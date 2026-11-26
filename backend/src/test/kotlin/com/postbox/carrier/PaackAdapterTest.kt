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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

class PaackAdapterTest {

    private val jsonHeaders = headersOf(
        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
        "x-remix-response" to listOf("yes"),
    )

    private val notFoundHeaders = headersOf(
        "x-remix-redirect" to listOf("/tracking?postal_code=28001&tracking_number=XX000000000000&err=true"),
        "x-remix-status" to listOf("302"),
    )

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/paack/$name")) { "missing fixture $name" }.readText()

    private fun adapter(
        followRedirects: Boolean = true,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = PaackAdapter(
        client = HttpClient(MockEngine(handler)) { this.followRedirects = followRedirects },
        rateLimiter = CarrierRateLimiter(minInterval = 1.seconds),
        baseUrl = "https://paack.test",
    )

    private fun okFixture(name: String): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        { respond(fixture(name), HttpStatusCode.OK, jsonHeaders) }

    @Test
    fun `captured delivered fixture returns the full event timeline ending in DELIVERED`() = runTest {
        val adapter = adapter(handler = okFixture("delivered-200.json"))

        val result = adapter.track("534000021268935007", mapOf("postcode" to "4250-004"))

        assertEquals(TrackingStatus.DELIVERED, result.status)
        assertEquals(6, result.events.size)
        assertEquals(
            listOf(
                TrackingStatus.PENDING,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.OUT_FOR_DELIVERY,
                TrackingStatus.DELIVERED,
            ),
            result.events.map { it.status },
        )
        assertEquals(
            listOf("manifested", "scannedAtOrigin", "inProgress", "driverAssigned", "inDelivery", "delivered"),
            result.events.map { it.description },
        )
        val times = result.events.map { it.occurredAt }
        assertEquals(times.sorted(), times, "events must be chronological regardless of response order")
        assertEquals(Instant.parse("2026-11-23T17:13:49Z"), result.events.last().occurredAt)
        assertEquals(Instant.parse("2026-11-23T21:00:00Z"), result.eta)
        assertEquals("direct", result.carrierService)
        assertEquals("PT", result.events.last().location)
    }

    @Test
    fun `captured out-for-delivery fixture maps to OUT_FOR_DELIVERY and skips the pending next step`() = runTest {
        val adapter = adapter(handler = okFixture("out-for-delivery-200.json"))

        val result = adapter.track("534000021268935007", mapOf("postcode" to "4250-004"))

        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, result.status)
        assertEquals(5, result.events.size)
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, result.events.last().status)
        assertEquals("inDelivery", result.events.last().description)
    }

    @Test
    fun `unknown number answered with 204 and x-remix-redirect maps to NotFound`() = runTest {
        val adapter = adapter {
            respond("", HttpStatusCode.NoContent, notFoundHeaders)
        }

        val error = assertFailsWith<CarrierError.NotFound> {
            adapter.track("XX000000000000", mapOf("postcode" to "28001"))
        }

        assertEquals("XX000000000000", error.number)
    }

    @Test
    fun `document-request 302 redirect also maps to NotFound`() = runTest {
        val adapter = adapter(followRedirects = false) {
            respond("", HttpStatusCode.Found, headersOf("location" to listOf("/tracking?err=true")))
        }

        assertFailsWith<CarrierError.NotFound> {
            adapter.track("XX000000000000", mapOf("postcode" to "28001"))
        }
    }

    @Test
    fun `400 missing-params answer degrades to CarrierUnavailable since the adapter always sends both params`() = runTest {
        val adapter = adapter {
            respond("Missing required parameters", HttpStatusCode.BadRequest, headersOf("x-remix-catch" to listOf("yes")))
        }

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("534000021268935007", mapOf("postcode" to "4250-004"))
        }
    }

    @Test
    fun `5xx with x-remix-catch degrades to CarrierUnavailable`() = runTest {
        val adapter = adapter {
            respond("<html>upstream exploded</html>", HttpStatusCode.InternalServerError, headersOf("x-remix-catch" to listOf("yes")))
        }

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("534000021268935007", mapOf("postcode" to "4250-004"))
        }
    }

    @Test
    fun `unparseable 200 body degrades to CarrierUnavailable instead of crashing`() = runTest {
        val adapter = adapter {
            respond("<html><body>Cloudflare</body></html>", HttpStatusCode.OK, jsonHeaders)
        }

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("534000021268935007", mapOf("postcode" to "4250-004"))
        }
    }

    @Test
    fun `missing postcode metadata throws NeedsMetadata before any HTTP call`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val adapter = adapter { request ->
            seen.add(request)
            respond(fixture("delivered-200.json"), HttpStatusCode.OK, jsonHeaders)
        }

        val error = assertFailsWith<CarrierError.NeedsMetadata> {
            adapter.track("534000021268935007", emptyMap())
        }

        assertEquals(listOf("postcode"), error.fields.map { it.name })
        assertTrue(seen.isEmpty(), "no HTTP call may happen without the required metadata")
    }

    @Test
    fun `postcode failing the validation regex throws NeedsMetadata`() = runTest {
        val adapter = adapter(handler = okFixture("delivered-200.json"))

        assertFailsWith<CarrierError.NeedsMetadata> {
            adapter.track("534000021268935007", mapOf("postcode" to "12"))
        }
    }

    @Test
    fun `missing metadata wins over an unknown number`() = runTest {
        val adapter = adapter {
            respond("", HttpStatusCode.NoContent, headersOf("x-remix-redirect" to listOf("/tracking?err=true")))
        }

        assertFailsWith<CarrierError.NeedsMetadata> {
            adapter.track("XX000000000000", emptyMap())
        }
    }

    @Test
    fun `lookup request targets the Remix data route with tracking number, postcode and _data param`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val adapter = adapter { request ->
            seen.add(request)
            respond(fixture("delivered-200.json"), HttpStatusCode.OK, jsonHeaders)
        }

        adapter.track(" 534000021268935007 ", mapOf("postcode" to "4250-004"))

        assertEquals(1, seen.size)
        val request = seen[0]
        assertEquals("/shipments/tracking/order", request.url.encodedPath)
        assertEquals("534000021268935007", request.url.parameters["tracking_number"])
        assertEquals("4250-004", request.url.parameters["postal_code"])
        assertEquals("routes/tracking.order", request.url.parameters["_data"])
        assertEquals(ContentType.Application.Json.toString(), request.headers[HttpHeaders.Accept])
    }

    @Test
    fun `failed-attempt event maps to EXCEPTION with the free-text reason resolved from its variables bindings`() = runTest {
        val body = """
            {
              "activeEvent": {
                "id": "SP031", "label": "absent", "event_type": "direct", "uniq_id": "u2",
                "timestamp": "2026-11-24T10:00:00.000Z", "timeline": true,
                "variables": {
                  "Header": [],
                  "Text": [
                    { "poe": "retailerName", "path": "retailer_name" },
                    { "poe": "failureReason", "path": "events.reason",
                      "condition": { "sourceField": "id", "operator": "eq", "value": "SP031" } },
                    { "poe": "time", "path": "events.timestamp", "field_type": "time",
                      "condition": { "sourceField": "id", "operator": "eq", "value": "SP031" } }
                  ]
                },
                "location": { "city": null, "country": "PT" }
              },
              "eventList": [],
              "triggeredEventList": [
                {
                  "id": "SP031", "label": "absent", "event_type": "direct", "uniq_id": "u2",
                  "timestamp": "2026-11-24T10:00:00.000Z", "timeline": true,
                  "variables": {
                    "Text": [
                      { "poe": "retailerName", "path": "retailer_name" },
                      { "poe": "failureReason", "path": "events.reason",
                        "condition": { "sourceField": "id", "operator": "eq", "value": "SP031" } },
                      { "poe": "time", "path": "events.timestamp", "field_type": "time",
                        "condition": { "sourceField": "id", "operator": "eq", "value": "SP031" } }
                    ]
                  },
                  "location": { "city": null, "country": "PT" }
                },
                {
                  "id": "SM001", "label": "manifested", "event_type": "direct", "uniq_id": "u1",
                  "timestamp": "2026-11-22T10:00:00.000Z", "timeline": true,
                  "variables": { "Text": [ { "poe": "retailerName", "path": "retailer_name" } ] },
                  "location": { "city": null, "country": "PT" }
                }
              ],
              "orderTrackData": {
                "service_type": "", "delivery_type": "direct",
                "expected_delivery_ts": { "start": "2026-11-24T08:00:00.000Z", "end": "2026-11-24T21:00:00.000Z" },
                "events": [
                  { "uniq_id": "u1", "id": "SM001", "timestamp": "2026-11-22T10:00:00.000Z", "location": { "city": null, "country": "PT" } },
                  { "uniq_id": "u2", "id": "SP031", "timestamp": "2026-11-24T10:00:00.000Z", "reason": "Customer was not at home", "location": { "city": null, "country": "PT" } }
                ]
              }
            }
        """.trimIndent()
        val adapter = adapter { respond(body, HttpStatusCode.OK, jsonHeaders) }

        val result = adapter.track("534000021268935007", mapOf("postcode" to "4250-004"))

        assertEquals(TrackingStatus.EXCEPTION, result.status)
        assertEquals(2, result.events.size)
        assertEquals("manifested", result.events.first().description)
        val failure = result.events.last()
        assertEquals(TrackingStatus.EXCEPTION, failure.status)
        assertEquals("absent: Customer was not at home", failure.description)
    }
}
