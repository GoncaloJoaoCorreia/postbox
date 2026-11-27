package com.postbox.carrier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Live integration test against DPD's real endpoints, verifying the G-60 acceptance
 * criteria. Disabled unless `POSTBOX_DPD_LIVE_TEST=true` is set, so `./gradlew check`
 * never depends on the network:
 * `POSTBOX_DPD_LIVE_TEST=true ./gradlew :backend:test --tests "com.postbox.carrier.DpdAdapterLiveTest"`.
 * The PT tracker needs no credentials; the NL guest API additionally needs the public
 * myDPD app-bundle values in `DPD_FIREBASE_API_KEY` / `DPD_FIREBASE_ANDROID_CERT`.
 */
@EnabledIfEnvironmentVariable(named = "POSTBOX_DPD_LIVE_TEST", matches = "true")
class DpdAdapterLiveTest {

    private fun liveAdapter(ptBaseUrl: String = DpdAdapter.DEFAULT_PT_BASE_URL): DpdAdapter {
        val client = createCarrierHttpClient(
            CarrierClientSettings(
                connectTimeoutMs = 10_000,
                socketTimeoutMs = 15_000,
                requestTimeoutMs = 20_000,
                userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            )
        )
        return DpdAdapter(
            client = client,
            rateLimiter = CarrierRateLimiter(minInterval = 1.seconds),
            ptBaseUrl = ptBaseUrl,
            firebaseApiKey = System.getenv("DPD_FIREBASE_API_KEY"),
            firebaseAndroidCert = System.getenv("DPD_FIREBASE_ANDROID_CERT"),
        )
    }

    @Test
    fun `unknown 14-digit DPD number returns NotFound on the PT tracker, not an error`() = runTest {
        val error = assertFailsWith<CarrierError.NotFound> {
            liveAdapter().track("09990000000404", emptyMap())
        }

        assertEquals("09990000000404", error.number)
    }

    @Test
    fun `unknown 14-digit number returns NotFound on the NL guest API when the firebase values are configured`() = runTest {
        assumeTrue(
            System.getenv("DPD_FIREBASE_API_KEY") != null && System.getenv("DPD_FIREBASE_ANDROID_CERT") != null,
            "needs DPD_FIREBASE_API_KEY and DPD_FIREBASE_ANDROID_CERT",
        )

        assertFailsWith<CarrierError.NotFound> {
            liveAdapter().track("09990000000404", mapOf("country" to "NL"))
        }
    }

    @Test
    fun `endpoint change to an unreachable host degrades to CarrierUnavailable without crashing`() = runTest {
        assertFailsWith<CarrierError.CarrierUnavailable> {
            liveAdapter(ptBaseUrl = "https://tracking.dpd.invalid").track("09550000000101", emptyMap())
        }
    }
}
