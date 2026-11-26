package com.postbox

import com.postbox.carrier.CarrierId
import com.postbox.db.closeDatabase
import com.postbox.db.freshTestDatabase
import com.typesafe.config.ConfigFactory
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CarrierWiringTest {

    @Test
    fun `carrier config defaults parse for all carriers with DHL min interval 5000`() {
        val config = ConfigLoader.load(env = ConfigFactory.empty())
        assertEquals(CarrierId.entries.toSet(), config.carriers.keys)
        assertEquals(5000L, config.carriers.getValue(CarrierId.DHL).minRequestIntervalMs)
        assertEquals(1000L, config.carriers.getValue(CarrierId.CTT).minRequestIntervalMs)
        // The existing scalar keys still parse alongside the carriers block.
        assertEquals(8080, config.port)
        assertEquals("data/postbox.db", config.sqlitePath)
        assertEquals("data", config.dataDir)
    }

    @Test
    fun `carrier env var overrides the HOCON default`() {
        val env = ConfigFactory.parseString(
            "POSTBOX_CARRIER_DHL_MIN_REQUEST_INTERVAL_MS = 1234"
        )
        val config = ConfigLoader.load(env = env)
        assertEquals(1234L, config.carriers.getValue(CarrierId.DHL).minRequestIntervalMs)
        // Other carriers keep their defaults.
        assertEquals(1000L, config.carriers.getValue(CarrierId.DPD).minRequestIntervalMs)
    }

    @Test
    fun `module exposes the carrier registry via attributes and healthz still works`() = testApplication {
        val db = freshTestDatabase("carrier-wiring")
        try {
            application {
                module(
                    AppConfig(
                        host = "127.0.0.1",
                        port = 0, // testApplication runs in-process and does not bind a real port
                        jwtSecret = "test-secret",
                        sqlitePath = "build/tmp/test/postbox.db",
                        dataDir = "build/tmp/test-data",
                        carriers = mapOf(
                            CarrierId.DHL to CarrierSettings(
                                connectTimeoutMs = 5000,
                                socketTimeoutMs = 10000,
                                requestTimeoutMs = 10000,
                                userAgent = "postbox-test/1.0",
                                minRequestIntervalMs = 5000,
                            )
                        ),
                    ),
                    db,
                )
            }
            // Ktor's testApplication starts the app lazily (on the first client call
            // or via startApplication()); the module — and with it the attributes —
            // only exists once the application has started.
            startApplication()
            // testApplication never raises ApplicationStopping, so the per-carrier
            // clients must be closed here — in a finally, so a failed assertion
            // cannot leak the CIO engine for the rest of the test JVM.
            val httpClients = application.attributes[CarrierHttpClientsAttribute]
            try {
                val registry = application.attributes[CarrierRegistryAttribute]
                assertNotNull(registry)
                // No real carrier adapters are registered yet.
                assertNull(registry.lookup(CarrierId.DHL))
                assertTrue(registry.all.isEmpty())
                // The per-carrier clients and limiters were built from the same config entry.
                assertEquals(setOf(CarrierId.DHL), httpClients.carriers)
                assertEquals(setOf(CarrierId.DHL), application.attributes[CarrierRateLimitersAttribute].keys)

                val response = client.get("/healthz")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("""{"status":"ok"}""", response.bodyAsText())
            } finally {
                httpClients.close()
            }
        } finally {
            closeDatabase(db)
        }
    }

    @Test
    fun `DHL rate limiter is built from the configured 5000 ms interval`() = runTest {
        val dhl = ConfigLoader.load(env = ConfigFactory.empty()).carriers.getValue(CarrierId.DHL)
        // IS-003: the interval comes from config, not a hardcoded constant. The virtual-time
        // spacing behavior itself stays owned by CarrierRateLimiterTest.
        assertEquals(5000L, dhl.minRequestIntervalMs)
        dhl.toRateLimiter().acquire() // same construction the module wiring performs
    }
}
