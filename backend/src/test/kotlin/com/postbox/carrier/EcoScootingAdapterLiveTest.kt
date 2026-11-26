package com.postbox.carrier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Live integration test against Cainiao's public link gateway (the lookup behind
 * EcoScooting's consumer tracking page), verifying the G-62 acceptance criteria with
 * the real sample tracking number from the G-73 research comments. Disabled unless
 * `POSTBOX_ECOSCOOTING_LIVE_TEST=true` is set, so `./gradlew check` never depends on
 * the network: `POSTBOX_ECOSCOOTING_LIVE_TEST=true ./gradlew :backend:test --tests "com.postbox.carrier.EcoScootingAdapterLiveTest"`.
 */
@EnabledIfEnvironmentVariable(named = "POSTBOX_ECOSCOOTING_LIVE_TEST", matches = "true")
class EcoScootingAdapterLiveTest {

    private fun liveAdapter(baseUrl: String = EcoScootingAdapter.DEFAULT_BASE_URL): EcoScootingAdapter {
        val client = createCarrierHttpClient(
            CarrierClientSettings(
                connectTimeoutMs = 10_000,
                socketTimeoutMs = 15_000,
                requestTimeoutMs = 20_000,
                userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            )
        )
        return EcoScootingAdapter(
            client = client,
            rateLimiter = CarrierRateLimiter(minInterval = 1.seconds),
            baseUrl = baseUrl,
        )
    }

    @Test
    fun `real EcoScooting number from the G-73 research returns its timeline`() = runTest {
        val result = liveAdapter().track("CNPRT42500041234024382386", emptyMap())

        assertTrue(result.events.isNotEmpty(), "expected the real number's timeline, got no events")
        val times = result.events.map { it.occurredAt }
        assertEquals(times.sorted(), times, "events must be chronological")
        assertEquals(result.events.last().status, result.status)
        assertTrue(result.events.all { it.description.isNotBlank() }, "original event text must be preserved")
    }

    @Test
    fun `unknown well-formed EcoScooting number returns NotFound`() = runTest {
        val error = assertFailsWith<CarrierError.NotFound> {
            liveAdapter().track("CNPRT99999999999999999999", emptyMap())
        }

        assertEquals("CNPRT99999999999999999999", error.number)
    }

    @Test
    fun `endpoint change to an unreachable host degrades to CarrierUnavailable without crashing`() = runTest {
        assertFailsWith<CarrierError.CarrierUnavailable> {
            liveAdapter(baseUrl = "https://de-link.cainiao.invalid/gateway/link.do")
                .track("CNPRT42500041234024382386", emptyMap())
        }
    }
}
