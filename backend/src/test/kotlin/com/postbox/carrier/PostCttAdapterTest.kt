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
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class PostCttAdapterTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun fixtureResponse(name: String): String {
        val text = requireNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()
        return Json.decodeFromString<JsonObject>(text)["response"]!!.toString()
    }

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, jsonHeaders)

    private fun adapter(
        detailBody: String,
        detailStatus: HttpStatusCode = HttpStatusCode.OK,
        seen: MutableList<HttpRequestData>? = null,
    ) = PostCttAdapter(
        client = HttpClient(MockEngine { request ->
            seen?.add(request)
            if (request.url.encodedPath.contains("moduleversioninfo")) {
                ok("""{"versionToken":"modver-1"}""")
            } else {
                respond(detailBody, detailStatus, jsonHeaders)
            }
        }),
        rateLimiter = CarrierRateLimiter(minInterval = 1.seconds),
        baseUrl = "https://ctt.test/CustomerArea",
    )

    @Test
    fun `captured delivered fixture returns the full event history with mapped statuses and verbatim PT text`() = runTest {
        val adapter = adapter(fixtureResponse("ctt-detail-delivered-DD506882333PT.json"))

        val result = adapter.track("DD506882333PT", emptyMap())

        assertEquals(TrackingStatus.DELIVERED, result.status)
        assertEquals(6, result.events.size)
        assertEquals(
            listOf(
                TrackingStatus.PENDING,
                TrackingStatus.PENDING,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.OUT_FOR_DELIVERY,
                TrackingStatus.DELIVERED,
            ),
            result.events.map { it.status },
        )
        assertEquals(
            listOf(
                Instant.parse("2026-11-20T18:49:34Z"),
                Instant.parse("2026-11-20T20:11:32Z"),
                Instant.parse("2026-11-20T20:11:33Z"),
                Instant.parse("2026-11-21T05:01:56Z"),
                Instant.parse("2026-11-21T10:03:48Z"),
                Instant.parse("2026-11-21T10:40:46Z"),
            ),
            result.events.map { it.occurredAt },
            "events must be chronological regardless of response order",
        )
        assertEquals("A informação sobre o envio foi recebida.", result.events[0].description)
        assertEquals("O envio foi entregue. O processo de envio terminou.", result.events[5].description)
        assertNull(result.events[0].location)
        assertEquals("8811373 - (CO) C.O. RIO DE MOURO", result.events[2].location)
        assertEquals("Centro operacional Porto", result.events[5].location)
        assertNull(result.eta)
        assertNull(result.carrierService)
    }

    @Test
    fun `captured unknown well-formed fixture maps to NotFound carrying the number`() = runTest {
        val adapter = adapter(fixtureResponse("ctt-detail-unknown-RR001000007PT.json"))

        val error = assertFailsWith<CarrierError.NotFound> {
            adapter.track("RR001000007PT", emptyMap())
        }

        assertEquals("RR001000007PT", error.number)
    }

    @Test
    fun `detail request carries the CSRF header, JSON content type, fetched module version and normalized uppercase code`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val adapter = adapter(fixtureResponse("ctt-detail-delivered-DD506882333PT.json"), seen = seen)

        adapter.track(" dd506882333pt ", emptyMap())

        assertEquals(2, seen.size)
        val moduleVersionRequest = seen[0]
        assertTrue(moduleVersionRequest.url.encodedPath.contains("moduleservices/moduleversioninfo"))
        assertEquals("T6C+9iB49TLra4jEsMeSckDMNhQ=", moduleVersionRequest.headers["X-CSRFToken"])
        val detailRequest = seen[1]
        assertTrue(detailRequest.url.encodedPath.endsWith("DataActionGetObjectEventsByInputObjectCode"))
        assertEquals("T6C+9iB49TLra4jEsMeSckDMNhQ=", detailRequest.headers["X-CSRFToken"])
        assertEquals(ContentType.Application.Json.toString(), detailRequest.headers[HttpHeaders.Accept])
        val body = detailRequest.body as TextContent
        assertTrue(body.contentType.toString().startsWith("application/json"))
        assertTrue(body.text.contains("\"ObjectCodeInput\":\"DD506882333PT\""), "code must be trimmed and uppercased: ${body.text}")
        assertTrue(body.text.contains("\"moduleVersion\":\"modver-1\""))
        assertTrue(body.text.contains("\"viewName\":\"CustomerArea.PublicArea_Detail\""))
    }

    @Test
    fun `server exception body degrades to CarrierUnavailable`() = runTest {
        val adapter = adapter("""{"data":{},"exception":{"name":"ServerException","message":"No role validation found"}}""")

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("DD506882333PT", emptyMap())
        }
    }

    @Test
    fun `non-success HTTP status degrades to CarrierUnavailable`() = runTest {
        val adapter = adapter("<html>403 Error 1010</html>", detailStatus = HttpStatusCode.Forbidden)

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("DD506882333PT", emptyMap())
        }
    }

    @Test
    fun `unparseable body degrades to CarrierUnavailable instead of crashing`() = runTest {
        val adapter = adapter("<html><body>Cloudflare</body></html>")

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("DD506882333PT", emptyMap())
        }
    }

    @Test
    fun `Result Success=false degrades to CarrierUnavailable`() = runTest {
        val adapter = adapter(
            """{"data":{"Result":{"Success":false,"ErrorMessage":"Erro"},"ObjectEventsFromQuery":null}}"""
        )

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("DD506882333PT", emptyMap())
        }
    }

    @Test
    fun `isIpLocked maps to RateLimited`() = runTest {
        val adapter = adapter(
            """{"data":{"Result":{"isIpLocked":true,"Result":{"Success":true,"ErrorMessage":""}}}}"""
        )

        assertFailsWith<CarrierError.RateLimited> {
            adapter.track("DD506882333PT", emptyMap())
        }
    }

    @Test
    fun `pre-advised object with only the EMO event is PENDING, not NotFound`() = runTest {
        val adapter = adapter(
            """
            { "data": { "Result": {"Success": true, "ErrorMessage": ""},
                "ObjectEventsFromQuery": { "ObjectCode": "DD506882333PT", "Found": true,
                  "ShipmentProduct": "",
                  "DeliveryDeadlineEnd": "1900-01-01T00:00:00",
                  "Events": { "List": [ {
                    "DateTime": "2026-11-20T18:49:34Z", "State": "Aguarda entrada nos CTT", "StateId": 1,
                    "Event": "A informação sobre o envio foi recebida.", "EventCode": "EMO", "Local": "" } ] } } } }
            """.trimIndent()
        )

        val result = adapter.track("DD506882333PT", emptyMap())

        assertEquals(TrackingStatus.PENDING, result.status)
        assertEquals(1, result.events.size)
        assertEquals(TrackingStatus.PENDING, result.events[0].status)
        assertEquals("A informação sobre o envio foi recebida.", result.events[0].description)
    }
}
