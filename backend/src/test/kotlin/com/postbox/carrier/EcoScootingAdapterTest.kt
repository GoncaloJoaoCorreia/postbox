package com.postbox.carrier

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

class EcoScootingAdapterTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, jsonHeaders)

    private fun adapter(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        seen: MutableList<HttpRequestData>? = null,
        locale: String = EcoScootingAdapter.DEFAULT_LOCALE,
    ) = EcoScootingAdapter(
        client = HttpClient(MockEngine { request ->
            seen?.add(request)
            respond(body, status, jsonHeaders)
        }),
        rateLimiter = CarrierRateLimiter(minInterval = 1.seconds),
        baseUrl = "https://linkdo.test/gateway/link.do",
        locale = locale,
    )

    @Test
    fun `captured out-for-delivery fixture returns the full timeline with mapped statuses and verbatim text`() = runTest {
        val adapter = adapter(fixture("linkdo-out-for-delivery-CNPRT42500041234024382386-en_US.json"))

        val result = adapter.track("CNPRT42500041234024382386", emptyMap())

        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, result.status)
        assertEquals(6, result.events.size)
        assertEquals(
            listOf(
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.OUT_FOR_DELIVERY,
            ),
            result.events.map { it.status },
            "events must be chronological regardless of response order",
        )
        val times = result.events.map { it.occurredAt }
        assertEquals(times.sorted(), times)
        assertEquals(Instant.ofEpochMilli(1795341743000), result.events.first().occurredAt)
        assertEquals("Your shipment has arrived at sorting center", result.events.first().description)
        assertEquals("San Fernando de Henares", result.events.first().location)
        assertEquals("Your shipment is out for delivery", result.events.last().description)
        assertEquals("PT", result.events.last().location)
        assertEquals("PES012", result.carrierService)
        assertNull(result.eta)
    }

    @Test
    fun `delivered fixture with a failed attempt keeps the reason verbatim and maps it to EXCEPTION`() = runTest {
        val adapter = adapter(fixture("linkdo-delivered-with-failed-attempt-088400000108352403-en_US.json"))

        val result = adapter.track("088400000108352403", emptyMap())

        assertEquals(TrackingStatus.DELIVERED, result.status)
        assertEquals(8, result.events.size)
        assertEquals(
            listOf(
                TrackingStatus.PENDING,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.OUT_FOR_DELIVERY,
                TrackingStatus.EXCEPTION,
                TrackingStatus.OUT_FOR_DELIVERY,
                TrackingStatus.DELIVERED,
            ),
            result.events.map { it.status },
        )
        val failure = result.events.single { it.status == TrackingStatus.EXCEPTION }
        assertTrue(
            failure.description.contains("[Parcel cannot be delivered outside business hours]"),
            "the original failure reason must stay in the description: ${failure.description}",
        )
        assertEquals("Barcelona", failure.location)
        assertEquals(TrackingStatus.DELIVERED, result.events.last().status)
        assertEquals("Viladecans", result.events.last().location)
    }

    @Test
    fun `es_ES locale returns the verbatim Spanish text`() = runTest {
        val adapter = adapter(
            fixture("linkdo-out-for-delivery-CNPRT42500041234024382386-es_ES.json"),
            locale = "es_ES",
        )

        val result = adapter.track("CNPRT42500041234024382386", emptyMap())

        assertEquals("Su paquete ha llegado al centro de clasificación", result.events.first().description)
        assertEquals("Su envío está en reparto", result.events.last().description)
    }

    @Test
    fun `captured unknown-number fixture maps to NotFound carrying the number`() = runTest {
        val adapter = adapter(fixture("linkdo-unknown-number-B00604.json"))

        val error = assertFailsWith<CarrierError.NotFound> {
            adapter.track("CNPRT99999999999999999999", emptyMap())
        }

        assertEquals("CNPRT99999999999999999999", error.number)
    }

    @Test
    fun `pre-advised number with only CREATE_ORDER_CES is PENDING, not NotFound`() = runTest {
        val adapter = adapter(
            """
            { "success": "true", "solutionParam": { "solutionCode": "PES012" },
              "statuses": [ { "datetime": "2026-11-20 10:00:00 UTC+1", "opTimestamp": "1795311600000",
                "statusName": "create", "description": "Shipment order created",
                "actionCode": "CREATE_ORDER_CES", "opCode": "0", "location": "ES" } ] }
            """.trimIndent()
        )

        val result = adapter.track("CNPRT42500041234024382386", emptyMap())

        assertEquals(TrackingStatus.PENDING, result.status)
        assertEquals(1, result.events.size)
        assertEquals(TrackingStatus.PENDING, result.events[0].status)
        assertEquals("Shipment order created", result.events[0].description)
    }

    @Test
    fun `datetime without opTimestamp parses with its UTC offset`() = runTest {
        val adapter = adapter(
            """
            { "success": "true", "statuses": [
              { "datetime": "2026-11-23 08:40:51 UTC+2", "statusName": "Out for Delivery",
                "description": "out", "actionCode": "LM_DELIVERY_DEPART", "location": "PT" },
              { "datetime": "2026-11-23 08:40:51 UTC+0", "statusName": "Out for Delivery",
                "description": "out", "actionCode": "LM_DELIVERY_DEPART", "location": "PT" } ] }
            """.trimIndent()
        )

        val result = adapter.track("CNPRT42500041234024382386", emptyMap())

        assertEquals(2, result.events.size)
        assertEquals(
            LocalDateTime.parse("2026-11-23T08:40:51").toInstant(ZoneOffset.ofHours(2)),
            result.events[0].occurredAt,
        )
        assertEquals(Instant.ofEpochMilli(1795423251000), result.events[1].occurredAt)
    }

    @Test
    fun `system error B00502 degrades to CarrierUnavailable`() = runTest {
        val adapter = adapter(fixture("linkdo-system-error-B00502.json"))

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("CNPRT42500041234024382386", emptyMap())
        }
    }

    @Test
    fun `XML S12 error body degrades to CarrierUnavailable`() = runTest {
        val adapter = adapter(fixture("linkdo-invalid-params-S12.xml"))

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("CNPRT42500041234024382386", emptyMap())
        }
    }

    @Test
    fun `non-success HTTP status degrades to CarrierUnavailable`() = runTest {
        val adapter = adapter("<html>502 Bad Gateway</html>", status = HttpStatusCode.BadGateway)

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("CNPRT42500041234024382386", emptyMap())
        }
    }

    @Test
    fun `unparseable body degrades to CarrierUnavailable instead of crashing`() = runTest {
        val adapter = adapter("gateway timeout")

        assertFailsWith<CarrierError.CarrierUnavailable> {
            adapter.track("CNPRT42500041234024382386", emptyMap())
        }
    }

    @Test
    fun `request posts the link gateway form with the number verbatim and the configured locale`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val adapter = adapter(
            fixture("linkdo-out-for-delivery-CNPRT42500041234024382386-es_ES.json"),
            seen = seen,
            locale = "es_ES",
        )

        adapter.track(" cnprt42500041234024382386 ", emptyMap())

        assertEquals(1, seen.size)
        val request = seen[0]
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://linkdo.test/gateway/link.do", request.url.toString())
        val body = request.body as TextContent
        assertTrue(
            body.contentType.toString().startsWith("application/x-www-form-urlencoded"),
            "content type must be form-urlencoded: ${body.contentType}",
        )
        val params = body.text.split("&").associate { pair ->
            val idx = pair.indexOf('=')
            URLDecoder.decode(pair.substring(0, idx), Charsets.UTF_8) to
                URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8)
        }
        assertEquals(
            """{"mailNo":"cnprt42500041234024382386","locale":"es_ES","role":"endUser"}""",
            params["logistics_interface"],
            "the lookup is case-sensitive: the number must be sent trimmed but verbatim",
        )
        assertEquals("CN_OVERSEA_LOGISTICS_INQUIRY_TRACKING", params["msg_type"])
        assertEquals("DISTRIBUTOR_30250031", params["logistic_provider_id"])
        assertEquals("suibianxie", params["data_digest"])
        assertEquals("CNL_EU", params["to_code"])
    }
}
