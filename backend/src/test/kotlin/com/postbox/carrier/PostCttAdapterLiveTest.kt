package com.postbox.carrier

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * Live integration test against CTT's real public endpoint, verifying the G-58 acceptance
 * criteria with the real sample tracking number from the G-69 research comments. Disabled
 * unless `POSTBOX_CTT_LIVE_TEST=true` is set, so `./gradlew check` never depends on the
 * network: `POSTBOX_CTT_LIVE_TEST=true ./gradlew :backend:test --tests "com.postbox.carrier.PostCttAdapterLiveTest"`.
 */
@EnabledIfEnvironmentVariable(named = "POSTBOX_CTT_LIVE_TEST", matches = "true")
class PostCttAdapterLiveTest {

    private fun liveAdapter(): PostCttAdapter {
        val client = createCarrierHttpClient(
            CarrierClientSettings(
                connectTimeoutMs = 10_000,
                socketTimeoutMs = 15_000,
                requestTimeoutMs = 20_000,
                userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            )
        )
        return PostCttAdapter(client = client, rateLimiter = CarrierRateLimiter(minInterval = 1.seconds))
    }

    @Test
    fun `real CTT S10 number DD506882333PT returns its full event history`() = runTest {
        val result = liveAdapter().track("DD506882333PT", emptyMap())

        assertEquals(TrackingStatus.DELIVERED, result.status)
        assertTrue(result.events.size >= 2, "expected the full event history, got ${result.events.size} events")
        val times = result.events.map { it.occurredAt }
        assertEquals(times.sorted(), times, "events must be chronological")
        assertEquals(TrackingStatus.DELIVERED, result.events.last().status)
        assertTrue(result.events.last().description.contains("entregue"))
    }

    @Test
    fun `bogus but well-formed CTT number RR001000007PT returns NotFound`() = runTest {
        val error = assertFailsWith<CarrierError.NotFound> {
            liveAdapter().track("RR001000007PT", emptyMap())
        }

        assertEquals("RR001000007PT", error.number)
    }
}
