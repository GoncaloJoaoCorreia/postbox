package com.postbox

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.File

/** Runtime configuration of the backend. */
data class AppConfig(
    val host: String,
    val port: Int,
    val jwtSecret: String,
    val sqlitePath: String,
    val dataDir: String,
)

object ConfigLoader {

    /**
     * Loads the HOCON defaults from `application.conf` on the classpath and lets
     * environment variables override them: `application.conf` declares each value
     * as `default` followed by an optional `${?ENV_VAR}` substitution, and the system
     * environment is merged on top, so `PORT`, `JWT_SECRET`, `SQLITE_PATH` and
     * `DATA_DIR` win over the defaults when set.
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
        )
    }

    /** Creates the data directory if it does not exist yet. */
    fun ensureDataDir(config: AppConfig): File = File(config.dataDir).apply { mkdirs() }
}
