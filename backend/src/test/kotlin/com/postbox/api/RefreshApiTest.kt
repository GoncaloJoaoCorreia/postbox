package com.postbox.api

import com.postbox.AppConfig
import com.postbox.CarrierSettings
import com.postbox.carrier.CarrierAdapter
import com.postbox.carrier.CarrierError
import com.postbox.carrier.CarrierHttpClients
import com.postbox.carrier.CarrierId
import com.postbox.carrier.FakeCarrier
import com.postbox.carrier.MetadataField
import com.postbox.carrier.TrackingResult
import com.postbox.db.ParcelRepository
import com.postbox.db.UserRepository
import com.postbox.db.closeDatabase
import com.postbox.db.freshTestDatabase
import com.postbox.db.isoUtc
import com.postbox.module
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database

class RefreshApiTest {

    private class RecordingAdapter(
        override val carrier: CarrierId,
        private val delegate: CarrierAdapter,
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf()),
    ) : CarrierAdapter {
        override val requiredMetadata: Map<String, MetadataField> = delegate.requiredMetadata

        override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult {
            calls += number
            return delegate.track(number, metadata)
        }
    }

    private class FailingAdapter(override val carrier: CarrierId) : CarrierAdapter {
        override val requiredMetadata: Map<String, MetadataField> = emptyMap()

        override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult =
            throw CarrierError.CarrierUnavailable(RuntimeException("connection refused"))
    }

    private class HangingAdapter(override val carrier: CarrierId) : CarrierAdapter {
        override val requiredMetadata: Map<String, MetadataField> = emptyMap()

        override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult {
            delay(60.seconds)
            return FakeCarrier.deterministicResult(number)
        }
    }

    private fun carrierSettings(requestTimeoutMs: Long) = CarrierSettings(
        connectTimeoutMs = 1000,
        socketTimeoutMs = 1000,
        requestTimeoutMs = requestTimeoutMs,
        userAgent = "postbox-test/1.0",
        minRequestIntervalMs = 1,
    )

    private fun testConfig() = AppConfig(
        host = "127.0.0.1",
        port = 0, // testApplication runs in-process and does not bind a real port
        jwtSecret = "test-secret",
        sqlitePath = "build/tmp/test/postbox.db",
        dataDir = "build/tmp/test-data",
        carriers = mapOf(
            CarrierId.DHL to carrierSettings(2000),
            CarrierId.CTT to carrierSettings(2000),
            CarrierId.PAACK to carrierSettings(50),
        ),
    )

    private fun withApp(
        name: String,
        adapters: Map<CarrierId, CarrierAdapter>,
        block: suspend ApplicationTestBuilder.(Database) -> Unit,
    ) = testApplication {
        val db = freshTestDatabase(name)
        try {
            application {
                dependencies {
                    adapters.forEach { (carrier, adapter) ->
                        provide<CarrierAdapter>(name = carrier.id) { adapter }
                    }
                }
                module(testConfig(), db)
            }
            startApplication()
            block(db)
        } finally {
            runCatching { application.dependencies.resolve<CarrierHttpClients>().close() }
            closeDatabase(db)
        }
    }

    private suspend fun ApplicationTestBuilder.postJson(
        path: String,
        body: String,
        token: String? = null,
    ): HttpResponse = client.post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
        if (token != null) bearerAuth(token)
    }

    private suspend fun ApplicationTestBuilder.getJson(path: String, token: String): HttpResponse =
        client.get(path) { bearerAuth(token) }

    private suspend fun ApplicationTestBuilder.patchJson(path: String, body: String, token: String): HttpResponse =
        client.patch(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            bearerAuth(token)
        }

    private suspend fun ApplicationTestBuilder.adminToken(): String {
        assertEquals(
            HttpStatusCode.Created,
            postJson("/api/v1/setup", """{"email":"admin@example.com","password":"admin-password-1"}""").status,
        )
        val login = postJson("/api/v1/auth/login", """{"email":"admin@example.com","password":"admin-password-1"}""")
        assertEquals(HttpStatusCode.OK, login.status)
        return bodyJson(login).getValue("token").jsonPrimitive.content
    }

    private suspend fun bodyJson(response: HttpResponse) =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject

    private suspend fun bodyJsonArray(response: HttpResponse) =
        Json.parseToJsonElement(response.bodyAsText()).jsonArray

    private suspend fun ApplicationTestBuilder.createParcelWithCarrier(
        token: String,
        trackingNumber: String,
        carrier: String,
    ): String {
        val response = postJson("/api/v1/parcels", """{"trackingNumber":"$trackingNumber","carrier":"$carrier"}""", token)
        assertEquals(HttpStatusCode.Created, response.status)
        return bodyJson(response).getValue("id").jsonPrimitive.content
    }

    private val dhlNumber = "1234567890"
    private val cttNumber = "DD506882333PT"
    private val paackNumber = "534000021268935007"
    private val postnlNumber = "3SPOSTNL00123456"

    @Test
    fun `listing parcels refreshes stale ones on open and repeated opens within the TTL trigger no carrier traffic`() {
        val dhl = RecordingAdapter(
            CarrierId.DHL,
            FakeCarrier(CarrierId.DHL, results = mapOf(dhlNumber to FakeCarrier.deterministicResult(dhlNumber))),
        )
        withApp("refresh-on-open", mapOf(CarrierId.DHL to dhl)) { _ ->
            val token = adminToken()
            val expected = FakeCarrier.deterministicResult(dhlNumber)
            createParcelWithCarrier(token, dhlNumber, "dhl")

            val first = getJson("/api/v1/parcels", token)
            assertEquals(HttpStatusCode.OK, first.status)
            val firstList = bodyJsonArray(first)
            assertEquals(1, firstList.size)
            val parcel = firstList[0].jsonObject
            assertEquals(expected.status.name, parcel.getValue("status").jsonPrimitive.content)
            assertNotNull(parcel["lastCheckedAt"])
            assertTrue(parcel["lastCheckError"] == JsonNull)
            assertEquals("Latest scan", parcel.getValue("latestEvent").jsonObject.getValue("description").jsonPrimitive.content)
            assertEquals(listOf(dhlNumber), dhl.calls)

            val second = getJson("/api/v1/parcels", token)
            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals(listOf(dhlNumber), dhl.calls, "a second open within the TTL must not trigger carrier traffic")
        }
    }

    @Test
    fun `POST refresh forces a refresh regardless of TTL and does not duplicate events`() {
        val dhl = RecordingAdapter(
            CarrierId.DHL,
            FakeCarrier(CarrierId.DHL, results = mapOf(dhlNumber to FakeCarrier.deterministicResult(dhlNumber))),
        )
        withApp("refresh-force", mapOf(CarrierId.DHL to dhl)) { _ ->
            val token = adminToken()
            val expected = FakeCarrier.deterministicResult(dhlNumber)
            val id = createParcelWithCarrier(token, dhlNumber, "dhl")

            assertEquals(HttpStatusCode.OK, getJson("/api/v1/parcels", token).status)
            assertEquals(listOf(dhlNumber), dhl.calls)

            val forced = postJson("/api/v1/parcels/$id/refresh", "", token)
            assertEquals(HttpStatusCode.OK, forced.status)
            val body = bodyJson(forced)
            assertEquals(expected.status.name, body.getValue("status").jsonPrimitive.content)
            assertNotNull(body["lastCheckedAt"])
            assertEquals(listOf(dhlNumber, dhlNumber), dhl.calls, "the forced refresh must probe even though the parcel is fresh")

            val events = bodyJsonArray(getJson("/api/v1/parcels/$id/events", token))
            assertEquals(3, events.size, "re-refreshing the same carrier result must not duplicate events")
        }
    }

    @Test
    fun `a carrier outage keeps cached data, marks the parcel softly and does not block the list`() =
        withApp(
            "refresh-outage",
            mapOf(
                CarrierId.DHL to RecordingAdapter(
                    CarrierId.DHL,
                    FakeCarrier(CarrierId.DHL, results = mapOf(dhlNumber to FakeCarrier.deterministicResult(dhlNumber))),
                ),
                CarrierId.CTT to FailingAdapter(CarrierId.CTT),
            ),
        ) { _ ->
            val token = adminToken()
            val dhlId = createParcelWithCarrier(token, dhlNumber, "dhl")
            val cttId = createParcelWithCarrier(token, cttNumber, "ctt")

            val list = getJson("/api/v1/parcels", token)
            assertEquals(HttpStatusCode.OK, list.status, "one unreachable carrier must not block the whole list")
            val parcels = bodyJsonArray(list).map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
            assertEquals(2, parcels.size)

            val failed = parcels.getValue(cttId)
            assertEquals("UNKNOWN", failed.getValue("status").jsonPrimitive.content, "cached data stays on outage")
            assertTrue(failed["latestEvent"] == JsonNull)
            assertTrue(failed.getValue("lastCheckError").jsonPrimitive.content.startsWith("carrier unreachable"))

            val ok = parcels.getValue(dhlId)
            assertTrue(ok["lastCheckError"] == JsonNull)
            assertNotNull(ok["lastCheckedAt"])
            assertTrue(ok["latestEvent"] != JsonNull)

            val failedEvents = getJson("/api/v1/parcels/$cttId/events", token)
            assertEquals(HttpStatusCode.OK, failedEvents.status)
            assertEquals(0, bodyJsonArray(failedEvents).size)
        }

    @Test
    fun `a hanging carrier is cut off by its own probe timeout and the list still renders`() =
        withApp(
            "refresh-timeout",
            mapOf(
                CarrierId.DHL to FakeCarrier(CarrierId.DHL, results = mapOf(dhlNumber to FakeCarrier.deterministicResult(dhlNumber))),
                CarrierId.PAACK to HangingAdapter(CarrierId.PAACK),
            ),
        ) { _ ->
            val token = adminToken()
            val dhlId = createParcelWithCarrier(token, dhlNumber, "dhl")
            val paackId = createParcelWithCarrier(token, paackNumber, "paack")

            val list = getJson("/api/v1/parcels", token)
            assertEquals(HttpStatusCode.OK, list.status)
            val parcels = bodyJsonArray(list).map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }

            val hung = parcels.getValue(paackId)
            assertTrue(
                hung.getValue("lastCheckError").jsonPrimitive.content.startsWith("carrier unreachable"),
                "the hanging carrier must be marked softly after its own timeout",
            )
            val ok = parcels.getValue(dhlId)
            assertTrue(ok["lastCheckError"] == JsonNull)
            assertTrue(ok["latestEvent"] != JsonNull)
        }

    @Test
    fun `a DELIVERED parcel past the grace window is not refreshed on open`() {
        val dhl = RecordingAdapter(
            CarrierId.DHL,
            FakeCarrier(CarrierId.DHL, results = mapOf(dhlNumber to FakeCarrier.deterministicResult(dhlNumber))),
        )
        withApp("refresh-delivered-past-grace", mapOf(CarrierId.DHL to dhl)) { db ->
            val token = adminToken()
            val id = createParcelWithCarrier(token, dhlNumber, "dhl")
            setParcelState(db, id, "DELIVERED", Instant.now().minus(4, ChronoUnit.DAYS))

            val list = getJson("/api/v1/parcels", token)
            assertEquals(HttpStatusCode.OK, list.status)
            assertEquals(emptyList<String>(), dhl.calls, "a DELIVERED parcel past the grace window must skip refresh")
        }
    }

    @Test
    fun `a DELIVERED parcel within the grace window is still refreshed when stale`() {
        val dhl = RecordingAdapter(
            CarrierId.DHL,
            FakeCarrier(CarrierId.DHL, results = mapOf(dhlNumber to FakeCarrier.deterministicResult(dhlNumber))),
        )
        withApp("refresh-delivered-within-grace", mapOf(CarrierId.DHL to dhl)) { db ->
            val token = adminToken()
            val id = createParcelWithCarrier(token, dhlNumber, "dhl")
            setParcelState(db, id, "DELIVERED", Instant.now().minus(2, ChronoUnit.DAYS))

            val list = getJson("/api/v1/parcels", token)
            assertEquals(HttpStatusCode.OK, list.status)
            assertEquals(listOf(dhlNumber), dhl.calls, "within the grace window a stale DELIVERED parcel is still refreshed")
            val parcel = bodyJsonArray(list)[0].jsonObject
            assertEquals(FakeCarrier.deterministicResult(dhlNumber).status.name, parcel.getValue("status").jsonPrimitive.content)
        }
    }

    @Test
    fun `a parcel whose carrier requires metadata gets a structured missing-metadata response on refresh until the key is added`() {
        val postcode = MetadataField("postcode", "Recipient postcode", "The 4 digits of your postcode", "\\d{4}")
        val postnl = FakeCarrier(
            CarrierId.POSTNL,
            requiredMetadata = mapOf("postcode" to postcode),
            results = mapOf(postnlNumber to FakeCarrier.deterministicResult(postnlNumber)),
        )
        withApp("refresh-needs-metadata", mapOf(CarrierId.POSTNL to postnl)) { _ ->
            val token = adminToken()
            val id = createParcelWithCarrier(token, postnlNumber, "postnl")

            val list = getJson("/api/v1/parcels", token)
            assertEquals(HttpStatusCode.OK, list.status)
            val listed = bodyJsonArray(list)[0].jsonObject
            assertEquals("UNKNOWN", listed.getValue("status").jsonPrimitive.content, "cached data stays while metadata is missing")
            assertTrue(listed["lastCheckError"] == JsonNull, "missing metadata is not an outage")
            assertTrue(listed["lastCheckedAt"] == JsonNull, "a missing-metadata probe is not a check")
            val missing = listed.getValue("missingMetadata").jsonArray
            assertEquals(1, missing.size)
            val missingField = missing[0].jsonObject
            assertEquals("postcode", missingField.getValue("name").jsonPrimitive.content)
            assertEquals("Recipient postcode", missingField.getValue("label").jsonPrimitive.content)
            assertEquals("The 4 digits of your postcode", missingField.getValue("hint").jsonPrimitive.content)
            assertEquals("\\d{4}", missingField.getValue("validationRegex").jsonPrimitive.content)

            val refresh = postJson("/api/v1/parcels/$id/refresh", "", token)
            assertEquals(HttpStatusCode.Conflict, refresh.status)
            val error = bodyJson(refresh).getValue("error").jsonObject
            assertEquals("missing-carrier-metadata", error.getValue("code").jsonPrimitive.content)
            val fields = error.getValue("fields").jsonArray
            assertEquals(1, fields.size)
            assertEquals("postcode", fields[0].jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals("\\d{4}", fields[0].jsonObject.getValue("validationRegex").jsonPrimitive.content)

            val invalid = patchJson("/api/v1/parcels/$id", """{"metadata":{"postcode":"abc"}}""", token)
            assertEquals(HttpStatusCode.OK, invalid.status)
            val stillMissing = postJson("/api/v1/parcels/$id/refresh", "", token)
            assertEquals(HttpStatusCode.Conflict, stillMissing.status, "a value failing validationRegex is still missing metadata")

            val valid = patchJson("/api/v1/parcels/$id", """{"metadata":{"postcode":"1234"}}""", token)
            assertEquals(HttpStatusCode.OK, valid.status)
            val expected = FakeCarrier.deterministicResult(postnlNumber)
            val ok = postJson("/api/v1/parcels/$id/refresh", "", token)
            assertEquals(HttpStatusCode.OK, ok.status)
            val body = bodyJson(ok)
            assertEquals(expected.status.name, body.getValue("status").jsonPrimitive.content)
            assertTrue(body["lastCheckError"] == JsonNull)
            assertNotNull(body["lastCheckedAt"])
            assertTrue(body["latestEvent"] != JsonNull)

            val after = bodyJsonArray(getJson("/api/v1/parcels", token))[0].jsonObject
            assertTrue(after["missingMetadata"] == JsonNull, "once the metadata is present the list no longer reports missing fields")
        }
    }

    private fun setParcelState(db: Database, parcelId: String, status: String, lastCheckedAt: Instant) {
        val userId = UserRepository(db).findByEmail("admin@example.com")!!.id
        ParcelRepository(db).updateParcelForUser(
            userId = userId,
            parcelId = parcelId,
            displayName = "P",
            status = status,
            carrier = "dhl",
            detectionMethod = "override",
            carrierMetadata = null,
            lastCheckedAt = isoUtc(lastCheckedAt),
            archivedAt = null,
            lastCheckError = null,
        )
    }
}
