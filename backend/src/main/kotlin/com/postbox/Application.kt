package com.postbox

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.BindException

private val log = LoggerFactory.getLogger("com.postbox.Application")

@Serializable
data class HealthStatus(val status: String)

fun Application.module(config: AppConfig) {
    install(ContentNegotiation) {
        json()
    }
    routing {
        get("/healthz") {
            call.respond(HealthStatus(status = "ok"))
        }
    }
}

fun main() {
    val config = ConfigLoader.load()
    ConfigLoader.ensureDataDir(config)
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
