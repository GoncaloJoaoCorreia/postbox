package com.postbox.carrier

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class DhlAdapterTest {

    private class ManualClock(var now: kotlin.time.Instant) : Clock {
        override fun now(): kotlin.time.Instant = now
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val deliveredExpressResponse = """
        {
          "url": "https://api-eu.dhl.com/track/shipments?trackingNumber=1234567890",
          "shipments": [
            {
              "id": "1234567890",
              "service": "express",
              "status": {
                "timestamp": "2026-11-20T15:30:00Z",
                "location": { "address": { "countryCode": "DE", "postalCode": "04109", "addressLocality": "Leipzig" } },
                "statusCode": "delivered",
                "status": "DELIVERED",
                "description": "Delivered - signed for by recipient"
              },
              "estimatedTimeOfDelivery": "2026-11-21T18:00:00Z",
              "details": { "product": { "productName": "EXPRESS WORLDWIDE" } },
              "events": [
                {
                  "timestamp": "2026-11-20T15:30:00Z",
                  "location": { "address": { "countryCode": "DE", "postalCode": "04109", "addressLocality": "Leipzig" } },
                  "statusCode": "delivered",
                  "status": "DELIVERED",
                  "description": "Delivered - signed for by recipient"
                },
                {
                  "timestamp": "2026-11-19T08:12:00+01:00",
                  "location": { "address": { "countryCode": "DE", "addressLocality": "Bonn" } },
                  "statusCode": "transit",
                  "status": "IN TRANSIT",
                  "description": "Arrived at DHL facility"
                },
                {
                  "timestamp": "2026-11-18T10:00:00Z",
                  "location": { "address": { "countryCode": "PT", "addressLocality": "Lisbon" } },
                  "statusCode": "pre-transit",
                  "status": "PRE-TRANSIT",
                  "description": "Shipment information received"
                },
                {
                  "statusCode": "transit",
                  "description": "event without a timestamp cannot be placed on the timeline"
                }
              ]
            }
          ]
        }
    """.trimIndent()

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, jsonHeaders)

    private fun adapter(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        DhlAdapter(
            client = HttpClient(MockEngine(handler)),
            apiKey = "test-api-key",
            rateLimiter = CarrierRateLimiter(minInterval = 1.seconds),
            baseUrl = "https://dhl.test",
        )

    @Test
    fun `full DHL express response normalizes to a sorted timeline with status eta service and locations`() = runTest {
        val adapter = adapter { ok(deliveredExpressResponse) }

        val result = adapter.track("1234567890", emptyMap())

        assertEquals(TrackingStatus.DELIVERED, result.status)
        assertEquals(Instant.parse("2026-11-21T18:00:00Z"), result.eta)
        assertEquals("EXPRESS WORLDWIDE", result.carrierService)
        assertEquals(3, result.events.size)
        assertEquals(
            listOf(TrackingStatus.PENDING, TrackingStatus.IN_TRANSIT, TrackingStatus.DELIVERED),
            result.events.map { it.status },
            "events must be chronological regardless of response order",
        )
        assertEquals(
            listOf(
                Instant.parse("2026-11-18T10:00:00Z"),
                Instant.parse("2026-11-19T07:12:00Z"),
                Instant.parse("2026-11-20T15:30:00Z"),
            ),
            result.events.map { it.occurredAt },
        )
        assertEquals("Lisbon, PT", result.events[0].location)
        assertEquals("Bonn, DE", result.events[1].location)
        assertEquals("Leipzig, 04109, DE", result.events[2].location)
        assertEquals("Shipment information received", result.events[0].description)
    }

    @Test
    fun `known number with no events yet is PENDING, not an error`() = runTest {
        val adapter = adapter {
            ok(
                """
                { "shipments": [ { "id": "GM1234567890123456", "service": "ecommerce",
                    "status": { "timestamp": "2026-11-18T09:00:00Z", "statusCode": "pre-transit", "description": "Shipment information received" },
                    "events": [] } ] }
                """.trimIndent()
            )
        }

        val result = adapter.track("GM1234567890123456", emptyMap())

        assertEquals(TrackingStatus.PENDING, result.status)
        assertTrue(result.events.isEmpty())
        assertNull(result.eta)
        assertEquals("ecommerce", result.carrierService)
    }

    @Test
    fun `ecommerce response without product details falls back to the service field`() = runTest {
        val adapter = adapter {
            ok(
                """
                { "shipments": [ { "id": "GM1234567890123456", "service": "ecommerce",
                    "status": { "statusCode": "transit", "description": "In transit" },
                    "events": [ { "timestamp": "2026-11-19T12:00:00Z", "statusCode": "transit", "description": "Departed facility" } ] } ] }
                """.trimIndent()
            )
        }

        val result = adapter.track("GM1234567890123456", emptyMap())

        assertEquals(TrackingStatus.IN_TRANSIT, result.status)
        assertEquals("ecommerce", result.carrierService)
        assertEquals(1, result.events.size)
        assertNull(result.events[0].location)
    }

    @Test
    fun `empty shipments list maps to NotFound`() = runTest {
        val adapter = adapter { ok("""{ "shipments": [] }""") }

        val error = assertFailsWith<CarrierError.NotFound> {
            adapter.track("0000000000", emptyMap())
        }

        assertEquals("0000000000", error.number)
    }

    @Test
    fun `404 problem detail maps to NotFound carrying the tracking number`() = runTest {
        val adapter = adapter {
            respond(
                """{ "title": "No result found", "detail": "No shipment with given tracking number found.", "status": 404, "instance": "/shipment/9999999999" }""",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }

        val error = assertFailsWith<CarrierError.NotFound> {
            adapter.track("9999999999", emptyMap())
        }

        assertEquals("9999999999", error.number)
    }

    @Test
    fun `429 maps to RateLimited carrying the Retry-After header`() = runTest {
        val adapter = adapter {
            respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "42"))
        }

        val error = assertFailsWith<CarrierError.RateLimited> {
            adapter.track("1234567890", emptyMap())
        }

        assertEquals(42.seconds, error.retryAfter)
    }

    @Test
    fun `other non-success statuses map to CarrierUnavailable`() = runTest {
        val adapter = adapter { respond("{}", HttpStatusCode.InternalServerError, jsonHeaders) }

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("1234567890", emptyMap())
        }
    }

    @Test
    fun `request carries the API key header, tracking number and the service hint per number format`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val adapter = adapter { request ->
            seen.add(request)
            ok("""{ "shipments": [ { "id": "x", "events": [ { "timestamp": "2026-11-18T10:00:00Z", "statusCode": "transit" } ] } ] }""")
        }

        adapter.track("1234567890", emptyMap())
        adapter.track("GM1234567890123456", emptyMap())
        adapter.track("JVGL123456789012", emptyMap())

        assertEquals(3, seen.size)
        for (request in seen) {
            assertEquals("test-api-key", request.headers[DhlAdapter.DHL_API_KEY_HEADER])
            assertEquals(ContentType.Application.Json.toString(), request.headers[HttpHeaders.Accept])
        }
        assertEquals("1234567890", seen[0].url.parameters["trackingNumber"])
        assertEquals("express", seen[0].url.parameters["service"])
        assertEquals("GM1234567890123456", seen[1].url.parameters["trackingNumber"])
        assertEquals("ecommerce", seen[1].url.parameters["service"])
        assertEquals("JVGL123456789012", seen[2].url.parameters["trackingNumber"])
        assertNull(seen[2].url.parameters["service"])
    }

    @Test
    fun `track paces DHL calls through the injected rate limiter`() = runTest {
        val clock = ManualClock(kotlin.time.Instant.fromEpochMilliseconds(0))
        val requestTimes = mutableListOf<kotlin.time.Instant>()
        val engine = MockEngine { request ->
            requestTimes.add(clock.now())
            ok("""{ "shipments": [ { "id": "x", "events": [ { "timestamp": "2026-11-18T10:00:00Z", "statusCode": "transit" } ] } ] }""")
        }
        val adapter = DhlAdapter(
            client = HttpClient(engine),
            apiKey = "test-api-key",
            rateLimiter = CarrierRateLimiter(minInterval = 5.seconds, clock = clock),
            baseUrl = "https://dhl.test",
        )

        adapter.track("1234567890", emptyMap())
        val second = launch { adapter.track("1234567890", emptyMap()) }
        yield()
        advanceTimeBy(4.seconds)
        runCurrent()
        assertEquals(1, requestTimes.size, "the second call must still be waiting for the 5 s interval")
        clock.now = kotlin.time.Instant.fromEpochMilliseconds(5_000)
        advanceTimeBy(1.seconds)
        runCurrent()
        second.join()

        assertEquals(2, requestTimes.size)
        assertEquals(5.seconds, requestTimes[1] - requestTimes[0])
    }
}
