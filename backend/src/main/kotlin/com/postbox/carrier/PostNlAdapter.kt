package com.postbox.carrier

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * PostNL adapter over the reverse-engineered consumer track & trace endpoint
 * (researched in G-70, `docs/carriers/postnl.md`): a single unauthenticated
 * `GET {baseUrl}/track-and-trace/api/trackAndTrace/<BARCODE>-<COUNTRY>-<POSTCODE>?language=<lang>`
 * with a plain `Accept: application/json` — no session, no CSRF token, no special
 * headers. The lookup key pairs the barcode with the ISO-2 delivery country and the
 * recipient postcode (both act as the secret), so the `postcode` parcel metadata
 * field is required: a `track` call without a usable one throws
 * [CarrierError.NeedsMetadata] before any HTTP call. The country is derived from the
 * postcode shape (NL `1234AB`, PT `4250-004`, BE `1234`, LU `L-1234`).
 *
 * Outcome mapping: `200` with a populated `colli` is the shipment (timeline from
 * `analyticsInfo.allObservations`, falling back to the UI `observations` list);
 * `200` with an empty `colli` — unknown barcode *or* wrong country/postcode, the two
 * are indistinguishable — is [CarrierError.NotFound]; a pre-advised number (colli
 * populated with only `A01`) stays PENDING and is never `NotFound`, so detection
 * probes do not fall through to another carrier. Any non-2xx answer (Akamai 403,
 * 429/503 with `Retry-After`), an unparseable body, or an unexpected payload shape
 * degrades to [CarrierError.CarrierUnavailable] with a logged raw sample — except
 * that `200` bodies are never logged, they may carry recipient PII.
 *
 * Observation codes map onto [TrackingStatus] (see [mapCode]) and the localized
 * `description` text is kept verbatim in the event. The recipient/sender/address/
 * e-mail/phone-hash fields are never deserialized, persisted or logged.
 */
