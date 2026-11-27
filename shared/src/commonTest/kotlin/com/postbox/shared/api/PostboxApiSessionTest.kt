package com.postbox.shared.api

import com.postbox.shared.storage.TokenStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class RecordingTokenStorage : TokenStorage {
    var access: String? = "old-access"
    var refresh: String? = "old-refresh"
    var session: String? = "session-1"

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

private val parcelBody = """[{"id":"11111111-1111-1111-1111-111111111111","trackingNumber":"1Z999","displayName":"1Z999","status":"IN_TRANSIT","createdAt":"2026-11-27T10:00:00Z","latestEvent":null,"carrier":null}]"""

private val rotatedSession = """{"token":"new-access","refreshToken":"new-refresh","sessionId":"session-1"}"""

private fun err(code: String, message: String) =
    """{"error":{"code":"$code","message":"$message"}}"""

class PostboxApiSessionTest {
    @Test
    fun unauthorizedAuthedCallSilentlyRefreshesAndRetries() = runTest {
        val requests = mutableListOf<Pair<String, String?>>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests.add(path to request.headers[HttpHeaders.Authorization])
            when {
                path == "/api/v1/auth/refresh" && request.method == HttpMethod.Post ->
                    respond(rotatedSession, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                path == "/api/v1/parcels" && request.headers[HttpHeaders.Authorization] == "Bearer old-access" ->
                    respond(err("unauthorized", "expired"), HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
                path == "/api/v1/parcels" ->
                    respond(parcelBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else ->
                    respond(err("not-found", "?"), HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val tokens = RecordingTokenStorage()
        val api = PostboxApi("http://localhost:8080", tokens, engine)

        val parcels = api.listParcels()

        assertEquals(1, parcels.size)
        assertEquals("/api/v1/parcels", requests[0].first)
        assertEquals("Bearer old-access", requests[0].second)
        assertEquals("/api/v1/auth/refresh", requests[1].first)
        assertEquals("Bearer new-access", requests[2].second)
        assertEquals("new-access", tokens.accessToken())
        assertEquals("new-refresh", tokens.refreshToken())
    }

    @Test
    fun failedRefreshClearsTokensAndThrowsSessionExpired() = runTest {
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath == "/api/v1/auth/refresh") {
                err("invalid-refresh-token", "revoked")
            } else {
                err("unauthorized", "expired")
            }
            respond(body, HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val tokens = RecordingTokenStorage()
        val api = PostboxApi("http://localhost:8080", tokens, engine)

        assertFailsWith<SessionExpiredException> { api.listParcels() }
        assertNull(tokens.accessToken())
        assertNull(tokens.refreshToken())
        assertNull(tokens.sessionId())
    }

    @Test
    fun transientRefreshFailureKeepsTokensAndSurfacesOriginalError() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/refresh" -> respond(
                    err("boom", "server exploded"),
                    HttpStatusCode.InternalServerError,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> respond(err("unauthorized", "expired"), HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val tokens = RecordingTokenStorage()
        val api = PostboxApi("http://localhost:8080", tokens, engine)

        val e = assertFailsWith<ApiException> { api.listParcels() }
        assertEquals("unauthorized", e.code)
        assertEquals(401, e.status)
        assertEquals("old-access", tokens.accessToken())
        assertEquals("old-refresh", tokens.refreshToken())
    }

    @Test
    fun retryUnauthorizedAfterRotationClearsTokensAndThrowsSessionExpired() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/api/v1/auth/refresh" ->
                    respond(rotatedSession, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                request.headers[HttpHeaders.Authorization] == "Bearer new-access" ->
                    respond(err("unauthorized", "still rejected"), HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
                else ->
                    respond(err("unauthorized", "expired"), HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val tokens = RecordingTokenStorage()
        val api = PostboxApi("http://localhost:8080", tokens, engine)

        assertFailsWith<SessionExpiredException> { api.listParcels() }
        assertNull(tokens.accessToken())
        assertNull(tokens.refreshToken())
        assertNull(tokens.sessionId())
    }

    @Test
    fun publicCall401DoesNotTriggerRefresh() = runTest {
        var refreshCalls = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/api/v1/auth/refresh") refreshCalls++
            respond(err("invalid-credentials", "bad login"), HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val tokens = RecordingTokenStorage()
        val api = PostboxApi("http://localhost:8080", tokens, engine)

        val e = assertFailsWith<ApiException> { api.login("a@b.c", "wrong-password") }
        assertEquals("invalid-credentials", e.code)
        assertEquals(0, refreshCalls)
        assertEquals("old-access", tokens.accessToken())
    }

    @Test
    fun setupStatusAndHealthzArePublic() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/healthz" -> respond("""{"status":"ok"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                "/api/v1/setup/status" -> respond("""{"setupPending":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(err("not-found", "?"), HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val api = PostboxApi("http://localhost:8080", RecordingTokenStorage(), engine)

        assertEquals("ok", api.healthz())
        assertEquals(true, api.setupStatus())
    }
}
