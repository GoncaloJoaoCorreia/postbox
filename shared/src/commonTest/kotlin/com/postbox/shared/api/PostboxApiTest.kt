package com.postbox.shared.api

import com.postbox.shared.domain.model.Carrier
import com.postbox.shared.domain.model.DetectionMethod
import com.postbox.shared.domain.model.TrackingStatus
import com.postbox.shared.storage.TokenStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun requestBodyText(request: HttpRequestData): String = when (val body = request.body) {
    is TextContent -> body.text
    is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
    else -> body.toString()
}

private class FakeTokenStorage : TokenStorage {
    var access: String? = "token-123"
    var refresh: String? = null
    var session: String? = null
    private val changeSignal = MutableSharedFlow<Unit>(replay = 1)

    override fun accessToken(): String? = access

    override fun refreshToken(): String? = refresh

    override fun sessionId(): String? = session

    override fun save(accessToken: String, refreshToken: String, sessionId: String) {
        access = accessToken
        refresh = refreshToken
        session = sessionId
        changeSignal.tryEmit(Unit)
    }

    override fun clear() {
        access = null
        refresh = null
        session = null
        changeSignal.tryEmit(Unit)
    }

    override fun changes(): Flow<Unit> = changeSignal
}

class PostboxApiTest {
    @Test
    fun unauthorizedEnvelopeMapsToTypedError() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer token-123", request.headers[HttpHeaders.Authorization])
            respond(
                """{"error":{"code":"unauthorized","message":"A valid bearer token is required"}}""",
                HttpStatusCode.Unauthorized,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val api = PostboxApi("http://localhost:8080", FakeTokenStorage(), engine)
        val e = assertFailsWith<ApiException> { api.listParcels() }
        assertEquals("unauthorized", e.code)
        assertEquals(401, e.status)
        assertEquals("A valid bearer token is required", e.message)
    }

