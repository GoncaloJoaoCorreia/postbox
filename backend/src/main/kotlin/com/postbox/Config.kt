package com.postbox

import com.postbox.carrier.CarrierId
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** Per-carrier settings parsed from the `postbox.carriers.<id>` block. */
data class CarrierSettings(
    val connectTimeoutMs: Long,
    val socketTimeoutMs: Long,
    val requestTimeoutMs: Long,
    val userAgent: String,
    val minRequestIntervalMs: Long,
)

/** Auth settings parsed from the `postbox.auth` block. */
data class AuthSettings(
    val refreshTokenTtl: Duration = 90.days,
)

/** On-demand refresh tuning parsed from the `postbox.refresh` block. */
data class RefreshSettings(
    val staleAfter: Duration,
    val maxConcurrentProbes: Int,
    val deliveredGrace: Duration,
) {
    init {
        require(staleAfter > Duration.ZERO) { "staleAfter must be positive, was $staleAfter" }
        require(maxConcurrentProbes >= 1) { "maxConcurrentProbes must be >= 1, was $maxConcurrentProbes" }
        require(deliveredGrace >= Duration.ZERO) { "deliveredGrace must be >= 0, was $deliveredGrace" }
    }
}

const val DEFAULT_JWT_SECRET = "dev-only-insecure-secret-change-me"

/** Runtime configuration of the backend. */
data class AppConfig(
    val host: String,
    val port: Int,
    val jwtSecret: String,
    val sqlitePath: String,
    val dataDir: String,
    val env: String = "dev",
    val carriers: Map<CarrierId, CarrierSettings> = emptyMap(),
    val dhlApiKey: String? = null,
    val dpdFirebaseApiKey: String? = null,
    val dpdFirebaseAndroidCert: String? = null,
    val refresh: RefreshSettings = RefreshSettings(
        staleAfter = 30.minutes,
        maxConcurrentProbes = 4,
        deliveredGrace = 3.days,
    ),
    val auth: AuthSettings = AuthSettings(),
) {
    init {
        if (env == "production" && jwtSecret == DEFAULT_JWT_SECRET) {
            throw IllegalStateException(
                "POSTBOX_ENV=production requires an explicit JWT_SECRET — refusing to boot with the shipped dev-only default"
            )
        }
    }
}

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
            env = config.getString("postbox.env"),
            dhlApiKey = if (config.hasPath("postbox.dhl.apiKey")) config.getString("postbox.dhl.apiKey") else null,
            dpdFirebaseApiKey = if (config.hasPath("postbox.dpd.firebaseApiKey")) config.getString("postbox.dpd.firebaseApiKey") else null,
            dpdFirebaseAndroidCert = if (config.hasPath("postbox.dpd.firebaseAndroidCert")) config.getString("postbox.dpd.firebaseAndroidCert") else null,
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
            refresh = RefreshSettings(
                staleAfter = config.getInt("postbox.refresh.staleAfterMinutes").minutes,
                maxConcurrentProbes = config.getInt("postbox.refresh.maxConcurrentProbes"),
                deliveredGrace = config.getInt("postbox.refresh.deliveredGraceDays").days,
            ),
            auth = AuthSettings(
                refreshTokenTtl = config.getInt("postbox.auth.refreshTokenTtlDays").days,
            ),
        )
    }

    /** Creates the data directory and the SQLite file's parent directory if they do not exist yet. */
    fun ensureDataDir(config: AppConfig): File {
        File(config.sqlitePath).parentFile?.mkdirs()
        return File(config.dataDir).apply { mkdirs() }
    }
}
