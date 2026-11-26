package com.postbox.carrier

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Paack adapter over the reverse-engineered consumer tracking endpoint (researched in
 * G-72, `docs/carriers/paack.md`): a stateless Remix data request, no auth, no session
 * bootstrap — `GET {baseUrl}/shipments/tracking/order?tracking_number=<num>&postal_code=<cp>&_data=routes/tracking.order`.
 * The delivery postcode is a required second secret, so it is exposed as the `postcode`
 * parcel metadata field: a `track` call without a valid one throws
 * [CarrierError.NeedsMetadata] before any HTTP call is made.
 *
 * Outcome mapping: `200` JSON is the found order (timeline from `triggeredEventList`,
 * falling back to `eventList`; pending events carry no timestamp and are skipped);
 * unknown number or wrong postcode answer `204` with `x-remix-redirect` (or a plain
 * `302` document redirect) and map to [CarrierError.NotFound]; `429` is
 * [CarrierError.RateLimited]; anything else non-2xx (`400` missing params, `5xx`
 * `x-remix-catch`, Cloudflare blocks) plus transport and parse failures degrade to
 * [CarrierError.CarrierUnavailable] with a logged raw sample.
 *
 * Paack's staged `label` vocabulary maps onto [TrackingStatus] (see [mapStatus]);
 * delivery-failure labels become EXCEPTION with the free-text reason — carried in a
 * `variables.*[].path` binding resolved against `orderTrackData` — preserved in the
 * event description.
 */
