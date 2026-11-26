package com.postbox.carrier

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * EcoScooting adapter over Cainiao's public "link" gateway, the lookup behind the
 * consumer tracking page `www.ecoscooting.com/tracking/:id` (researched in G-73; the
 * original `play.ecoscooting.com` page and its own API are dead): a form-encoded
 * `POST {baseUrl}` with `logistics_interface` carrying the mail number, no session,
 * no captcha, no auth. The lookup is case-sensitive, so the number is sent trimmed
 * but never case-normalized.
 *
 * All answers are HTTP 200 with the outcome in the body: `success=false` with
 * `errorCode=B00604` (number out of scope) is [CarrierError.NotFound], any other
 * failure (`B00502`, XML `S12`, JSON `S16`/`S23`) or a non-2xx/transport failure
 * degrades to [CarrierError.CarrierUnavailable] with a logged raw sample. A
 * pre-advised number (`success=true` with only `CREATE_ORDER_CES`, or no statuses
 * yet) stays a PENDING [TrackingResult] — distinct from `NotFound`. `actionCode`
 * maps onto [TrackingStatus] and the localized `description`/`statusName` text is
 * kept verbatim in the event description.
 */
class EcoScootingAdapter(
    private val client: HttpClient,
    private val rateLimiter: CarrierRateLimiter,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val locale: String = DEFAULT_LOCALE,
) : CarrierAdapter {

    override val carrier: CarrierId = CarrierId.ECOSCOOTING

    override val requiredMetadata: Map<String, MetadataField> = emptyMap()

    override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult {
        val code = number.trim()
        rateLimiter.acquire()
        val response = try {
            client.post(baseUrl) {
                setBody(
                    TextContent(
                        formBody(code),
                        ContentType.Application.FormUrlEncoded.withCharset(Charsets.UTF_8),
                        null,
                    )
                )
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CarrierError.CarrierUnavailable(e)
        }
        if (!response.status.isSuccess()) {
            val sample = try {
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            throw unavailable(
                "link.do answered HTTP ${response.status.value}",
                IllegalStateException("link.do HTTP ${response.status.value}"),
                sample,
            )
        }
        val body = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CarrierError.CarrierUnavailable(e)
        }
        return normalize(code, body)
    }

    private fun normalize(number: String, body: String): TrackingResult {
        if (body.trimStart().startsWith("<")) {
            throw unavailable("link.do answered an XML error body", null, body)
        }
        val root = try {
            linkdoJson.decodeFromString<LinkdoResponse>(body)
        } catch (e: Exception) {
            throw unavailable("unparseable link.do body", e, body)
        }
        val success = root.success?.content?.equals("true", ignoreCase = true) == true
        if (!success) {
            if (root.errorCode == ERROR_OUT_OF_SCOPE) throw CarrierError.NotFound(number)
            throw unavailable("link.do success=false ${root.errorCode}: ${root.errorMsg}", null, body)
        }
        val events = root.statuses.orEmpty().mapNotNull { it.toTrackingEvent() }
        val status = events.maxByOrNull { it.occurredAt }?.status ?: TrackingStatus.PENDING
        return TrackingResult(
            status = status,
            eta = null,
            carrierService = root.solutionParam?.solutionCode?.takeIf { it.isNotBlank() },
            events = events,
        )
    }

    private fun LinkdoStatus.toTrackingEvent(): TrackingEvent? {
        val occurredAt = occurredAt() ?: return null
        return TrackingEvent(
            occurredAt = occurredAt,
            status = mapStatus(actionCode),
            description = description?.takeIf { it.isNotBlank() }
                ?: statusName?.takeIf { it.isNotBlank() }
                ?: actionCode
                ?: "EcoScooting tracking event",
            location = location?.takeIf { it.isNotBlank() },
        )
    }

    private fun LinkdoStatus.occurredAt(): Instant? =
        opTimestamp?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: datetime?.let(::parseDatetime)

    private fun mapStatus(actionCode: String?): TrackingStatus = when (actionCode) {
        "CREATE_ORDER_CES" -> TrackingStatus.PENDING
        "SC_INBOUND", "SC_OUTBOUND", "TRANSFER_DEPART", "TRANSFER_ARRIVE", "SL_ACCEPT" -> TrackingStatus.IN_TRANSIT
        "LM_DELIVERY_DEPART", "PUDO_INBOUND", "PUDO_DELIVERY" -> TrackingStatus.OUT_FOR_DELIVERY
        "LM_SIGN_SUCCESS", "PUDO_SIGN_SUCCESS" -> TrackingStatus.DELIVERED
        "LM_DELIVERY_FAILURE" -> TrackingStatus.EXCEPTION
        else -> TrackingStatus.UNKNOWN
    }

    private fun parseDatetime(raw: String): Instant? {
        val match = DATETIME_PATTERN.matchEntire(raw.trim()) ?: return null
        return try {
            LocalDateTime
                .parse("${match.groupValues[1]}T${match.groupValues[2]}")
                .toInstant(ZoneOffset.ofHours(match.groupValues[3].toInt()))
        } catch (ignored: DateTimeParseException) {
            null
        }
    }

    private fun formBody(number: String): String {
        val logisticsInterface = buildJsonObject {
            put("mailNo", number)
            put("locale", locale)
            put("role", "endUser")
        }.toString()
        return listOf(
            "logistics_interface" to logisticsInterface,
            "msg_type" to MSG_TYPE,
            "logistic_provider_id" to LOGISTIC_PROVIDER_ID,
            "data_digest" to DATA_DIGEST,
            "to_code" to TO_CODE,
        ).joinToString("&") { (key, value) -> "${key.encodeForm()}=${value.encodeForm()}" }
    }

    private fun String.encodeForm(): String = URLEncoder.encode(this, Charsets.UTF_8)

    private fun unavailable(reason: String, cause: Throwable?, rawSample: String): CarrierError.CarrierUnavailable {
        log.warn("EcoScooting adapter degrading to CarrierUnavailable ($reason); raw sample: {}", rawSample.take(RAW_SAMPLE_LENGTH))
        return CarrierError.CarrierUnavailable(cause ?: IllegalStateException("EcoScooting $reason"))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://de-link.cainiao.com/gateway/link.do"
        const val DEFAULT_LOCALE = "en_US"
        private const val MSG_TYPE = "CN_OVERSEA_LOGISTICS_INQUIRY_TRACKING"
        private const val LOGISTIC_PROVIDER_ID = "DISTRIBUTOR_30250031"
        private const val DATA_DIGEST = "suibianxie"
        private const val TO_CODE = "CNL_EU"
        private const val ERROR_OUT_OF_SCOPE = "B00604"
        private const val RAW_SAMPLE_LENGTH = 1000
        private val DATETIME_PATTERN = Regex("""^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}) UTC([+-]\d{1,2})$""")
        private val linkdoJson = Json { ignoreUnknownKeys = true }
        private val log = LoggerFactory.getLogger(EcoScootingAdapter::class.java)
    }
}

@Serializable
private data class LinkdoResponse(
    val success: JsonPrimitive? = null,
    val errorCode: String? = null,
    val errorMsg: String? = null,
    val statuses: List<LinkdoStatus>? = null,
    val solutionParam: LinkdoSolution? = null,
)

@Serializable
private data class LinkdoSolution(val solutionCode: String? = null)

@Serializable
private data class LinkdoStatus(
    val datetime: String? = null,
    val opTimestamp: String? = null,
    val statusName: String? = null,
    val description: String? = null,
    val actionCode: String? = null,
    val location: String? = null,
)
