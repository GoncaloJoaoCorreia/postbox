package com.postbox

import com.postbox.db.closeDatabase
import com.postbox.db.freshTestDatabase
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebDistTest {

    private fun testConfig() = AppConfig(
        host = "127.0.0.1",
        port = 0,
        jwtSecret = "test-secret",
        sqlitePath = "build/tmp/test/postbox-webdist.db",
        dataDir = "build/tmp/test-data",
    )

    @Test
    fun `the SPA build is served at the root with history-mode fallback to index html`() = testApplication {
        val db = freshTestDatabase("web-dist")
        try {
            application { module(testConfig(), db) }

            val root = client.get("/")
            assertEquals(HttpStatusCode.OK, root.status)
            assertTrue(root.bodyAsText().contains("test-spa-root"))

            val deepLink = client.get("/parcels/123e4567-e89b-12d3-a456-426614174000")
            assertEquals(HttpStatusCode.OK, deepLink.status)
            assertEquals(root.bodyAsText(), deepLink.bodyAsText())

            val asset = client.get("/assets/app.js")
            assertEquals(HttpStatusCode.OK, asset.status)
            assertTrue(asset.bodyAsText().contains("test-spa-asset"))
        } finally {
            closeDatabase(db)
        }
    }

    @Test
    fun `api docs and healthz paths are never answered with the SPA fallback`() = testApplication {
        val db = freshTestDatabase("web-dist-excluded")
        try {
            application { module(testConfig(), db) }

            val apiMiss = client.get("/api/v1/no-such-route")
            assertEquals(HttpStatusCode.NotFound, apiMiss.status)
            assertFalse(apiMiss.bodyAsText().contains("test-spa-root"))

            val healthz = client.get("/healthz")
            assertEquals(HttpStatusCode.OK, healthz.status)
            assertEquals("""{"status":"ok"}""", healthz.bodyAsText())
        } finally {
            closeDatabase(db)
        }
    }
}
