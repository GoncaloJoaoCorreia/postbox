package com.postbox.shared.api

import com.postbox.shared.domain.model.Carrier
import com.postbox.shared.domain.model.TrackingStatus
import com.postbox.shared.storage.TokenStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class FakeTokenStorage : TokenStorage {
    var access: String? = "token-123"
    var refresh: String? = null
    var session: String? = null

    override fun accessToken(): String? = access

    override fun refreshToken(): String? = refresh

    override fun sessionId(): String? = session

    override fun save(accessToken: String, refreshToken: String, sessionId: String) {
        access = accessToken
        refresh = refreshToken
        session = sessionId
    }

    override fun clear() {
        access = null
        refresh = null
        session = null
    }
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