class PostNlAdapter(
    private val client: HttpClient,
    private val rateLimiter: CarrierRateLimiter,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val language: String = DEFAULT_LANGUAGE,
) : CarrierAdapter {

    override val carrier: CarrierId = CarrierId.POSTNL

    override val requiredMetadata: Map<String, MetadataField> = mapOf(
        POSTCODE to MetadataField(
            name = POSTCODE,
            label = "Delivery postcode",
            hint = "Postcode of the delivery address in its country format — NL 1234AB, PT 4250-004, BE 1234, LU L-1234",
            validationRegex = POSTCODE_REGEX,
        )
    )

    override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult {
        val code = number.trim().uppercase()
        val postcode = metadata[POSTCODE]?.trim()?.uppercase()?.replace(WHITESPACE, "")
        val country = postcode?.takeIf { POSTCODE_PATTERN.matches(it) }?.let(::countryFor)
        if (country == null) {
            throw CarrierError.NeedsMetadata(requiredMetadata.values.toList())
        }
        rateLimiter.acquire()
        val key = "$code-$country-$postcode"
        val response = try {
            client.get("$baseUrl$TRACK_PATH/$key") {
                url.parameters.append("language", language)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CarrierError.CarrierUnavailable(e)
        }
        if (!response.status.isSuccess()) {
            throw unavailable(
                "track-and-trace answered HTTP ${response.status.value}",
                IllegalStateException("PostNL track-and-trace HTTP ${response.status.value}"),
                response.readSample(),
            )
        }
        return normalize(code, response.bodyAsTextCatching())
    }

    private fun normalize(number: String, body: String): TrackingResult {
        val envelope = try {
            postnlJson.decodeFromString<PostNlEnvelope>(body)
        } catch (e: Exception) {
            throw unavailable("unparseable track-and-trace body", e, null)
        }
        val colli = envelope.colli
            ?: throw unavailable("body carried no colli object", null, null)
        val shipment = colli[number] ?: colli.values.firstOrNull()
            ?: throw CarrierError.NotFound(number)
        val rawEvents = shipment.analyticsInfo?.allObservations?.takeIf { it.isNotEmpty() }
            ?: shipment.observations.orEmpty()
        val events = rawEvents.mapNotNull { it.toTrackingEvent() }
        val status = when {
            shipment.isDelivered == true -> TrackingStatus.DELIVERED
            else -> events.maxByOrNull { it.occurredAt }?.status
                ?: mapPhase(shipment.statusPhase?.index)
                ?: TrackingStatus.PENDING
        }
        return TrackingResult(
            status = status,
            eta = shipment.eta?.end?.let(::parseTimestamp),
            carrierService = shipment.context?.shipmentType?.takeIf { it.isNotBlank() },
            events = events,
        )
    }

    private fun PostNlObservation.toTrackingEvent(): TrackingEvent? {
        if (observationCode in IGNORED_CODES) return null
        val occurredAt = observationDate?.let(::parseTimestamp) ?: return null
        return TrackingEvent(
            occurredAt = occurredAt,
            status = mapCode(observationCode),
            description = description?.takeIf { it.isNotBlank() }
                ?: observationCode
                ?: "PostNL tracking event",
            location = null,
        )
    }

    private fun mapCode(code: String?): TrackingStatus = when (code) {
        null -> TrackingStatus.UNKNOWN
        "A01" -> TrackingStatus.PENDING
        "B01", "J01", "J02", "J06", "X02", "X08", "Q12" -> TrackingStatus.IN_TRANSIT
        "J05", "Y05" -> TrackingStatus.OUT_FOR_DELIVERY
        "Z01" -> TrackingStatus.DELIVERED
        "H04" -> TrackingStatus.EXCEPTION
        else -> if (code.startsWith("K")) TrackingStatus.EXCEPTION else TrackingStatus.UNKNOWN
    }

    private fun mapPhase(index: Int?): TrackingStatus? = when (index) {
        0 -> TrackingStatus.PENDING
        1, 2 -> TrackingStatus.IN_TRANSIT
        3 -> TrackingStatus.OUT_FOR_DELIVERY
        4 -> TrackingStatus.DELIVERED
        else -> null
    }

    private fun countryFor(postcode: String): String? = when {
        NL_POSTCODE.matches(postcode) -> "NL"
        PT_POSTCODE.matches(postcode) -> "PT"
        LU_POSTCODE.matches(postcode) -> "LU"
        BE_POSTCODE.matches(postcode) -> "BE"
        else -> null
    }

    private suspend fun io.ktor.client.statement.HttpResponse.readSample(): String = try {
        bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ""
    }

    private suspend fun io.ktor.client.statement.HttpResponse.bodyAsTextCatching(): String = try {
        bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw CarrierError.CarrierUnavailable(e)
    }

    private fun parseTimestamp(raw: String): Instant? = try {
        Instant.parse(raw)
    } catch (ignored: DateTimeParseException) {
        try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (ignored: DateTimeParseException) {
            try {
                LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC)
            } catch (ignored: DateTimeParseException) {
                null
            }
        }
    }

    private fun unavailable(reason: String, cause: Throwable?, rawSample: String?): CarrierError.CarrierUnavailable {
        if (rawSample != null) {
            log.warn("PostNL adapter degrading to CarrierUnavailable ($reason); raw sample: {}", rawSample.take(RAW_SAMPLE_LENGTH))
        } else {
            log.warn("PostNL adapter degrading to CarrierUnavailable ($reason); 200 body not logged (may carry recipient PII)")
        }
        return CarrierError.CarrierUnavailable(cause ?: IllegalStateException("PostNL $reason"))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://tracking.postnl.nl"
        const val POSTCODE = "postcode"
        const val POSTCODE_REGEX = "^([1-9]\\d{3}[A-Z]{2}|\\d{4}-?\\d{3}|L-?\\d{4}|[1-9]\\d{3})$"

        private const val TRACK_PATH = "/track-and-trace/api/trackAndTrace"
        private const val DEFAULT_LANGUAGE = "en"
        private const val RAW_SAMPLE_LENGTH = 1000
        private val WHITESPACE = Regex("\\s+")
        private val POSTCODE_PATTERN = Regex(POSTCODE_REGEX)
        private val NL_POSTCODE = Regex("^[1-9]\\d{3}[A-Z]{2}$")
        private val PT_POSTCODE = Regex("^\\d{4}-?\\d{3}$")
        private val LU_POSTCODE = Regex("^L-?\\d{4}$")
        private val BE_POSTCODE = Regex("^[1-9]\\d{3}$")
        private val IGNORED_CODES = setOf("A98", "A95", "A04", "Q11")
        private val postnlJson = Json { ignoreUnknownKeys = true }
        private val log = LoggerFactory.getLogger(PostNlAdapter::class.java)
    }
}

@Serializable
private data class PostNlEnvelope(
    val colli: Map<String, PostNlCollo>? = null,
    val processed: String? = null,
)

@Serializable
private data class PostNlCollo(
    val barcode: String? = null,
    val eta: PostNlEta? = null,
    val isDelivered: Boolean? = null,
    val deliveryDate: String? = null,
    val statusPhase: PostNlStatusPhase? = null,
    val observations: List<PostNlObservation>? = null,
    val analyticsInfo: PostNlAnalytics? = null,
    val context: PostNlContext? = null,
)

@Serializable
private data class PostNlEta(
    val type: String? = null,
    val start: String? = null,
    val end: String? = null,
    val minutesOverdue: Int? = null,
)

@Serializable
private data class PostNlStatusPhase(
    val index: Int? = null,
    val message: String? = null,
)

@Serializable
private data class PostNlObservation(
    val observationDate: String? = null,
    val observationCode: String? = null,
    val description: String? = null,
)

@Serializable
private data class PostNlAnalytics(
    val allObservations: List<PostNlObservation>? = null,
    val shipmentStage: String? = null,
)

@Serializable
private data class PostNlContext(
    val shipmentType: String? = null,
)
