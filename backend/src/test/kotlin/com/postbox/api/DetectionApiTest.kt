package com.postbox.api

import com.postbox.AppConfig
import com.postbox.carrier.CarrierAdapter
import com.postbox.carrier.CarrierId
import com.postbox.carrier.FakeCarrier
import com.postbox.carrier.MetadataField
import com.postbox.carrier.TrackingResult
import com.postbox.db.closeDatabase
import com.postbox.db.freshTestDatabase
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectionApiTest {

    private class RecordingAdapter(
        override val carrier: CarrierId,
        private val delegate: CarrierAdapter,
        val calls: MutableList<String> = mutableListOf(),
    ) : CarrierAdapter {
        override val requiredMetadata: Map<String, MetadataField> = delegate.requiredMetadata

        override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult {
            calls += number
            return delegate.track(number, metadata)
        }
    }

    private fun testConfig() = AppConfig(
        host = "127.0.0.1",
        port = 0,
        jwtSecret = "test-secret",
        sqlitePath = "build/tmp/test/postbox.db",
        dataDir = "build/tmp/test-data",
    )

    private fun withApp(
        name: String,
        adapters: Map<CarrierId, CarrierAdapter> = emptyMap(),
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
            block(db)
        } finally {
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

    private val cttNumber = "DD506882333PT"
    private val dhlNumber = "1234567890"
    private val paackNumber = "534000021268935007"

    @Test
    fun `detect probes the rule candidates and the first carrier with real data wins`() = withApp(
        "detect-probe-hit",
        mapOf(
            CarrierId.DHL to FakeCarrier(CarrierId.DHL, results = mapOf(dhlNumber to FakeCarrier.deterministicResult(dhlNumber))),
            CarrierId.PAACK to FakeCarrier(CarrierId.PAACK, results = emptyMap()),
        ),
    ) { _ ->
        val token = adminToken()

        val response = postJson("/api/v1/parcels/detect", """{"trackingNumber":"$dhlNumber"}""", token)
        assertEquals(HttpStatusCode.OK, response.status)
        val body = bodyJson(response)
        assertEquals("dhl", body.getValue("carrier").jsonPrimitive.content)
        assertEquals("probe", body.getValue("method").jsonPrimitive.content)
        assertEquals(listOf("dhl", "paack"), body.getValue("candidates").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `detect with an explicit carrier returns override and never probes`() {
        val ctt = RecordingAdapter(
            CarrierId.CTT,
            FakeCarrier(CarrierId.CTT, results = mapOf(cttNumber to FakeCarrier.deterministicResult(cttNumber))),
        )
        withApp("detect-override", mapOf(CarrierId.CTT to ctt)) { _ ->
            val token = adminToken()

            val response = postJson(
                "/api/v1/parcels/detect",
                """{"trackingNumber":"$cttNumber","carrier":"dhl"}""",
                token,
            )
            assertEquals(HttpStatusCode.OK, response.status)
            val body = bodyJson(response)
            assertEquals("dhl", body.getValue("carrier").jsonPrimitive.content)
            assertEquals("override", body.getValue("method").jsonPrimitive.content)
            assertEquals(listOf("ctt", "paack"), body.getValue("candidates").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(ctt.calls.isEmpty(), "an overridden detection must not probe any carrier")
        }
    }

    @Test
    fun `adding a single-format number resolves in a single probe and stores the outcome`() = withApp(
        "create-single-probe",
        mapOf(
            CarrierId.DHL to FakeCarrier(CarrierId.DHL, results = mapOf(dhlNumber to FakeCarrier.deterministicResult(dhlNumber))),
            CarrierId.PAACK to FakeCarrier(CarrierId.PAACK, results = emptyMap()),
        ),
    ) { _ ->
        val token = adminToken()

        val created = postJson("/api/v1/parcels", """{"trackingNumber":"$dhlNumber"}""", token)
        assertEquals(HttpStatusCode.Created, created.status)
        val parcel = bodyJson(created)
        assertEquals("dhl", parcel.getValue("carrier").jsonPrimitive.content)
        assertEquals("probe", parcel.getValue("detectionMethod").jsonPrimitive.content)

        val fetched = client.get("/api/v1/parcels/${parcel.getValue("id").jsonPrimitive.content}") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertEquals("probe", bodyJson(fetched).getValue("detectionMethod").jsonPrimitive.content)
    }

    @Test
    fun `adding an ambiguous number resolves to the carrier that actually knows it`() = withApp(
        "create-ambiguous",
        mapOf(
            CarrierId.PAACK to FakeCarrier(CarrierId.PAACK, results = mapOf(paackNumber to FakeCarrier.deterministicResult(paackNumber))),
            CarrierId.ECOSCOOTING to FakeCarrier(CarrierId.ECOSCOOTING, results = emptyMap()),
        ),
    ) { _ ->
        val token = adminToken()

        val created = postJson("/api/v1/parcels", """{"trackingNumber":"$paackNumber"}""", token)
        assertEquals(HttpStatusCode.Created, created.status)
        val parcel = bodyJson(created)
        assertEquals("paack", parcel.getValue("carrier").jsonPrimitive.content)
        assertEquals("probe", parcel.getValue("detectionMethod").jsonPrimitive.content)
    }

    @Test
    fun `adding a number no carrier knows yet keeps the top candidate provisionally`() = withApp(
        "create-provisional",
        mapOf(CarrierId.CTT to FakeCarrier(CarrierId.CTT, results = emptyMap())),
    ) { _ ->
        val token = adminToken()

        val created = postJson("/api/v1/parcels", """{"trackingNumber":"$cttNumber"}""", token)
        assertEquals(HttpStatusCode.Created, created.status)
        val parcel = bodyJson(created)
        assertEquals("ctt", parcel.getValue("carrier").jsonPrimitive.content)
        assertEquals("rule", parcel.getValue("detectionMethod").jsonPrimitive.content)
    }

    @Test
    fun `an explicit carrier on create is stored as override and never probed`() {
        val ctt = RecordingAdapter(
            CarrierId.CTT,
            FakeCarrier(CarrierId.CTT, results = mapOf(cttNumber to FakeCarrier.deterministicResult(cttNumber))),
        )
        withApp("create-override", mapOf(CarrierId.CTT to ctt)) { _ ->
            val token = adminToken()

            val created = postJson(
                "/api/v1/parcels",
                """{"trackingNumber":"$cttNumber","carrier":"dhl"}""",
                token,
            )
            assertEquals(HttpStatusCode.Created, created.status)
            val parcel = bodyJson(created)
            assertEquals("dhl", parcel.getValue("carrier").jsonPrimitive.content)
            assertEquals("override", parcel.getValue("detectionMethod").jsonPrimitive.content)
            assertTrue(ctt.calls.isEmpty(), "an explicit carrier override must not probe any carrier")
        }
    }

    @Test
    fun `patching the carrier records override while other patches keep the detection method`() = withApp(
        "patch-detection-method",
        mapOf(CarrierId.CTT to FakeCarrier(CarrierId.CTT, results = emptyMap())),
    ) { _ ->
        val token = adminToken()

        val created = postJson("/api/v1/parcels", """{"trackingNumber":"$cttNumber"}""", token)
        val id = bodyJson(created).getValue("id").jsonPrimitive.content
        assertEquals("rule", bodyJson(created).getValue("detectionMethod").jsonPrimitive.content)

        val renamed = patchJson("/api/v1/parcels/$id", """{"displayName":"Shoes"}""", token)
        assertEquals(HttpStatusCode.OK, renamed.status)
        assertEquals("rule", bodyJson(renamed).getValue("detectionMethod").jsonPrimitive.content)

        val reCarrier = patchJson("/api/v1/parcels/$id", """{"carrier":"paack"}""", token)
        assertEquals(HttpStatusCode.OK, reCarrier.status)
        assertEquals("paack", bodyJson(reCarrier).getValue("carrier").jsonPrimitive.content)
        assertEquals("override", bodyJson(reCarrier).getValue("detectionMethod").jsonPrimitive.content)
    }
}
