package com.postbox

import com.postbox.carrier.CarrierId
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.File

/** Per-carrier settings parsed from the `postbox.carriers.<id>` block. */
data class CarrierSettings(
    val connectTimeoutMs: Long,
    val socketTimeoutMs: Long,
    val requestTimeoutMs: Long,
    val userAgent: String,
    val minRequestIntervalMs: Long,
)

/** Runtime configuration of the backend. */
data class AppConfig(
    val host: String,
    val port: Int,
    val jwtSecret: String,
    val sqlitePath: String,
    val dataDir: String,
    val carriers: Map<CarrierId, CarrierSettings> = emptyMap(),
)

object ConfigLoader {

    /**
     * Loads the HOCON defaults from `application.conf` on the classpath and lets
     * environment variables override them: `application.conf` declares each value
     * as `default` followed by an optional `${?ENV_VAR}` substitution, and the system
     * environment is merged on top, so `PORT`, `JWT_SECRET`, `SQLITE_PATH`,
     * `DATA_DIR` and `POSTBOX_CARRIER_<ID>_<KEY>` win over the defaults when set.
     */
    fun load(
        env: Config = ConfigFactory.systemEnvironment(),
        defaults: Config = ConfigFactory.parseResources("application.conf"),
    ): AppConfig {
        val config = env.withFallback(defaults).resolve()
        return AppConfig(
            host = config.getString("postbox.http.host"),
            port = config.getInt("postbox.http.port"),
            jwtSecret = config.getString("postbox.jwt.secret"),
            sqlitePath = config.getString("postbox.storage.sqlitePath"),
            dataDir = config.getString("postbox.storage.dataDir"),
            // Driven by the CarrierId enum, not by config keys, so a typo'd carrier
            // key can never create a phantom carrier.
            carriers = CarrierId.entries
                .filter { config.hasPath("postbox.carriers.${it.id}") }
                .associateWith { carrier ->
                    val carrierConfig = config.getConfig("postbox.carriers.${carrier.id}")
                    CarrierSettings(
                        connectTimeoutMs = carrierConfig.getLong("connectTimeoutMs"),
                        socketTimeoutMs = carrierConfig.getLong("socketTimeoutMs"),
                        requestTimeoutMs = carrierConfig.getLong("requestTimeoutMs"),
                        userAgent = carrierConfig.getString("userAgent"),
                        minRequestIntervalMs = carrierConfig.getLong("minRequestIntervalMs"),
                    )
                },
        )
    }

    /** Creates the data directory if it does not exist yet. */
    fun ensureDataDir(config: AppConfig): File = File(config.dataDir).apply { mkdirs() }
}
