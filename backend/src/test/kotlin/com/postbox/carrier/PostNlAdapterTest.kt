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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

class PostNlAdapterTest {

    private val jsonHeaders = headersOf(
        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
    )

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/postnl/$name")) { "missing fixture $name" }.readText()

    private fun adapter(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = PostNlAdapter(
        client = HttpClient(MockEngine(handler)),
        rateLimiter = CarrierRateLimiter(minInterval = 1.seconds),
        baseUrl = "https://postnl.test",
    )

    private fun okFixture(name: String): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        { respond(fixture(name), HttpStatusCode.OK, jsonHeaders) }

    @Test
    fun `captured delivered fixture returns the full timeline ending in DELIVERED`() = runTest {
        val adapter = adapter(handler = okFixture("delivered-3s-pt-200.json"))

        val result = adapter.track("3Sxxxx00000000", mapOf("postcode" to "4250-004"))

        assertEquals(TrackingStatus.DELIVERED, result.status)
        assertEquals(12, result.events.size)
        assertEquals(
            listOf(
                TrackingStatus.PENDING,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.EXCEPTION,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.OUT_FOR_DELIVERY,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.OUT_FOR_DELIVERY,
                TrackingStatus.DELIVERED,
            ),
            result.events.map { it.status },
        )
        val times = result.events.map { it.occurredAt }
        assertEquals(times.sorted(), times, "events must be chronological regardless of response order")
        assertEquals("Shipment expected, but not yet arrived or processed at PostNL", result.events.first().description)
        val last = result.events.last()
        assertEquals(Instant.parse("2026-11-17T10:36:00Z"), last.occurredAt)
        assertEquals("Yes! Your shipment has been delivered to the recipient", last.description)
        assertEquals(Instant.parse("2026-11-18T22:59:59Z"), result.eta)
        assertEquals("Parcel", result.carrierService)
        val ignoredTexts = listOf(
            "Pre-alerted shipment enriched by PostNL management.",
            "Delivery cannot be changed",
            "PIOS notice",
            "Parcel linked to packaging tool",
        )
        assertFalse(result.events.any { it.description in ignoredTexts }, "notice-only codes must not become events")
    }

    @Test
    fun `captured pre-advised fixture is PENDING with its single A01 event, not NotFound`() = runTest {
        val adapter = adapter(handler = okFixture("pre-advised-200.json"))

        val result = adapter.track("3SXXXX00000000", mapOf("postcode" to "1234AB"))

        assertEquals(TrackingStatus.PENDING, result.status)
        assertEquals(1, result.events.size)
        assertEquals(TrackingStatus.PENDING, result.events.single().status)
        assertEquals("Shipment expected, but not yet arrived or processed at PostNL", result.events.single().description)
    }

    @Test
    fun `unknown number answered with an empty colli maps to NotFound`() = runTest {
        val adapter = adapter(handler = okFixture("unknown-number-200-empty-colli.json"))

        val error = assertFailsWith<CarrierError.NotFound> {
            adapter.track("3SXXXX00000000", mapOf("postcode" to "1234AB"))
        }

        assertEquals("3SXXXX00000000", error.number)
    }

    @Test
    fun `known number with a wrong postcode answers the same empty colli and maps to NotFound`() = runTest {
        val adapter = adapter(handler = okFixture("wrong-postcode-200-empty-colli.json"))

        assertFailsWith<CarrierError.NotFound> {
            adapter.track("3SXXXX00000000", mapOf("postcode" to "1234AB"))
        }
    }

    @Test
    fun `missing postcode metadata throws NeedsMetadata before any HTTP call`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val adapter = adapter { request ->
            seen.add(request)
            respond(fixture("delivered-3s-pt-200.json"), HttpStatusCode.OK, jsonHeaders)
        }

        val error = assertFailsWith<CarrierError.NeedsMetadata> {
            adapter.track("3SXXXX00000000", emptyMap())
        }

        assertEquals(listOf("postcode"), error.fields.map { it.name })
        assertTrue(seen.isEmpty(), "no HTTP call may happen without the required metadata")
    }

    @Test
    fun `postcode failing the validation regex throws NeedsMetadata`() = runTest {
        val adapter = adapter(handler = okFixture("delivered-3s-pt-200.json"))

        assertFailsWith<CarrierError.NeedsMetadata> {
            adapter.track("3SXXXX00000000", mapOf("postcode" to "12"))
        }
        assertFailsWith<CarrierError.NeedsMetadata> {
            adapter.track("3SXXXX00000000", mapOf("postcode" to "12345"))
        }
    }

    @Test
    fun `lookup request targets the trackAndTrace path key with language and Accept headers`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val adapter = adapter { request ->
            seen.add(request)
            respond(fixture("delivered-3s-pt-200.json"), HttpStatusCode.OK, jsonHeaders)
        }

        adapter.track(" 3sxxxx00000000 ", mapOf("postcode" to "4250 004"))

        assertEquals(1, seen.size)
        val request = seen[0]
        assertEquals("/track-and-trace/api/trackAndTrace/3SXXXX00000000-PT-4250004", request.url.encodedPath)
        assertEquals("en", request.url.parameters["language"])
        assertEquals(ContentType.Application.Json.toString(), request.headers[HttpHeaders.Accept])
    }

    @Test
    fun `delivery country is derived from the postcode shape`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val adapter = adapter { request ->
            seen.add(request)
            respond(fixture("pre-advised-200.json"), HttpStatusCode.OK, jsonHeaders)
        }

        suspend fun keyFor(postcode: String): String {
            seen.clear()
            adapter.track("3SXXXX00000000", mapOf("postcode" to postcode))
            return seen.single().url.encodedPath.substringAfterLast('/')
        }

        assertEquals("3SXXXX00000000-NL-1234AB", keyFor("1234ab"))
        assertEquals("3SXXXX00000000-PT-4250-004", keyFor("4250-004"))
        assertEquals("3SXXXX00000000-BE-1234", keyFor("1234"))
        assertEquals("3SXXXX00000000-LU-L-1234", keyFor("L-1234"))
    }

    @Test
    fun `Akamai 403 degrades to CarrierUnavailable`() = runTest {
        val adapter = adapter {
            respond("<html>Access Denied</html>", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType to listOf(ContentType.Text.Html.toString())))
        }

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("3SXXXX00000000", mapOf("postcode" to "4250-004"))
        }
    }

    @Test
    fun `429 with Retry-After degrades to CarrierUnavailable with a logged sample instead of a harder retry`() = runTest {
        val adapter = adapter {
            respond("Too Many Requests", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter to listOf("30")))
        }

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("3SXXXX00000000", mapOf("postcode" to "4250-004"))
        }
    }

    @Test
    fun `unparseable 200 body degrades to CarrierUnavailable instead of crashing`() = runTest {
        val adapter = adapter {
            respond("<html><body>gateway error</body></html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf(ContentType.Text.Html.toString())))
        }

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("3SXXXX00000000", mapOf("postcode" to "4250-004"))
        }
    }

    @Test
    fun `200 body without a colli object degrades to CarrierUnavailable`() = runTest {
        val adapter = adapter {
            respond("""{"processed":"2026-11-27T01:11:13+01:00"}""", HttpStatusCode.OK, jsonHeaders)
        }

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("3SXXXX00000000", mapOf("postcode" to "4250-004"))
        }
    }

    @Test
    fun `transport failure degrades to CarrierUnavailable`() = runTest {
        val adapter = adapter {
            throw java.net.ConnectException("connection refused")
        }

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("3SXXXX00000000", mapOf("postcode" to "4250-004"))
        }
    }
}