class PaackAdapter(
    private val client: HttpClient,
    private val rateLimiter: CarrierRateLimiter,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : CarrierAdapter {

    override val carrier: CarrierId = CarrierId.PAACK

    override val requiredMetadata: Map<String, MetadataField> = mapOf(
        POSTCODE to MetadataField(
            name = POSTCODE,
            label = "Delivery postcode",
            hint = "Postcode of the delivery address in its country format (e.g. 4250-004 in Portugal, 28001 in Spain)",
            validationRegex = POSTCODE_REGEX,
        )
    )

    override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult {
        val postcode = metadata[POSTCODE]
        if (postcode == null || !POSTCODE_PATTERN.matches(postcode)) {
            throw CarrierError.NeedsMetadata(requiredMetadata.values.toList())
        }
        val code = number.trim()
        rateLimiter.acquire()
        val response = try {
            client.get("$baseUrl$TRACK_PATH") {
                url.parameters.append("tracking_number", code)
                url.parameters.append("postal_code", postcode)
                url.parameters.append("_data", DATA_PARAM)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CarrierError.CarrierUnavailable(e)
        }
        return when {
            response.status == HttpStatusCode.NoContent -> throw CarrierError.NotFound(code)
            response.status.value in 300..399 -> throw CarrierError.NotFound(code)
            response.status == HttpStatusCode.TooManyRequests -> {
                val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.seconds
                    ?: DEFAULT_RETRY_AFTER
                throw CarrierError.RateLimited(retryAfter)
            }
            !response.status.isSuccess() -> {
                throw unavailable(
                    "tracking endpoint answered HTTP ${response.status.value}",
                    IllegalStateException("Paack tracking HTTP ${response.status.value}"),
                    response.readSample(),
                )
            }
            else -> normalize(code, response.bodyAsTextCatching())
        }
    }

    private fun normalize(number: String, body: String): TrackingResult {
        val element = try {
            paackJson.parseToJsonElement(body)
        } catch (e: Exception) {
            throw unavailable("unparseable tracking body", e, body)
        }
        val payload = try {
            paackJson.decodeFromJsonElement<PaackPayload>(element)
        } catch (e: Exception) {
            throw unavailable("unexpected tracking payload shape", e, body)
        }
        val order = payload.orderTrackData
            ?: throw unavailable("payload carried no orderTrackData", null, body)
        val orderRaw = element.jsonObject["orderTrackData"]?.jsonObject
        val rawEvents = payload.triggeredEventList ?: payload.eventList ?: emptyList()
        val events = rawEvents.mapNotNull { it.toTrackingEvent(orderRaw) }
        val status = payload.activeEvent?.takeIf { it.timestamp != null }?.let { mapStatus(it.label) }
            ?: events.maxByOrNull { it.occurredAt }?.status
            ?: TrackingStatus.PENDING
        return TrackingResult(
            status = status,
            eta = order.expectedDeliveryTs?.end?.let(::parseTimestamp),
            carrierService = order.serviceType?.takeIf { it.isNotBlank() } ?: order.deliveryType,
            events = events,
        )
    }

    private fun PaackEvent.toTrackingEvent(orderRaw: JsonObject?): TrackingEvent? {
        val occurredAt = timestamp?.let(::parseTimestamp) ?: return null
        val status = mapStatus(label)
        val base = label?.takeIf { it.isNotBlank() } ?: id ?: "Paack tracking event"
        val reason = if (status == TrackingStatus.EXCEPTION) reasonText(orderRaw) else null
        return TrackingEvent(
            occurredAt = occurredAt,
            status = status,
            description = if (reason.isNullOrBlank()) base else "$base: $reason",
            location = location?.let { loc ->
                listOfNotNull(loc.city, loc.country).joinToString(", ").takeIf { it.isNotBlank() }
            },
        )
    }

    private fun PaackEvent.reasonText(orderRaw: JsonObject?): String? {
        if (orderRaw == null) return null
        val texts = variables.orEmpty().values.flatten()
            .filter { binding ->
                val path = binding.path.orEmpty()
                binding.fieldType !in DATE_TIME_FIELD_TYPES &&
                    path.isNotEmpty() &&
                    STRUCTURAL_PATH_PREFIXES.none { path.startsWith(it) }
            }
            .mapNotNull { binding ->
                (resolveBinding(orderRaw, binding) as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            }
            .distinct()
        return texts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun resolveBinding(root: JsonObject, binding: PaackVariable): JsonElement? {
        val segments = binding.path.orEmpty().split('.')
        var current: JsonElement = root
        var condition: PaackCondition? = binding.condition
        var index = 0
        while (index < segments.size) {
            when (current) {
                is JsonObject -> {
                    current = current[segments[index]] ?: return null
                    index++
                }
                is JsonArray -> {
                    val cond = condition ?: return null
                    condition = null
                    val sourceField = cond.sourceField ?: return null
                    current = current.firstOrNull { element ->
                        element is JsonObject &&
                            (element[sourceField] as? JsonPrimitive)?.takeIf { it.isString }?.content == cond.value
                    } ?: return null
                }
                else -> return null
            }
        }
        return current
    }

    private fun mapStatus(label: String?): TrackingStatus = when (label) {
        null -> TrackingStatus.UNKNOWN
        "manifested" -> TrackingStatus.PENDING
        "inDelivery" -> TrackingStatus.OUT_FOR_DELIVERY
        "scannedAtOrigin", "inProgress", "driverAssigned", "pudoAssigned", "collected", "collectionPlanned" ->
            TrackingStatus.IN_TRANSIT
        else -> when {
            label.startsWith("delivered") || label == "droppedInPudo" -> TrackingStatus.DELIVERED
            label in FAILURE_LABELS ||
                label.startsWith("return") ||
                label.startsWith("notCollected") ||
                label.startsWith("inPudoToReturn") -> TrackingStatus.EXCEPTION
            else -> TrackingStatus.UNKNOWN
        }
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

    private fun unavailable(reason: String, cause: Throwable?, rawSample: String): CarrierError.CarrierUnavailable {
        log.warn("Paack adapter degrading to CarrierUnavailable ($reason); raw sample: {}", rawSample.take(RAW_SAMPLE_LENGTH))
        return CarrierError.CarrierUnavailable(cause ?: IllegalStateException("Paack $reason"))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://paack.co"
        const val POSTCODE = "postcode"
        const val POSTCODE_REGEX = "^[0-9A-Za-z][0-9A-Za-z -]{2,9}$"

        private const val TRACK_PATH = "/shipments/tracking/order"
        private const val DATA_PARAM = "routes/tracking.order"
        private const val RAW_SAMPLE_LENGTH = 1000
        private val DEFAULT_RETRY_AFTER: Duration = 60.seconds
        private val POSTCODE_PATTERN = Regex(POSTCODE_REGEX)
        private val DATE_TIME_FIELD_TYPES = setOf("date", "time")
        private val STRUCTURAL_PATH_PREFIXES = listOf(
            "retailer_name", "expected_", "delivery_address", "pudo", "passcode", "events.timestamp",
        )
        private val FAILURE_LABELS = setOf(
            "absent", "incorrectAddress", "codNotAccepted", "attemptedOther", "rejected", "cancelled",
            "logisticIssueOpen", "logisticIssueClosed", "orderReactivated",
            "appointmentChangedToNextAvailableDay", "additionalDeliveryAttemptScheduled",
            "incorrectAddressReturn", "pudoFull", "pudoClosed", "rejectedByPudo", "lostByPudo",
        )
        private val paackJson = Json { ignoreUnknownKeys = true }
        private val log = LoggerFactory.getLogger(PaackAdapter::class.java)
    }
}

@Serializable
private data class PaackPayload(
    @SerialName("activeEvent") val activeEvent: PaackEvent? = null,
    @SerialName("eventList") val eventList: List<PaackEvent>? = null,
    @SerialName("triggeredEventList") val triggeredEventList: List<PaackEvent>? = null,
    @SerialName("orderTrackData") val orderTrackData: PaackOrderTrackData? = null,
)

@Serializable
private data class PaackOrderTrackData(
    @SerialName("service_type") val serviceType: String? = null,
    @SerialName("delivery_type") val deliveryType: String? = null,
    @SerialName("expected_delivery_ts") val expectedDeliveryTs: PaackWindow? = null,
)

@Serializable
private data class PaackWindow(
    val start: String? = null,
    val end: String? = null,
)

@Serializable
private data class PaackEvent(
    val id: String? = null,
    val label: String? = null,
    @SerialName("event_type") val eventType: String? = null,
    @SerialName("uniq_id") val uniqId: String? = null,
    val timestamp: String? = null,
    @SerialName("next_id") val nextId: String? = null,
    val timeline: Boolean? = null,
    val status: String? = null,
    val variables: Map<String, List<PaackVariable>>? = null,
    val location: PaackLocation? = null,
)

@Serializable
private data class PaackLocation(
    val city: String? = null,
    val country: String? = null,
)

@Serializable
private data class PaackVariable(
    val poe: String? = null,
    val path: String? = null,
    @SerialName("field_type") val fieldType: String? = null,
    val condition: PaackCondition? = null,
)

@Serializable
private data class PaackCondition(
    @SerialName("sourceField") val sourceField: String? = null,
    val operator: String? = null,
    val value: String? = null,
)
