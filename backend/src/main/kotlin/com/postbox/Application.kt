package com.postbox

import com.postbox.api.adminRoutes
import com.postbox.api.authRoutes
import com.postbox.api.meRoutes
import com.postbox.api.setupRoutes
import com.postbox.auth.AuthJwt
import com.postbox.auth.JwtService
import com.postbox.auth.PasswordHasher
import com.postbox.auth.configureAuth
import com.postbox.carrier.CarrierRegistry
import com.postbox.db.Migrations
import com.postbox.db.UserRepository
import com.postbox.db.closeDatabase
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.DI
import io.ktor.server.plugins.di.dependencies
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
    if (pluginOrNull(DI) == null) {
        install(DI)
    }
    appDependencies(config, db)
    val users: UserRepository by dependencies
    val hasher: PasswordHasher by dependencies
    val jwtService: JwtService by dependencies
    val registry: CarrierRegistry by dependencies
    configureAuth(db, users, jwtService)
    log.info(
        "Carrier wiring: {} carriers configured, {} adapters registered",
        config.carriers.size, registry.all.size
    )
    routing {
        get("/healthz") {
            call.respond(HealthStatus(status = "ok"))
        }
        route("/api/v1") {
            setupRoutes(users, hasher)
            authRoutes(users, hasher, jwtService)
            authenticate(AuthJwt) {
                meRoutes(users, hasher)
                adminRoutes(users, hasher)
            }
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
