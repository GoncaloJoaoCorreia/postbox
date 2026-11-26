package com.postbox

import com.postbox.carrier.CarrierClientSettings
import com.postbox.carrier.CarrierHttpClients
import com.postbox.carrier.CarrierId
import com.postbox.carrier.CarrierRateLimiter
import com.postbox.carrier.CarrierRegistry
import com.postbox.db.Migrations
import com.postbox.db.closeDatabase
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.BindException
import kotlin.time.Duration.Companion.milliseconds

private val log = LoggerFactory.getLogger("com.postbox.Application")

@Serializable
data class HealthStatus(val status: String)

/** Attribute key under which [Application.module] exposes the [CarrierRegistry]. */
val CarrierRegistryAttribute = AttributeKey<CarrierRegistry>("com.postbox.carrier.registry")

/** Attribute key for the per-carrier HTTP clients built from the carrier config. */
val CarrierHttpClientsAttribute = AttributeKey<CarrierHttpClients>("com.postbox.carrier.http-clients")

/** Attribute key for the per-carrier rate limiters built from the carrier config. */
val CarrierRateLimitersAttribute =
    AttributeKey<Map<CarrierId, CarrierRateLimiter>>("com.postbox.carrier.rate-limiters")

/** Maps the parsed `postbox.carriers.<id>` block to the carrier HTTP client factory's settings. */
internal fun CarrierSettings.toClientSettings() = CarrierClientSettings(
    connectTimeoutMs = connectTimeoutMs,
    socketTimeoutMs = socketTimeoutMs,
    requestTimeoutMs = requestTimeoutMs,
    userAgent = userAgent,
)

/** Builds the per-carrier rate limiter spaced by the configured minimum request interval. */
internal fun CarrierSettings.toRateLimiter() =
    CarrierRateLimiter(minInterval = minRequestIntervalMs.milliseconds)

fun Application.module(config: AppConfig) {
    install(ContentNegotiation) {
        json()
    }
    // Per-carrier HTTP clients and rate limiters are built from the parsed
    // postbox.carriers.<id> config, not from hardcoded values (DHL defaults to
    // one call per 5 s via minRequestIntervalMs).
    val httpClients = CarrierHttpClients(
        config.carriers.mapValues { (_, settings) -> settings.toClientSettings() }
    )
    val rateLimiters = config.carriers.mapValues { (_, settings) -> settings.toRateLimiter() }
    // No real carrier adapters yet (later issues register them); the registry starts empty.
    val registry = CarrierRegistry(adapters = emptyMap())
    attributes.put(CarrierRegistryAttribute, registry)
    attributes.put(CarrierHttpClientsAttribute, httpClients)
    attributes.put(CarrierRateLimitersAttribute, rateLimiters)
    // Release every per-carrier CIO engine (selector thread, dispatcher) when the
    // server stops; testApplication never raises ApplicationStopping, so tests
    // that build clients close them explicitly.
    monitor.subscribe(ApplicationStopping) { httpClients.close() }
    log.info(
        "Carrier wiring: {} carriers configured, {} adapters registered",
        config.carriers.size, registry.all.size
    )
    routing {
        get("/healthz") {
            call.respond(HealthStatus(status = "ok"))
        }
    }
}

fun main() {
    val config = ConfigLoader.load()
    ConfigLoader.ensureDataDir(config)
    val db = Migrations.run(config.sqlitePath)
    Runtime.getRuntime().addShutdownHook(Thread { closeDatabase(db) })
    log.info(
        "Starting postbox backend on {}:{} (data dir: {}, sqlite: {})",
        config.host, config.port, config.dataDir, config.sqlitePath
    )
    try {
        embeddedServer(Netty, host = config.host, port = config.port) {
            module(config)
        }.start(wait = true)
    } catch (e: BindException) {
        log.error("Port {} is already in use — set PORT to a free port and retry", config.port)
        throw e
    }
}