    @Test
    fun conflictEnvelopeKeepsStructuredFields() = runTest {
        val body = """{"error":{"code":"missing-carrier-metadata","message":"Carrier needs metadata","fields":[{"name":"postcode","label":"Recipient postcode","hint":"The 4 digits","validationRegex":"\\d{4}"}]}}"""
        val engine = MockEngine {
            respond(body, HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = PostboxApi("http://localhost:8080", FakeTokenStorage(), engine)
        val e = assertFailsWith<ApiException> { api.refreshParcel("p1") }
        assertEquals("missing-carrier-metadata", e.code)
        assertEquals(409, e.status)
        assertEquals("postcode", e.fields?.first()?.name)
    }

    @Test
    fun listParcelsParsesResponseIntoDomainModel() = runTest {
        val body = """[{"id":"11111111-1111-1111-1111-111111111111","trackingNumber":"1Z999","displayName":"1Z999","status":"IN_TRANSIT","createdAt":"2026-11-27T10:00:00Z","latestEvent":{"id":"22222222-2222-2222-2222-222222222222","occurredAt":"2026-11-27T11:00:00Z","status":"IN_TRANSIT","description":"In transit","location":null},"carrier":"dhl"}]"""
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = PostboxApi("http://localhost:8080", FakeTokenStorage(), engine)
        val parcels = api.listParcels()
        assertEquals(1, parcels.size)
        assertEquals(Carrier.dhl, parcels.first().carrier)
        assertEquals(TrackingStatus.IN_TRANSIT, parcels.first().latestEvent?.status)
    }

    @Test
    fun unknownEnumValuesFromNewerServerDegradeGracefully() = runTest {
        val body = """[{"id":"11111111-1111-1111-1111-111111111111","trackingNumber":"X1","displayName":"X1","status":"TELEPORTED","createdAt":"2026-11-27T10:00:00Z","latestEvent":null,"carrier":"quantum","detectionMethod":"magic"}]"""
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = PostboxApi("http://localhost:8080", FakeTokenStorage(), engine)
        val parcel = api.listParcels().single()
        assertEquals(TrackingStatus.UNKNOWN, parcel.status)
        assertNull(parcel.carrier)
        assertNull(parcel.detectionMethod)
    }

    @Test
    fun detectPostsToDetectEndpointAndParsesOutcome() = runTest {
        val body = """{"trackingNumber":"1Z999","carrier":"dhl","method":"probe","candidates":["dhl","ctt"]}"""
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/parcels/detect", request.url.encodedPath)
            assertEquals("Bearer token-123", request.headers[HttpHeaders.Authorization])
            assertTrue(requestBodyText(request).contains("\"trackingNumber\":\"1Z999\""))
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = PostboxApi("http://localhost:8080", FakeTokenStorage(), engine)
        val result = api.detect("1Z999")
        assertEquals("1Z999", result.trackingNumber)
        assertEquals(Carrier.dhl, result.carrier)
        assertEquals(DetectionMethod.probe, result.method)
        assertEquals(listOf(Carrier.dhl, Carrier.ctt), result.candidates)
    }

    @Test
    fun detectWithCarrierOverrideSendsCarrierAndMetadataAndMapsOverrideMethod() = runTest {
        val body = """{"trackingNumber":"RR123456789PT","carrier":"ctt","method":"override","candidates":["ctt"]}"""
        val engine = MockEngine { request ->
            val sent = requestBodyText(request)
            assertTrue(sent.contains("\"carrier\":\"ctt\""))
            assertTrue(sent.contains("\"postcode\":\"4250-004\""))
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = PostboxApi("http://localhost:8080", FakeTokenStorage(), engine)
        val result = api.detect("RR123456789PT", carrier = Carrier.ctt, metadata = mapOf("postcode" to "4250-004"))
        assertEquals(DetectionMethod.override, result.method)
        assertEquals(Carrier.ctt, result.carrier)
    }

    @Test
    fun createParcelPostsAndParsesCreatedParcel() = runTest {
        val body = """{"id":"44444444-4444-4444-4444-444444444444","trackingNumber":"1Z999","displayName":"1Z999","status":"UNKNOWN","createdAt":"2026-11-27T10:00:00Z","latestEvent":null,"carrier":"ctt","detectionMethod":"override","metadata":{"postcode":"4250-004"}}"""
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/parcels", request.url.encodedPath)
            val sent = requestBodyText(request)
            assertTrue(sent.contains("\"carrier\":\"ctt\""))
            assertTrue(sent.contains("\"postcode\":\"4250-004\""))
            respond(body, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = PostboxApi("http://localhost:8080", FakeTokenStorage(), engine)
        val parcel = api.createParcel("1Z999", carrier = Carrier.ctt, metadata = mapOf("postcode" to "4250-004"))
        assertEquals("44444444-4444-4444-4444-444444444444", parcel.id)
        assertEquals(TrackingStatus.UNKNOWN, parcel.status)
        assertEquals(Carrier.ctt, parcel.carrier)
        assertEquals(DetectionMethod.override, parcel.detectionMethod)
        assertEquals("4250-004", parcel.metadata?.get("postcode"))
    }

    @Test
    fun createParcelValidationErrorKeepsInlineCode() = runTest {
        val body = """{"error":{"code":"invalid-tracking-number","message":"Tracking number must be 1-64 characters of letters, digits or hyphens"}}"""
        val engine = MockEngine {
            respond(body, HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = PostboxApi("http://localhost:8080", FakeTokenStorage(), engine)
        val e = assertFailsWith<ApiException> { api.createParcel("bad number!") }
        assertEquals("invalid-tracking-number", e.code)
        assertEquals(400, e.status)
    }

    @Test
    fun loginMapsResponseToSession() = runTest {
        val body = """{"token":"jwt-access","refreshToken":"opaque-refresh","sessionId":"33333333-3333-3333-3333-333333333333"}"""
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = PostboxApi("http://localhost:8080", FakeTokenStorage(), engine)
        val session = api.login("admin@example.com", "supersecret1", deviceName = "Pixel 9")
        assertEquals("jwt-access", session.accessToken)
        assertEquals("opaque-refresh", session.refreshToken)
        assertEquals("33333333-3333-3333-3333-333333333333", session.sessionId)
    }
}
