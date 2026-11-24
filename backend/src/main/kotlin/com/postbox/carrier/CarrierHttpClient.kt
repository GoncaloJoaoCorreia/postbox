package com.postbox.carrier

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.Closeable

/**
 * Per-carrier HTTP client settings. All values are supplied by the caller (config wiring
 * is a later subtask); nothing carrier-specific is hardcoded here.
 */
data class CarrierClientSettings(
    val connectTimeoutMs: Long,
    val socketTimeoutMs: Long,
    val requestTimeoutMs: Long,
    val userAgent: String,
)

/**
 * Builds the [HttpClient] for one carrier: the CIO engine with explicit connect, socket and
 * request timeouts plus the configured browser-like [CarrierClientSettings.userAgent], and
 * JSON content negotiation for carrier API payloads.
 */
fun createCarrierHttpClient(settings: CarrierClientSettings): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        // Ktor 3's HttpTimeoutConfig exposes the *Millis names; the Ms names are Ktor 2 only.
        connectTimeoutMillis = settings.connectTimeoutMs
        socketTimeoutMillis = settings.socketTimeoutMs
        requestTimeoutMillis = settings.requestTimeoutMs
    }
    install(UserAgent) {
        agent = settings.userAgent
    }
    install(ContentNegotiation) {
        json()
    }
}

/**
 * One [HttpClient] per carrier, built from the caller-supplied per-carrier settings map.
 * Close it to release every client's engine.
 */
class CarrierHttpClients(settings: Map<CarrierId, CarrierClientSettings>) : Closeable {
    private val clients: Map<CarrierId, HttpClient> =
        settings.mapValues { (_, carrierSettings) -> createCarrierHttpClient(carrierSettings) }

    val carriers: Set<CarrierId> = clients.keys

    operator fun get(carrier: CarrierId): HttpClient = clients.getValue(carrier)

    override fun close() {
        clients.values.forEach { it.close() }
    }
}
