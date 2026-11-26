package com.postbox

import com.postbox.carrier.CarrierAdapter
import com.postbox.carrier.CarrierHttpClients
import com.postbox.carrier.CarrierId
import com.postbox.carrier.CarrierRateLimiter
import com.postbox.carrier.CarrierRegistry
import com.postbox.carrier.DhlAdapter
import com.postbox.carrier.EcoScootingAdapter
import com.postbox.carrier.FakeCarrier
import com.postbox.carrier.PostCttAdapter
import com.postbox.db.closeDatabase
import com.postbox.db.freshTestDatabase
import com.postbox.service.DetectionService
import com.postbox.service.RuleBasedDetectionService
import com.typesafe.config.ConfigFactory
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.plugins.di.resolve
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CarrierWiringTest {

    private fun testConfig(
        dhlApiKey: String? = null,
        carriers: Set<CarrierId> = setOf(CarrierId.DHL),
    ) = AppConfig(
        host = "127.0.0.1",
        port = 0, // testApplication runs in-process and does not bind a real port
        jwtSecret = "test-secret",
        sqlitePath = "build/tmp/test/postbox.db",
        dataDir = "build/tmp/test-data",
        dhlApiKey = dhlApiKey,
        carriers = carriers.associateWith {
            CarrierSettings(
                connectTimeoutMs = 5000,
                socketTimeoutMs = 10000,
                requestTimeoutMs = 10000,
                userAgent = "postbox-test/1.0",
                minRequestIntervalMs = 5000,
            )
        },
    )

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
    fun `module resolves carrier components from DI and healthz still works`() = testApplication {
        val db = freshTestDatabase("carrier-wiring")
        try {
            application {
                module(testConfig(), db)
            }
            // Ktor's testApplication starts the app lazily (on the first client call
            // or via startApplication()); the module — and with it the DI registry —
            // only exists once the application has started.
            startApplication()
            // testApplication never raises ApplicationStopping, so the per-carrier
            // clients must be closed here — in a finally, so a failed assertion
            // cannot leak the CIO engine for the rest of the test JVM.
            val httpClients = application.dependencies.resolve<CarrierHttpClients>()
            try {
                val registry = application.dependencies.resolve<CarrierRegistry>()
                // No real carrier adapters are registered yet.
                assertNull(registry.lookup(CarrierId.DHL))
                assertTrue(registry.all.isEmpty())
                // The per-carrier clients and limiters were built from the same config entry.
                assertEquals(setOf(CarrierId.DHL), httpClients.carriers)
                assertEquals(
                    setOf(CarrierId.DHL),
                    application.dependencies.resolve<Map<CarrierId, CarrierRateLimiter>>().keys,
                )

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
    fun `detection service resolves from DI as the rules-plus-probe implementation`() = testApplication {
        val db = freshTestDatabase("carrier-wiring-detection")
        try {
            application {
                module(testConfig(), db)
            }
            startApplication()
            val httpClients = application.dependencies.resolve<CarrierHttpClients>()
            try {
                val detection = application.dependencies.resolve<DetectionService>()
                assertTrue(detection is RuleBasedDetectionService)
            } finally {
                httpClients.close()
            }
        } finally {
            closeDatabase(db)
        }
    }

    @Test
    fun `a named carrier adapter provided before the module lands in the registry`() = testApplication {
        val db = freshTestDatabase("carrier-wiring-override")
        val number = "1Z999AA10123456784"
        val expected = FakeCarrier.deterministicResult(number)
        val fake = FakeCarrier(CarrierId.DHL, results = mapOf(number to expected))
        try {
            application {
                dependencies {
                    provide<CarrierAdapter>(name = CarrierId.DHL.id) { fake }
                }
                module(testConfig(), db)
            }
            startApplication()
            val registry = application.dependencies.resolve<CarrierRegistry>()
            assertSame(fake, registry.lookup(CarrierId.DHL))
            assertSame(expected, registry.lookup(CarrierId.DHL)?.track(number, emptyMap()))
        } finally {
            closeDatabase(db)
        }
    }

    @Test
    fun `DHL adapter registers in the carrier registry when DHL_API_KEY is set`() = testApplication {
        val db = freshTestDatabase("carrier-wiring-dhl-key")
        try {
            application {
                module(testConfig(dhlApiKey = "test-dhl-key"), db)
            }
            startApplication()
            val httpClients = application.dependencies.resolve<CarrierHttpClients>()
            try {
                val registry = application.dependencies.resolve<CarrierRegistry>()
                val dhl = registry.lookup(CarrierId.DHL)
                assertIs<DhlAdapter>(dhl)
                assertEquals(CarrierId.DHL, dhl?.carrier)
                assertEquals(emptyMap(), dhl?.requiredMetadata)
                assertEquals(listOf(dhl), registry.all)
            } finally {
                httpClients.close()
            }
        } finally {
            closeDatabase(db)
        }
    }

    @Test
    fun `CTT adapter registers in the carrier registry when its carrier config block is present`() = testApplication {
        val db = freshTestDatabase("carrier-wiring-ctt")
        try {
            application {
                module(testConfig(carriers = setOf(CarrierId.CTT)), db)
            }
            startApplication()
            val httpClients = application.dependencies.resolve<CarrierHttpClients>()
            try {
                val registry = application.dependencies.resolve<CarrierRegistry>()
                val ctt = registry.lookup(CarrierId.CTT)
                assertIs<PostCttAdapter>(ctt)
                assertEquals(CarrierId.CTT, ctt?.carrier)
                assertEquals(emptyMap(), ctt?.requiredMetadata)
                assertEquals(listOf(ctt), registry.all)
            } finally {
                httpClients.close()
            }
        } finally {
            closeDatabase(db)
        }
    }

    @Test
    fun `EcoScooting adapter registers in the carrier registry when its carrier config block is present`() = testApplication {
        val db = freshTestDatabase("carrier-wiring-ecoscooting")
        try {
            application {
                module(testConfig(carriers = setOf(CarrierId.ECOSCOOTING)), db)
            }
            startApplication()
            val httpClients = application.dependencies.resolve<CarrierHttpClients>()
            try {
                val registry = application.dependencies.resolve<CarrierRegistry>()
                val ecoscooting = registry.lookup(CarrierId.ECOSCOOTING)
                assertIs<EcoScootingAdapter>(ecoscooting)
                assertEquals(CarrierId.ECOSCOOTING, ecoscooting?.carrier)
                assertEquals(emptyMap(), ecoscooting?.requiredMetadata)
                assertEquals(listOf(ecoscooting), registry.all)
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
        dhl.toRateLimiter().acquire() // same construction the DI wiring performs
    }
}
