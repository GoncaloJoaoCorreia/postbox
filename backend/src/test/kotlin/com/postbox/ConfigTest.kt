package com.postbox

import com.typesafe.config.ConfigFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class ConfigTest {

    @Test
    fun `env vars override HOCON defaults`() {
        val env = ConfigFactory.parseString(
            """
            PORT = "18080"
            JWT_SECRET = "test-secret"
            SQLITE_PATH = "build/tmp/test.db"
            DATA_DIR = "build/tmp/test-data"
            POSTBOX_REFRESH_STALE_AFTER_MINUTES = "5"
            POSTBOX_REFRESH_MAX_CONCURRENT_PROBES = "2"
            POSTBOX_REFRESH_DELIVERED_GRACE_DAYS = "7"
            """.trimIndent()
        )
        val config = ConfigLoader.load(env = env)
        assertEquals(18080, config.port)
        assertEquals("test-secret", config.jwtSecret)
        assertEquals("build/tmp/test.db", config.sqlitePath)
        assertEquals("build/tmp/test-data", config.dataDir)
        assertEquals(5.minutes, config.refresh.staleAfter)
        assertEquals(2, config.refresh.maxConcurrentProbes)
        assertEquals(7.days, config.refresh.deliveredGrace)
    }

    @Test
    fun `HOCON defaults apply when env is empty`() {
        val config = ConfigLoader.load(env = ConfigFactory.empty())
        assertEquals("0.0.0.0", config.host)
        assertEquals(8080, config.port)
        assertEquals("data/postbox.db", config.sqlitePath)
        assertEquals("data", config.dataDir)
        assertEquals(30.minutes, config.refresh.staleAfter)
        assertEquals(4, config.refresh.maxConcurrentProbes)
        assertEquals(3.days, config.refresh.deliveredGrace)
    }
}
