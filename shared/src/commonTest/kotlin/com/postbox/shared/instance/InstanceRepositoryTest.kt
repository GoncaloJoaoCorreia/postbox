package com.postbox.shared.instance

import com.postbox.shared.api.PostboxApi
import com.postbox.shared.storage.InstanceSessionStore
import com.postbox.shared.storage.TokenStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private class MemTokenStorage : TokenStorage {
    override fun accessToken(): String? = null
    override fun refreshToken(): String? = null
    override fun sessionId(): String? = null
    override fun save(accessToken: String, refreshToken: String, sessionId: String) {}
    override fun clear() {}
}

private class MemSessionStore : InstanceSessionStore {
    var url: String? = null

    override fun baseUrl(): String? = url

    override fun save(baseUrl: String) {
        url = baseUrl
    }

    override fun clear() {
        url = null
    }
}

class InstanceRepositoryTest {
    private fun repo(engine: MockEngine, store: MemSessionStore) =
        InstanceRepository(store) { url -> PostboxApi(url, MemTokenStorage(), engine) }

    @Test
    fun connectValidatesHealthAndSetupStatusThenPersistsNormalizedUrl() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/healthz" -> respond("""{"status":"ok"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                "/api/v1/setup/status" -> respond("""{"setupPending":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond("""{"error":{"code":"not-found","message":"?"}}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val store = MemSessionStore()

        val result = repo(engine, store).connect("postbox.example.com:8443/")

        val connected = assertIs<InstanceProbeResult.Connected>(result)
        assertEquals(true, connected.setupPending)
        assertEquals("https://postbox.example.com:8443", store.baseUrl())
    }

    @Test
    fun connectRejectsInvalidUrlWithoutAnyRequest() = runTest {
        var called = false
        val engine = MockEngine {
            called = true
            respond("""{"status":"ok"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val store = MemSessionStore()

        val result = repo(engine, store).connect("ftp://nope")

        assertIs<InstanceProbeResult.InvalidUrl>(result)
        assertEquals(false, called)
        assertNull(store.baseUrl())
    }

    @Test
    fun connectReportsUnreachableWhenHealthzFails() = runTest {
        val engine = MockEngine {
            respond(
                """{"error":{"code":"boom","message":"server exploded"}}""",
                HttpStatusCode.InternalServerError,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val store = MemSessionStore()

        val result = repo(engine, store).connect("https://down.example.com")

        val unreachable = assertIs<InstanceProbeResult.Unreachable>(result)
        assertEquals("server exploded", unreachable.message)
        assertNull(store.baseUrl())
    }
}
