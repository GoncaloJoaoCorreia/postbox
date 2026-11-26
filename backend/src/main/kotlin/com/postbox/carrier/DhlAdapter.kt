package com.postbox.carrier

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * DHL Shipment Tracking - Unified API adapter: `GET {baseUrl}/track/shipments` with the
 * `DHL-API-Key` header. One normalizer covers DHL Express (10-digit numbers) and DHL
 * eCommerce (`GM…` numbers) alike — the `service` query hint only steers resolution, the
 * response shape is identical. Every [track] call is paced by [rateLimiter] client-side
 * (free tier: 250 calls/day, at most one call per 5 seconds), independent of the caller.
 *
 * DHL's `statusCode` vocabulary (`pre-transit`, `transit`, `delivered`, `failure`,
 * `unknown`) maps onto [TrackingStatus]; a known number whose event list is still empty
 * is PENDING, a 404 problem-detail answer is [CarrierError.NotFound], and a 429 is
 * [CarrierError.RateLimited] carrying the `Retry-After` header. Transport and parse
 * failures surface as [CarrierError.CarrierUnavailable] so the detection engine's
 * carrier-elimination catch and the refresh service's soft-error path both apply.
 */
class DhlAdapter(
    private val client: HttpClient,
    private val apiKey: String,
    private val rateLimiter: CarrierRateLimiter,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : CarrierAdapter {

    override val carrier: CarrierId = CarrierId.DHL

    override val requiredMetadata: Map<String, MetadataField> = emptyMap()

    override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult {
        rateLimiter.acquire()
        val response = try {
            client.get("$baseUrl/track/shipments") {
                url.parameters.append("trackingNumber", number)
                serviceHintFor(number)?.let { url.parameters.append("service", it) }
                header(DHL_API_KEY_HEADER, apiKey)
                accept(ContentType.Application.Json)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CarrierError.CarrierUnavailable(e)
        }
        return when {
            response.status == HttpStatusCode.NotFound -> throw CarrierError.NotFound(number)
            response.status == HttpStatusCode.TooManyRequests -> {
                val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.seconds
                    ?: DEFAULT_RETRY_AFTER
                throw CarrierError.RateLimited(retryAfter)
            }
            !response.status.isSuccess() -> throw CarrierError.CarrierUnavailable(
                IllegalStateException("DHL API answered HTTP ${response.status.value}")
            )
            else -> {
                val body = try {
                    response.bodyAsText()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw CarrierError.CarrierUnavailable(e)
                }
                normalize(number, body)
            }
        }
    }

    private fun normalize(number: String, body: String): TrackingResult {
        val shipments = try {
            dhlJson.decodeFromString<DhlShipmentsResponse>(body).shipments.orEmpty()
        } catch (e: Exception) {
            throw CarrierError.CarrierUnavailable(e)
        }
        val shipment = shipments.firstOrNull() ?: throw CarrierError.NotFound(number)
        val events = shipment.events.orEmpty().mapNotNull { it.toTrackingEvent() }
        val status = when {
            events.isEmpty() -> TrackingStatus.PENDING
            shipment.status?.statusCode != null -> mapStatus(shipment.status.statusCode)
            else -> events.maxByOrNull { it.occurredAt }?.status ?: TrackingStatus.UNKNOWN
        }
        return TrackingResult(
            status = status,
            eta = shipment.estimatedTimeOfDelivery?.let(::parseTimestamp),
            carrierService = shipment.details?.product?.productName ?: shipment.service,
            events = events,
        )
    }

    private fun mapStatus(statusCode: String?): TrackingStatus = when (statusCode) {
        "pre-transit" -> TrackingStatus.PENDING
        "transit" -> TrackingStatus.IN_TRANSIT
        "delivered" -> TrackingStatus.DELIVERED
        "failure" -> TrackingStatus.EXCEPTION
        else -> TrackingStatus.UNKNOWN
    }

    private fun DhlEvent.toTrackingEvent(): TrackingEvent? {
        val occurredAt = timestamp?.let(::parseTimestamp) ?: return null
        return TrackingEvent(
            occurredAt = occurredAt,
            status = mapStatus(statusCode),
            description = description ?: status ?: statusCode ?: "DHL tracking event",
            location = location?.address?.toLocationString(),
        )
    }

    private fun DhlAddress.toLocationString(): String? {
        val parts = listOfNotNull(addressLocality, postalCode, countryCode)
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun serviceHintFor(number: String): String? {
        val upper = number.uppercase()
        return when {
            EXPRESS_NUMBER.matches(number) -> "express"
            ECOMMERCE_NUMBER.matches(upper) -> "ecommerce"
            else -> null
        }
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
                try {
                    LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant()
                } catch (ignored: DateTimeParseException) {
                    null
                }
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api-eu.dhl.com"
        const val DHL_API_KEY_HEADER = "DHL-API-Key"

        private val DEFAULT_RETRY_AFTER: Duration = 5.seconds
        private val EXPRESS_NUMBER = Regex("""^\d{10}$""")
        private val ECOMMERCE_NUMBER = Regex("""^GM\d{16}$""")
        private val dhlJson = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class DhlShipmentsResponse(val shipments: List<DhlShipment>? = null)

@Serializable
private data class DhlShipment(
    val service: String? = null,
    val status: DhlEvent? = null,
    val estimatedTimeOfDelivery: String? = null,
    val details: DhlDetails? = null,
    val events: List<DhlEvent>? = null,
)

@Serializable
private data class DhlDetails(val product: DhlProduct? = null)

@Serializable
private data class DhlProduct(val productName: String? = null)

@Serializable
private data class DhlEvent(
    val timestamp: String? = null,
    val location: DhlPlace? = null,
    val statusCode: String? = null,
    val status: String? = null,
    val description: String? = null,
)

@Serializable
private data class DhlPlace(val address: DhlAddress? = null)

@Serializable
private data class DhlAddress(
    val addressLocality: String? = null,
    val postalCode: String? = null,
    val countryCode: String? = null,
)
