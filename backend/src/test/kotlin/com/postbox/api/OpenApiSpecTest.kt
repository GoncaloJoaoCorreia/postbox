package com.postbox.api

import com.postbox.AppConfig
import com.postbox.db.closeDatabase
import com.postbox.db.freshTestDatabase
import com.postbox.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenApiSpecTest {

    private fun testConfig() = AppConfig(
        host = "127.0.0.1",
        port = 0, // testApplication runs in-process and does not bind a real port
        jwtSecret = "test-secret",
        sqlitePath = "build/tmp/test/postbox.db",
        dataDir = "build/tmp/test-data",
    )

    @Test
    fun `the OpenAPI spec is served at docs and covers every implemented route`() = testApplication {
        val db = freshTestDatabase("openapi-spec")
        try {
            application { module(testConfig(), db) }

            val response = client.get("/docs/openapi.yaml")
            assertEquals(HttpStatusCode.OK, response.status)
            val spec = response.bodyAsText()
            assertTrue(spec.contains("openapi: 3"), "spec should declare an OpenAPI 3 version")

            val paths = listOf(
                "/api/v1/setup/status",
                "/api/v1/setup",
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/me",
                "/api/v1/me/devices",
                "/api/v1/me/devices/{id}",
                "/api/v1/admin/users",
                "/api/v1/admin/users/{id}",
                "/api/v1/parcels",
                "/api/v1/parcels/detect",
                "/api/v1/parcels/{id}",
                "/api/v1/parcels/{id}/events",
                "/api/v1/parcels/{id}/refresh",
            )
            for (path in paths) {
                assertTrue(spec.contains(path), "served spec is missing path $path")
            }
            assertTrue(spec.contains("lastCheckError"), "served spec should document the soft refresh-failure indicator on Parcel")
            assertTrue(spec.contains("bearerAuth"), "served spec should declare the bearer security scheme")
        } finally {
            closeDatabase(db)
        }
    }
}
