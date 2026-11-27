package com.postbox

import com.typesafe.config.ConfigFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
        assertEquals("dev", config.env)
        assertEquals(30.minutes, config.refresh.staleAfter)
        assertEquals(4, config.refresh.maxConcurrentProbes)
        assertEquals(3.days, config.refresh.deliveredGrace)
        assertNull(config.dhlApiKey)
    }

    @Test
    fun `DHL_API_KEY env var sets the DHL API key`() {
        val env = ConfigFactory.parseString("""DHL_API_KEY = "test-dhl-key"""")
        val config = ConfigLoader.load(env = env)
        assertEquals("test-dhl-key", config.dhlApiKey)
    }

    @Test
    fun `production env with the default JWT secret refuses to boot`() {
        val env = ConfigFactory.parseString("""POSTBOX_ENV = "production"""")
        assertFailsWith<IllegalStateException> { ConfigLoader.load(env = env) }
    }

    @Test
    fun `production env with an explicit JWT_SECRET loads`() {
        val env = ConfigFactory.parseString(
            """
            POSTBOX_ENV = "production"
            JWT_SECRET = "strong-random-secret"
            """.trimIndent()
        )
        val config = ConfigLoader.load(env = env)
        assertEquals("production", config.env)
        assertEquals("strong-random-secret", config.jwtSecret)
    }

    @Test
    fun `shipped default in application conf matches DEFAULT_JWT_SECRET`() {
        val defaults = ConfigFactory.parseResources("application.conf").resolve()
        assertEquals(DEFAULT_JWT_SECRET, defaults.getString("postbox.jwt.secret"))
    }
}
