package com.postbox.carrier

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

class DpdAdapterTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val htmlHeaders = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    private fun adapter(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.statement.HttpResponse,
        seen: MutableList<HttpRequestData>? = null,
        withFirebase: Boolean = true,
    ): DpdAdapter {
        val engine: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.statement.HttpResponse =
            { request ->
                seen?.add(request)
                when {
                    request.url.toString().contains("firebaseinstallations.googleapis.com") -> respond(
                        """{"fid":"syntheticfid000000000001","authToken":{"token":"install-token","expiresIn":"604800s"}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                    request.url.toString().contains("firebaseremoteconfig.googleapis.com") -> respond(
                        """{"entries":{"basic_dpd_token":"QkFTSUNfVE9LRU4="}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                    request.url.toString().contains("/oauth/token") -> respond(
                        """{"access_token":"guest-token","token_type":"Bearer","expires_in":3600}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                    else -> handler(request)
                }
            }
        return DpdAdapter(
            client = HttpClient(MockEngine(engine)),
            rateLimiter = CarrierRateLimiter(minInterval = 1.seconds),
            ptBaseUrl = "https://tracking.dpd.test",
            groupBaseUrl = "https://dpdgroup.test",
            firebaseApiKey = if (withFirebase) "test-firebase-key" else null,
            firebaseAndroidCert = if (withFirebase) "AA:BB:CC:DD" else null,
        )
    }

    private fun ptAdapter(body: String, status: HttpStatusCode = HttpStatusCode.OK, seen: MutableList<HttpRequestData>? = null) =
        adapter({ respond(body, status, htmlHeaders) }, seen = seen)

    private fun nlAdapter(
        details: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.statement.HttpResponse,
        seen: MutableList<HttpRequestData>? = null,
        withFirebase: Boolean = true,
    ) = adapter(details, seen = seen, withFirebase = withFirebase)

    private fun nlDetails(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.statement.HttpResponse =
        { respond(body, status, jsonHeaders) }

    @Test
    fun `PT in-transit fixture returns the full timeline with mapped codes, glosses and wall-clock times`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val adapter = ptAdapter(fixture("dpd/pt-in-transit.html"), seen = seen)

        val result = adapter.track("09550000000101", emptyMap())

        assertEquals("https://tracking.dpd.test/pt?reference=09550000000101", seen.single().url.toString())
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, result.status)
        assertEquals(4, result.events.size)
        assertEquals(
            listOf(
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.OUT_FOR_DELIVERY,
            ),
            result.events.map { it.status },
            "events must be chronological regardless of the page's newest-first row order",
        )
        val lisbon = ZoneId.of("Europe/Lisbon")
        assertEquals(LocalDateTime.parse("2026-11-12T16:31").atZone(lisbon).toInstant(), result.events[0].occurredAt)
        assertEquals(LocalDateTime.parse("2026-11-14T08:37").atZone(lisbon).toInstant(), result.events.last().occurredAt)
        assertEquals("ENTRADA EM ARMAZÉM (depot inbound) — 999", result.events[0].description)
        assertEquals("PASSAGEM POR HUB (hub transit) — NL", result.events[1].description)
        assertEquals("EM DISTRIBUIÇÃO (out for delivery)", result.events.last().description)
        assertNull(result.eta)
    }

    @Test
    fun `PT delivered fixture maps POD to DELIVERED and keeps the signatory comment in the description`() = runTest {
        val result = ptAdapter(fixture("dpd/pt-delivered.html")).track("09550000000102", emptyMap())

        assertEquals(TrackingStatus.DELIVERED, result.status)
        assertEquals(TrackingStatus.DELIVERED, result.events.last().status)
        assertTrue(
            result.events.last().description.contains("ENTREGUE") &&
                result.events.last().description.contains("[SIGNATORY NAME REDACTED]"),
            "original wording and POD comment must stay in the description: ${result.events.last().description}",
        )
    }

    @Test
    fun `PT failed-attempt fixture is EXCEPTION from the current status even with a later depot scan`() = runTest {
        val result = ptAdapter(fixture("dpd/pt-failed-attempt.html")).track("09550000000103", emptyMap())

        assertEquals(TrackingStatus.EXCEPTION, result.status)
        assertEquals(
            listOf(
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.OUT_FOR_DELIVERY,
                TrackingStatus.EXCEPTION,
                TrackingStatus.IN_TRANSIT,
            ),
            result.events.map { it.status },
        )
        assertEquals(
            "DESTINATÁRIO AUSENTE (failed attempt: recipient absent)",
            result.events.single { it.status == TrackingStatus.EXCEPTION }.description,
        )
    }

    @Test
    fun `PT unknown-number fixture maps to NotFound carrying the number`() = runTest {
        val error = assertFailsWith<CarrierError.NotFound> {
            ptAdapter(fixture("dpd/pt-unknown-number.html")).track("09990000000404", emptyMap())
        }

        assertEquals("09990000000404", error.number)
    }

    @Test
    fun `PT 404 is indeterminate and degrades to CarrierUnavailable, not NotFound`() = runTest {
        assertFailsWith<CarrierError.CarrierUnavailable> {
            ptAdapter("<html><body>not found</body></html>", status = HttpStatusCode.NotFound)
                .track("09550000000101", emptyMap())
        }
    }

    @Test
    fun `PT bot-check page without the reference label degrades to CarrierUnavailable`() = runTest {
        assertFailsWith<CarrierError.CarrierUnavailable> {
            ptAdapter("<html><body><h1>Just a moment...</h1></body></html>").track("09550000000101", emptyMap())
        }
    }

    @Test
    fun `PT known number with an empty history table is PENDING, distinct from NotFound`() = runTest {
        val page = """
            <html><body>
            <div class="reference-label"><span>Nº de encomenda: 09550000000105</span></div>
            <div class="table-responsive hide"><table class="table"><thead><tr><th>Código</th></tr></thead><tbody></tbody></table></div>
            </body></html>
        """.trimIndent()

        val result = ptAdapter(page).track("09550000000105", emptyMap())

        assertEquals(TrackingStatus.PENDING, result.status)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `PT page with a current status but no rows still yields one event`() = runTest {
        val page = """
            <html><body>
            <div class="reference-label"><span>Nº de encomenda: 09550000000106</span></div>
            <div class="current-status "><span class="current-description">EM DISTRIBUIÇÃO</span><span class="current-date">14-11-2026 08:37</span></div>
            <div class="table-responsive hide"><table class="table"><tbody></tbody></table></div>
            </body></html>
        """.trimIndent()

        val result = ptAdapter(page).track("09550000000106", emptyMap())

        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, result.status)
        assertEquals(1, result.events.size)
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, result.events.single().status)
    }

    @Test
    fun `a 15th check character is stripped for the lookup but kept on NotFound`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val error = assertFailsWith<CarrierError.NotFound> {
            ptAdapter(fixture("dpd/pt-unknown-number.html"), seen = seen).track("09990000000404X", emptyMap())
        }

        assertEquals("09990000000404X", error.number)
        assertEquals("https://tracking.dpd.test/pt?reference=09990000000404", seen.single().url.toString())
    }

    @Test
    fun `country metadata defaults to PT and rejects unsupported values with NeedsMetadata before any HTTP call`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        ptAdapter(fixture("dpd/pt-in-transit.html"), seen = seen).track("09550000000101", emptyMap())
        assertEquals("tracking.dpd.test", seen.single().url.host)

        val seenInvalid = mutableListOf<HttpRequestData>()
        val error = assertFailsWith<CarrierError.NeedsMetadata> {
            ptAdapter(fixture("dpd/pt-in-transit.html"), seen = seenInvalid).track("09550000000101", mapOf("country" to "DE"))
        }
        assertEquals(listOf("country"), error.fields.map { it.name })
        assertTrue(seenInvalid.isEmpty(), "an invalid country must not trigger HTTP calls")
    }

    @Test
    fun `NL guest API walks the token chain and claims the parcel with businessUnit DPD-NL`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val adapter = nlAdapter(nlDetails(fixture("dpd/nl-in-transit-200.json")), seen = seen)

        val result = adapter.track("05220000000202", mapOf("country" to "NL"))

        assertEquals(TrackingStatus.IN_TRANSIT, result.status)
        assertEquals("HOME", result.carrierService)
        assertEquals(
            listOf(
                TrackingStatus.PENDING,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
            ),
            result.events.map { it.status },
        )
        assertEquals("ORDER_CREATED", result.events.first().description)
        assertEquals(
            LocalDateTime.parse("2026-11-12T16:31:00").atZone(ZoneId.of("+01:00")).toInstant(),
            result.events.first().occurredAt,
        )
        val requests = seen.map { it.url.toString() }
        assertTrue(requests.any { it.contains("firebaseinstallations.googleapis.com") })
        assertTrue(requests.any { it.contains("firebaseremoteconfig.googleapis.com") })
        assertTrue(requests.any { it.contains("/oauth/token") })
        val details = seen.single { it.url.toString().contains("/parcels/details/") }
        assertEquals("DPD-NL", details.url.parameters["businessUnit"])
        assertEquals("true", details.url.parameters["continueWithoutVerification"])
        assertEquals("Bearer guest-token", details.headers[HttpHeaders.Authorization])
        assertEquals("test-firebase-key", seen.first().headers["X-Goog-Api-Key"])
        assertEquals("Basic QkFTSUNfVE9LRU4=", seen.single { it.url.toString().contains("/oauth/token") }.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `NL verified fixture prefers the scan events over the milestone history`() = runTest {
        val result = nlAdapter(nlDetails(fixture("dpd/nl-delivered-200-verified.json")))
            .track("05220000000203", mapOf("country" to "NL"))

        assertEquals(TrackingStatus.DELIVERED, result.status)
        assertEquals(5, result.events.size)
        assertEquals(
            listOf(
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.OUT_FOR_DELIVERY,
                TrackingStatus.DELIVERED,
                TrackingStatus.DELIVERED,
            ),
            result.events.map { it.status },
        )
        val amsterdam = ZoneId.of("Europe/Amsterdam")
        assertEquals(LocalDateTime.parse("2026-11-12T18:05:12").atZone(amsterdam).toInstant(), result.events.first().occurredAt)
        assertEquals("Your parcel has been delivered successfully", result.events.last().description)
        assertEquals("Utrecht, NL", result.events.last().location)
        assertEquals("We received the proof of delivery", result.events[3].description)
        assertNull(result.events[3].location, "UNDEFINED country carries no place")
    }

    @Test
    fun `NL exception fixture maps UNSUCCESSFUL_DELIVERY_ATTEMPT to EXCEPTION`() = runTest {
        val result = nlAdapter(nlDetails(fixture("dpd/nl-exception-200.json")))
            .track("05220000000204", mapOf("country" to "NL"))

        assertEquals(TrackingStatus.EXCEPTION, result.status)
        assertEquals(
            listOf(
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.IN_TRANSIT,
                TrackingStatus.OUT_FOR_DELIVERY,
                TrackingStatus.EXCEPTION,
            ),
            result.events.map { it.status },
        )
    }

    @Test
    fun `NL unknown number answers 400 PARCEL_NOT_FOUND or plain 404, both NotFound`() = runTest {
        val notFound400 = assertFailsWith<CarrierError.NotFound> {
            nlAdapter(nlDetails(fixture("dpd/nl-unknown-400-parcel-not-found.json"), HttpStatusCode.BadRequest))
                .track("05229999999999", mapOf("country" to "NL"))
        }
        assertEquals("05229999999999", notFound400.number)

        assertFailsWith<CarrierError.NotFound> {
            nlAdapter(nlDetails("", HttpStatusCode.NotFound)).track("05229999999999", mapOf("country" to "NL"))
        }
    }

    @Test
    fun `NL guest API is group-wide - a parcel whose countryCode is not NL is not claimed`() = runTest {
        val body = fixture("dpd/nl-in-transit-200.json").replaceFirst(""""countryCode": "NL"""", """"countryCode": "DE"""")

        assertFailsWith<CarrierError.NotFound> {
            nlAdapter(nlDetails(body)).track("05220000000202", mapOf("country" to "NL"))
        }
    }

    @Test
    fun `NL 400 with another exceptionType is inconclusive and degrades to CarrierUnavailable`() = runTest {
        assertFailsWith<CarrierError.CarrierUnavailable> {
            nlAdapter(nlDetails("""{"exceptionType":"VALIDATION_ERROR","message":"bad"}""", HttpStatusCode.BadRequest))
                .track("05220000000202", mapOf("country" to "NL"))
        }
    }

    @Test
    fun `NL 429 maps to RateLimited with the retry-after value`() = runTest {
        val error = assertFailsWith<CarrierError.RateLimited> {
            nlAdapter({ respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30")) })
                .track("05220000000202", mapOf("country" to "NL"))
        }

        assertEquals(30.seconds, error.retryAfter)
    }

    @Test
    fun `NL 503 degrades to CarrierUnavailable`() = runTest {
        assertFailsWith<CarrierError.CarrierUnavailable> {
            nlAdapter(nlDetails("<html>down</html>", HttpStatusCode.ServiceUnavailable))
                .track("05220000000202", mapOf("country" to "NL"))
        }
    }

    @Test
    fun `NL 401 refreshes the guest token once and retries the details call`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        var detailsCalls = 0
        val adapter = nlAdapter({ request ->
            detailsCalls++
            if (detailsCalls == 1) {
                respond("", HttpStatusCode.Unauthorized, jsonHeaders)
            } else {
                respond(fixture("dpd/nl-in-transit-200.json"), HttpStatusCode.OK, jsonHeaders)
            }
        }, seen = seen)

        val result = adapter.track("05220000000202", mapOf("country" to "NL"))

        assertEquals(TrackingStatus.IN_TRANSIT, result.status)
        assertEquals(2, detailsCalls)
        assertEquals(2, seen.count { it.url.toString().contains("/oauth/token") })
    }

    @Test
    fun `NL without the firebase app values degrades to CarrierUnavailable without any HTTP call`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        assertFailsWith<CarrierError.CarrierUnavailable> {
            nlAdapter(nlDetails(fixture("dpd/nl-in-transit-200.json")), seen = seen, withFirebase = false)
                .track("05220000000202", mapOf("country" to "NL"))
        }
        assertTrue(seen.isEmpty())
    }
}
