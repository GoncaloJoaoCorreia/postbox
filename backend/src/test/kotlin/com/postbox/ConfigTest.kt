package com.postbox

import com.typesafe.config.ConfigFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigTest {

    @Test
    fun `env vars override HOCON defaults`() {
        val env = ConfigFactory.parseString(
            """
            PORT = "18080"
            JWT_SECRET = "test-secret"
            SQLITE_PATH = "build/tmp/test.db"
            DATA_DIR = "build/tmp/test-data"
            """.trimIndent()
        )
        val config = ConfigLoader.load(env = env)
        assertEquals(18080, config.port)
        assertEquals("test-secret", config.jwtSecret)
        assertEquals("build/tmp/test.db", config.sqlitePath)
        assertEquals("build/tmp/test-data", config.dataDir)
    }

    @Test
    fun `HOCON defaults apply when env is empty`() {
        val config = ConfigLoader.load(env = ConfigFactory.empty())
        assertEquals("0.0.0.0", config.host)
        assertEquals(8080, config.port)
        assertEquals("data/postbox.db", config.sqlitePath)
        assertEquals("data", config.dataDir)
    }
}
