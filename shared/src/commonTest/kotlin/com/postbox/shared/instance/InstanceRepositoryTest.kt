package com.postbox.shared.instance

import com.postbox.shared.api.PostboxApi
import com.postbox.shared.storage.InstanceSessionStore
import com.postbox.shared.storage.TokenStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private class MemTokenStorage : TokenStorage {
    private val changeSignal = MutableSharedFlow<Unit>(replay = 1)

    override fun accessToken(): String? = null
    override fun refreshToken(): String? = null
    override fun sessionId(): String? = null
    override fun save(accessToken: String, refreshToken: String, sessionId: String) {
        changeSignal.tryEmit(Unit)
    }
    override fun clear() {
        changeSignal.tryEmit(Unit)
    }
    override fun changes(): Flow<Unit> = changeSignal
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
    private fun repo(
        engine: MockEngine,
        store: MemSessionStore,
        tokens: MemTokenStorage = MemTokenStorage(),
        onClearLocalData: suspend () -> Unit = {}
    ) = InstanceRepository(store, onClearLocalData) { url -> PostboxApi(url, tokens, engine) }

    private fun healthyEngine() = MockEngine { request ->
        when (request.url.encodedPath) {
            "/healthz" -> respond("""{"status":"ok"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            "/api/v1/setup/status" -> respond("""{"setupPending":false}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            else -> respond("""{"error":{"code":"not-found","message":"?"}}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

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
    fun connectToDifferentInstanceClearsLocalData() = runTest {
        val store = MemSessionStore().apply { save("https://old.example.com") }
        var cleared = false
        val repo = repo(healthyEngine(), store) { cleared = true }

        val result = repo.connect("https://new.example.com")

        assertIs<InstanceProbeResult.Connected>(result)
        assertEquals("https://new.example.com", store.baseUrl())
        assertEquals(true, cleared)
    }

    @Test
    fun connectToSameInstanceKeepsLocalData() = runTest {
        val store = MemSessionStore().apply { save("https://same.example.com") }
        var cleared = false
        val repo = repo(healthyEngine(), store) { cleared = true }

        val result = repo.connect("same.example.com")

        assertIs<InstanceProbeResult.Connected>(result)
        assertEquals("https://same.example.com", store.baseUrl())
        assertEquals(false, cleared)
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
