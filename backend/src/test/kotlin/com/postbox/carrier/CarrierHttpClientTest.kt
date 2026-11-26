package com.postbox.carrier

import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

class CarrierHttpClientTest {

    private val settings = CarrierClientSettings(
        connectTimeoutMs = 1_000,
        socketTimeoutMs = 2_000,
        requestTimeoutMs = 3_000,
        userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36",
    )

    @Test
    fun `factory builds a CIO client carrying the configured timeouts and user agent`() = runBlocking {
        val client = createCarrierHttpClient(settings)
        try {
            assertIs<CIOEngineConfig>(client.engineConfig) // CIO engine
            assertNotNull(client.pluginOrNull(ContentNegotiation))

            // Short-circuit the send pipeline before the engine so the test performs no network I/O.
            var captured: HttpRequestBuilder? = null
            client.plugin(HttpSend).intercept { request ->
                captured = request
                error("request captured; not proceeding to the engine")
            }
            try {
                client.get("https://carrier.invalid/track")
            } catch (e: CancellationException) {
                throw e // scope cancellation must propagate, never be swallowed
            } catch (ignored: Exception) {
                // the interceptor above stopped the send before any network call
            }
            val request = captured ?: error("the send interceptor never saw a request")

            assertEquals(settings.userAgent, request.headers[HttpHeaders.UserAgent])
            val timeout = request.getCapabilityOrNull(HttpTimeoutCapability)
                ?: error("HttpTimeout capability missing on the request")
            assertEquals(settings.connectTimeoutMs, timeout.connectTimeoutMillis)
            assertEquals(settings.socketTimeoutMs, timeout.socketTimeoutMillis)
            assertEquals(settings.requestTimeoutMs, timeout.requestTimeoutMillis)
        } finally {
            client.close()
        }
    }

    @Test
    fun `holder builds one client per carrier from the settings map`() {
        val clients = CarrierHttpClients(CarrierId.entries.associateWith { settings })
        try {
            assertEquals(CarrierId.entries.toSet(), clients.carriers)
            for (carrier in CarrierId.entries) {
                assertIs<CIOEngineConfig>(clients[carrier].engineConfig)
            }
            assertNotSame(clients[CarrierId.DHL], clients[CarrierId.CTT])
        } finally {
            clients.close()
        }
    }
}
