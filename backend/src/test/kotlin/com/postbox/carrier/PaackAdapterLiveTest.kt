package com.postbox.carrier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Live integration test against Paack's real consumer tracking endpoint, verifying the
 * G-61 acceptance criteria with the real sample tracking number from the G-72 research
 * comments (`534000021268935007`, own parcel, delivered 2026-11-23). Disabled unless
 * `POSTBOX_PAACK_LIVE_TEST=true` is set, so `./gradlew check` never depends on the
 * network: `POSTBOX_PAACK_LIVE_TEST=true ./gradlew :backend:test --tests "com.postbox.carrier.PaackAdapterLiveTest"`.
 */
@EnabledIfEnvironmentVariable(named = "POSTBOX_PAACK_LIVE_TEST", matches = "true")
class PaackAdapterLiveTest {

    private fun liveAdapter(): PaackAdapter {
        val client = createCarrierHttpClient(
            CarrierClientSettings(
                connectTimeoutMs = 10_000,
                socketTimeoutMs = 15_000,
                requestTimeoutMs = 20_000,
                userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            )
        )
        return PaackAdapter(client = client, rateLimiter = CarrierRateLimiter(minInterval = 1.seconds))
    }

    @Test
    fun `real Paack number 534000021268935007 with its postcode returns the event timeline`() = runTest {
        val result = liveAdapter().track("534000021268935007", mapOf("postcode" to "4250-004"))

        assertEquals(TrackingStatus.DELIVERED, result.status)
        assertTrue(result.events.size >= 4, "expected the event timeline, got ${result.events.size} events")
        val times = result.events.map { it.occurredAt }
        assertEquals(times.sorted(), times, "events must be chronological")
        assertEquals(TrackingStatus.DELIVERED, result.events.last().status)
        assertTrue(result.events.last().description.isNotBlank())
    }

    @Test
    fun `unknown Paack number returns NotFound`() = runTest {
        val error = assertFailsWith<CarrierError.NotFound> {
            liveAdapter().track("XX000000000000", mapOf("postcode" to "28001"))
        }

        assertEquals("XX000000000000", error.number)
    }

    @Test
    fun `real number with a wrong postcode also returns NotFound`() = runTest {
        assertFailsWith<CarrierError.NotFound> {
            liveAdapter().track("534000021268935007", mapOf("postcode" to "28001"))
        }
    }
}
