package com.postbox.carrier

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * CTT (Correios de Portugal) adapter over the public "track object" screenservices JSON
 * endpoint of the OutSystems app at `appserver.ctt.pt/CustomerArea` (researched in G-69;
 * the legacy `feapl_2 .../objectSearch.jspx` HTML endpoint is stale). No login and no
 * captcha token are needed server-side: the static anonymous `X-CSRFToken` suffices, but a
 * browser-like User-Agent is required (Cloudflare 403s tool UAs) — supplied by the
 * per-carrier client settings. Each [track] call fetches a fresh `moduleVersion` token and
 * POSTs the detail data action, which returns the full event timeline for one object.
 *
 * All answers are HTTP 200 with the status in the body: `Found=false` is
 * [CarrierError.NotFound], `Result.Success=false` / a server `exception` / an unexpected
 * shape / a non-2xx status degrade to [CarrierError.CarrierUnavailable] with a logged raw
 * sample, `isIpLocked` is [CarrierError.RateLimited]. `StateId`/`EventCode` map onto
 * [TrackingStatus]; the PT `Event` text is kept verbatim in the event description.
 */
class PostCttAdapter(
    private val client: HttpClient,
    private val rateLimiter: CarrierRateLimiter,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : CarrierAdapter {

    override val carrier: CarrierId = CarrierId.CTT

    override val requiredMetadata: Map<String, MetadataField> = emptyMap()

    override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult {
        val code = number.trim().uppercase()
        val moduleVersion = fetchModuleVersion()
        rateLimiter.acquire()
        val response = try {
            client.post("$baseUrl$DETAIL_PATH") {
                setBody(
                    TextContent(
                        detailPayload(moduleVersion, code),
                        ContentType.Application.Json.withCharset(Charsets.UTF_8),
                        null,
                    )
                )
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                header(CSRF_HEADER, ANON_CSRF_TOKEN)
                header(HttpHeaders.Origin, baseUrl)
                header(HttpHeaders.Referrer, "$baseUrl/PublicArea")
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
                "screenservices answered HTTP ${response.status.value}",
                IllegalStateException("CTT screenservices HTTP ${response.status.value}"),
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

    private suspend fun fetchModuleVersion(): String {
        rateLimiter.acquire()
        val response = try {
            client.get("$baseUrl/moduleservices/moduleversioninfo?${System.currentTimeMillis()}") {
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                header(CSRF_HEADER, ANON_CSRF_TOKEN)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CarrierError.CarrierUnavailable(e)
        }
        if (!response.status.isSuccess()) {
            throw CarrierError.CarrierUnavailable(
                IllegalStateException("CTT moduleversioninfo answered HTTP ${response.status.value}")
            )
        }
        val body = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CarrierError.CarrierUnavailable(e)
        }
        val token = try {
            cttJson.decodeFromString<CttModuleVersion>(body).versionToken
        } catch (e: Exception) {
            throw unavailable("unparseable moduleversioninfo body", e, body)
        }
        return token ?: throw unavailable("moduleversioninfo carried no versionToken", null, body)
    }

    private fun normalize(number: String, body: String): TrackingResult {
        val envelope = try {
            cttJson.decodeFromString<CttEnvelope>(body)
        } catch (e: Exception) {
            throw unavailable("unparseable screenservices body", e, body)
        }
        envelope.exception?.let {
            throw unavailable("server exception ${it.name}: ${it.message}", null, body)
        }
        val data = envelope.data ?: throw unavailable("body carried no data object", null, body)
        val result = data.Result
        if (result?.isIpLocked == true) throw CarrierError.RateLimited(DEFAULT_RETRY_AFTER)
        val success = result?.Success ?: result?.Result?.Success
        if (success == false) {
            throw unavailable("Result.Success=false: ${result?.ErrorMessage ?: result?.Result?.ErrorMessage}", null, body)
        }
        val obj = data.ObjectEventsFromQuery
            ?: throw unavailable("body carried no ObjectEventsFromQuery", null, body)
        when (obj.Found) {
            false -> throw CarrierError.NotFound(number)
            null -> throw unavailable("ObjectEventsFromQuery carried no Found flag", null, body)
            true -> {
            }
        }
        val events = obj.Events?.List.orEmpty().mapNotNull { it.toTrackingEvent() }
        val status = events.maxByOrNull { it.occurredAt }?.status ?: TrackingStatus.PENDING
        return TrackingResult(
            status = status,
            eta = parseEta(obj.DeliveryDeadlineEnd),
            carrierService = obj.ShipmentProduct?.takeIf { it.isNotBlank() },
            events = events,
        )
    }

    private fun CttEvent.toTrackingEvent(): TrackingEvent? {
        val occurredAt = DateTime?.let(::parseTimestamp) ?: return null
        return TrackingEvent(
            occurredAt = occurredAt,
            status = mapStatus(StateId, EventCode),
            description = Event?.takeIf { it.isNotBlank() }
                ?: State?.takeIf { it.isNotBlank() }
                ?: EventCode
                ?: "CTT tracking event",
            location = Local?.takeIf { it.isNotBlank() },
        )
    }

    private fun mapStatus(stateId: Int?, eventCode: String?): TrackingStatus = when {
        eventCode == "EMI" || stateId == 12 -> TrackingStatus.DELIVERED
        eventCode == "EMZ" || stateId == 7 -> TrackingStatus.OUT_FOR_DELIVERY
        eventCode == "EMH" || stateId == 13 -> TrackingStatus.EXCEPTION
        eventCode == "EMB" || stateId == 11 -> TrackingStatus.IN_TRANSIT
        eventCode == "EMA" || stateId == 2 -> TrackingStatus.PENDING
        eventCode == "EMO" || stateId == 1 -> TrackingStatus.PENDING
        else -> TrackingStatus.UNKNOWN
    }

    private fun parseEta(raw: String?): Instant? {
        if (raw == null || raw.startsWith("1900-01-01")) return null
        return parseTimestamp(raw)
    }

    private fun parseTimestamp(raw: String): Instant? = try {
        Instant.parse(raw)
    } catch (ignored: DateTimeParseException) {
        try {
            LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC)
        } catch (ignored: DateTimeParseException) {
            null
        }
    }

    private fun detailPayload(moduleVersion: String, code: String): String = buildJsonObject {
        put("versionInfo", buildJsonObject {
            put("moduleVersion", moduleVersion)
            put("apiVersion", DETAIL_API_VERSION)
        })
        put("viewName", DETAIL_VIEW_NAME)
        put("screenData", buildJsonObject {
            put("variables", buildJsonObject {
                put("ObjectCodeInput", code)
                put("ObjectsToSearch", "")
                put("ObjectsToSearchList", buildJsonObject {
                    put("List", kotlinx.serialization.json.JsonArray(emptyList()))
                    put("EmptyListItem", "")
                })
                put("ShowList", false)
                put("IsRunning", false)
                put("IsIPBlocked", false)
                put("IsCaptchaValid", false)
                put("TokenCapcha", "")
                put("DisableButton_Search", false)
            })
        })
        put("inputParameters", buildJsonObject { })
    }.toString()

    private fun unavailable(reason: String, cause: Throwable?, rawSample: String): CarrierError.CarrierUnavailable {
        log.warn("CTT adapter degrading to CarrierUnavailable ($reason); raw sample: {}", rawSample.take(RAW_SAMPLE_LENGTH))
        return CarrierError.CarrierUnavailable(cause ?: IllegalStateException("CTT $reason"))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://appserver.ctt.pt/CustomerArea"
        private const val DETAIL_PATH =
            "/screenservices/CustomerArea/CustomerArea/PublicArea_Detail/DataActionGetObjectEventsByInputObjectCode"
        private const val DETAIL_API_VERSION = "o0WgZ3QtqpbJXXi+QDtVYA"
        private const val DETAIL_VIEW_NAME = "CustomerArea.PublicArea_Detail"
        private const val CSRF_HEADER = "X-CSRFToken"
        private const val ANON_CSRF_TOKEN = "T6C+9iB49TLra4jEsMeSckDMNhQ="
        private const val RAW_SAMPLE_LENGTH = 1000
        private val DEFAULT_RETRY_AFTER: Duration = 60.seconds
        private val cttJson = Json { ignoreUnknownKeys = true }
        private val log = LoggerFactory.getLogger(PostCttAdapter::class.java)
    }
}

@Serializable
private data class CttModuleVersion(val versionToken: String? = null)

@Serializable
private data class CttEnvelope(
    val data: CttData? = null,
    val exception: CttException? = null,
)

@Serializable
private data class CttException(val name: String? = null, val message: String? = null)

@Serializable
private data class CttData(
    val Result: CttResult? = null,
    val ObjectEventsFromQuery: CttObject? = null,
)

@Serializable
private data class CttResult(
    val Success: Boolean? = null,
    val ErrorMessage: String? = null,
    val isIpLocked: Boolean? = null,
    val Result: CttResult? = null,
)

@Serializable
private data class CttObject(
    val ObjectCode: String? = null,
    val Found: Boolean? = null,
    val ShipmentProduct: String? = null,
    val DeliveryDeadlineEnd: String? = null,
    val Events: CttEventList? = null,
)

@Serializable
private data class CttEventList(val List: List<CttEvent>? = null)

@Serializable
private data class CttEvent(
    val DateTime: String? = null,
    val State: String? = null,
    val StateId: Int? = null,
    val Event: String? = null,
    val EventCode: String? = null,
    val Local: String? = null,
)
