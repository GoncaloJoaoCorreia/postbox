package com.postbox

import com.postbox.db.closeDatabase
import com.postbox.db.freshTestDatabase
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthzTest {

    private fun testConfig() = AppConfig(
        host = "127.0.0.1",
        port = 0, // testApplication runs in-process and does not bind a real port
        jwtSecret = "test-secret",
        sqlitePath = "build/tmp/test/postbox.db",
        dataDir = "build/tmp/test-data",
    )

    @Test
    fun `GET healthz returns 200 with status ok body`() = testApplication {
        val db = freshTestDatabase("healthz")
        try {
            application {
                module(testConfig(), db)
            }
            val response = client.get("/healthz")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"status":"ok"}""", response.bodyAsText())
        } finally {
            closeDatabase(db)
        }
    }
}
