package com.postbox.shared.auth

import com.postbox.shared.api.PostboxApi
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class MemTokenStorage : TokenStorage {
    var access: String? = null
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

class AuthRepositoryTest {
    @Test
    fun loginPersistsTokensAndSessionId() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/v1/auth/login", request.url.encodedPath)
            respond(
                """{"token":"jwt","refreshToken":"opaque","sessionId":"s-1"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val tokens = MemTokenStorage()
        val repo = AuthRepository(PostboxApi("http://x", tokens, engine), tokens) {}

        val session = repo.login("a@b.c", "password123", "Pixel 9")

        assertEquals("jwt", session.accessToken)
        assertEquals("jwt", tokens.accessToken())
        assertEquals("opaque", tokens.refreshToken())
        assertEquals("s-1", tokens.sessionId())
        assertTrue(repo.hasSession())
    }

    @Test
    fun logoutRevokesCurrentSessionServerSideAndWipesLocalState() = runTest {
        val revoked = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            if (request.method == HttpMethod.Delete && path.startsWith("/api/v1/me/devices/")) {
                revoked.add(path.substringAfterLast('/'))
                respond("", HttpStatusCode.NoContent)
            } else {
                respond(
                    """{"error":{"code":"unauthorized","message":"expired"}}""",
                    HttpStatusCode.Unauthorized,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        val tokens = MemTokenStorage().apply {
            save("jwt", "opaque", "s-1")
        }
        var cacheCleared = false
        val repo = AuthRepository(PostboxApi("http://x", tokens, engine), tokens) { cacheCleared = true }

        repo.logout()

        assertEquals(listOf("s-1"), revoked)
        assertFalse(repo.hasSession())
        assertTrue(cacheCleared)
    }

    @Test
    fun logoutStillWipesLocalStateWhenRevocationFails() = runTest {
        val engine = MockEngine {
            respond(
                """{"error":{"code":"device-not-found","message":"gone"}}""",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val tokens = MemTokenStorage().apply { save("jwt", "opaque", "s-1") }
        var cacheCleared = false
        val repo = AuthRepository(PostboxApi("http://x", tokens, engine), tokens) { cacheCleared = true }

        repo.logout()

        assertFalse(repo.hasSession())
        assertTrue(cacheCleared)
    }

    @Test
    fun revokingOwnDeviceFromListEndsLocalSession() = runTest {
        val engine = MockEngine {
            respond("", HttpStatusCode.NoContent)
        }
        val tokens = MemTokenStorage().apply { save("jwt", "opaque", "s-1") }
        var cacheCleared = false
        val repo = AuthRepository(PostboxApi("http://x", tokens, engine), tokens) { cacheCleared = true }

        repo.revokeDevice("s-1")

        assertFalse(repo.hasSession())
        assertTrue(cacheCleared)
    }
}
