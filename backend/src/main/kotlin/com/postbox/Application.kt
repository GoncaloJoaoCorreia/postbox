package com.postbox

import com.postbox.api.ApiException
import com.postbox.api.adminRoutes
import com.postbox.api.authRoutes
import com.postbox.api.meRoutes
import com.postbox.api.parcelRoutes
import com.postbox.api.respondError
import com.postbox.api.setupRoutes
import com.postbox.auth.AuthJwt
import com.postbox.auth.JwtService
import com.postbox.auth.PasswordHasher
import com.postbox.auth.RefreshTokenService
import com.postbox.auth.configureAuth
import com.postbox.carrier.CarrierRegistry
import com.postbox.db.DeviceSessionRepository
import com.postbox.db.Migrations
import com.postbox.db.ParcelRepository
import com.postbox.db.UserRepository
import com.postbox.db.closeDatabase
import com.postbox.service.DetectionService
import com.postbox.service.RefreshService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.DI
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.net.BindException

private val log = LoggerFactory.getLogger("com.postbox.Application")

@Serializable
data class HealthStatus(val status: String)

fun Application.module(config: AppConfig, db: Database) {
    install(ContentNegotiation) {
        json()
    }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respondError(cause.status, cause.code, cause.message)
        }
        exception<BadRequestException> { call, _ ->
            call.respondError(HttpStatusCode.BadRequest, "invalid-request", "The request body could not be parsed")
        }
        exception<ContentTransformationException> { call, _ ->
            call.respondError(HttpStatusCode.BadRequest, "invalid-request", "The request body could not be parsed")
        }
        exception<Throwable> { call, cause ->
            log.error("Unhandled error serving {} {}", call.request.httpMethod.value, call.request.path(), cause)
            call.respondError(HttpStatusCode.InternalServerError, "internal-error", "Internal server error")
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "A valid bearer token is required")
        }
    }
    if (pluginOrNull(DI) == null) {
        install(DI)
    }
    appDependencies(config, db)
    val users: UserRepository by dependencies
    val hasher: PasswordHasher by dependencies
    val jwtService: JwtService by dependencies
    val sessions: DeviceSessionRepository by dependencies
    val refreshTokens: RefreshTokenService by dependencies
    val registry: CarrierRegistry by dependencies
    val parcels: ParcelRepository by dependencies
    val detectionService: DetectionService by dependencies
    val refreshService: RefreshService by dependencies
    configureAuth(db, users, jwtService)
    log.info(
        "Carrier wiring: {} carriers configured, {} adapters registered",
        config.carriers.size, registry.all.size
    )
    routing {
        get("/healthz") {
            call.respond(HealthStatus(status = "ok"))
        }
        staticResources("/docs", "static")
        route("/api/v1") {
            setupRoutes(users, hasher)
            authRoutes(users, hasher, jwtService, refreshTokens)
            authenticate(AuthJwt) {
                meRoutes(users, hasher, sessions)
                adminRoutes(users, hasher, sessions)
                parcelRoutes(parcels, detectionService, refreshService)
            }
        }
        staticResources("/", "web") {
            filter { call ->
                val path = call.request.path()
                path.startsWith("/api") || path.startsWith("/docs") || path == "/healthz"
            }
            default("index.html")
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
            module(config, db)
        }.start(wait = true)
    } catch (e: BindException) {
        log.error("Port {} is already in use — set PORT to a free port and retry", config.port)
        throw e
    }
}
