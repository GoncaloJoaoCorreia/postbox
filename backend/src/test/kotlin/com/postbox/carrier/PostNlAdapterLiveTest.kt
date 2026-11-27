package com.postbox.carrier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Live integration test against PostNL's real consumer track-and-trace endpoint,
 * verifying the G-59 acceptance criteria with the real sample tracking number from
 * the G-70 research comments (`3SDKVX1477340`, own parcel, delivered 2026-11-17,
 * PT postcode). Disabled unless `POSTBOX_POSTNL_LIVE_TEST=true` is set, so
 * `./gradlew check` never depends on the network:
 * `POSTBOX_POSTNL_LIVE_TEST=true ./gradlew :backend:test --tests "com.postbox.carrier.PostNlAdapterLiveTest"`.
 */
@EnabledIfEnvironmentVariable(named = "POSTBOX_POSTNL_LIVE_TEST", matches = "true")
class PostNlAdapterLiveTest {

    private fun liveAdapter(baseUrl: String = PostNlAdapter.DEFAULT_BASE_URL): PostNlAdapter {
        val client = createCarrierHttpClient(
            CarrierClientSettings(
                connectTimeoutMs = 10_000,
                socketTimeoutMs = 15_000,
                requestTimeoutMs = 20_000,
                userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            )
        )
        return PostNlAdapter(client = client, rateLimiter = CarrierRateLimiter(minInterval = 1.seconds), baseUrl = baseUrl)
    }

    @Test
    fun `real 3S number with its postcode returns the full timeline ending in DELIVERED`() = runTest {
        val result = liveAdapter().track("3SDKVX1477340", mapOf("postcode" to "4250-004"))

        assertEquals(TrackingStatus.DELIVERED, result.status)
        assertTrue(result.events.size >= 4, "expected the full timeline, got ${result.events.size} events")
        val times = result.events.map { it.occurredAt }
        assertEquals(times.sorted(), times, "events must be chronological")
        assertEquals(TrackingStatus.DELIVERED, result.events.last().status)
        assertTrue(result.events.last().description.isNotBlank(), "original event text must be preserved")
    }

    @Test
    fun `the same number without postcode triggers the structured metadata request`() = runTest {
        val error = assertFailsWith<CarrierError.NeedsMetadata> {
            liveAdapter().track("3SDKVX1477340", emptyMap())
        }

        assertEquals(listOf("postcode"), error.fields.map { it.name })
    }

    @Test
    fun `unknown 3S number returns NotFound`() = runTest {
        val error = assertFailsWith<CarrierError.NotFound> {
            liveAdapter().track("3SXXXX00000000", mapOf("postcode" to "4250-004"))
        }

        assertEquals("3SXXXX00000000", error.number)
    }

    @Test
    fun `known number with a wrong postcode returns the same NotFound`() = runTest {
        assertFailsWith<CarrierError.NotFound> {
            liveAdapter().track("3SDKVX1477340", mapOf("postcode" to "1234AB"))
        }
    }

    @Test
    fun `endpoint change to an unreachable host degrades to CarrierUnavailable without crashing`() = runTest {
        assertFailsWith<CarrierError.CarrierUnavailable> {
            liveAdapter(baseUrl = "https://tracking.postnl.invalid")
                .track("3SDKVX1477340", mapOf("postcode" to "4250-004"))
        }
    }
}
