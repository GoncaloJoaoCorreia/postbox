package com.postbox.service

import com.postbox.RefreshSettings
import com.postbox.carrier.CarrierAdapter
import com.postbox.carrier.CarrierError
import com.postbox.carrier.CarrierId
import com.postbox.carrier.CarrierRegistry
import com.postbox.carrier.FakeCarrier
import com.postbox.carrier.MetadataField
import com.postbox.carrier.TrackingEvent
import com.postbox.carrier.TrackingResult
import com.postbox.carrier.TrackingStatus
import com.postbox.db.ParcelRepository
import com.postbox.db.UserRepository
import com.postbox.db.closeDatabase
import com.postbox.db.freshTestDatabase
import com.postbox.db.isoUtc
import com.postbox.carrier.CarrierRateLimiter
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class RefreshServiceTest {

    private class ConcurrencyTrackingAdapter(
        override val carrier: CarrierId,
    ) : CarrierAdapter {
        override val requiredMetadata: Map<String, MetadataField> = emptyMap()
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val calls = AtomicInteger(0)

        override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult {
            calls.incrementAndGet()
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { max -> maxOf(max, now) }
            delay(100)
            inFlight.decrementAndGet()
            return FakeCarrier.deterministicResult(number)
        }
    }

    private class RateLimitedAdapter(
        override val carrier: CarrierId,
        private val retryAfter: Duration,
    ) : CarrierAdapter {
        override val requiredMetadata: Map<String, MetadataField> = emptyMap()

        override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult =
            throw CarrierError.RateLimited(retryAfter)
    }

    /** Manually advanced clock so the test drives time explicitly instead of sleeping in real time. */
    private class ManualClock(var now: kotlin.time.Instant) : Clock {
        override fun now(): kotlin.time.Instant = now
    }

    @Test
    fun `in-flight carrier probes are capped at maxConcurrentProbes while every stale parcel still refreshes`() = runTest {
        val db = freshTestDatabase("refresh-concurrency-cap")
        try {
            val users = UserRepository(db)
            val parcels = ParcelRepository(db)
            val user = users.create("alice@example.com", "hash-1", displayName = "Alice")
            val adapter = ConcurrencyTrackingAdapter(CarrierId.DHL)
            val service = DefaultRefreshService(
                parcels = parcels,
                registry = CarrierRegistry(mapOf(CarrierId.DHL to adapter)),
                rateLimiters = emptyMap(),
                settings = RefreshSettings(
                    staleAfter = 30.minutes,
                    maxConcurrentProbes = 4,
                    deliveredGrace = 3.days,
                ),
                probeTimeouts = mapOf(CarrierId.DHL to 10.seconds),
            )
            val owned = (1..6).map { i ->
                parcels.createParcel(user.id, "DHL-$i", "P$i", "UNKNOWN", carrier = "dhl")
            }

            val refreshed = service.refreshStale(owned)

            assertEquals(6, refreshed.size)
            assertTrue(refreshed.all { it.parcel.lastCheckedAt != null }, "every stale parcel must be refreshed")
            assertEquals(6, adapter.calls.get())
            assertTrue(
                adapter.maxInFlight.get() <= 4,
                "at most maxConcurrentProbes probes may be in flight, got ${adapter.maxInFlight.get()}",
            )
            assertTrue(
                adapter.maxInFlight.get() > 1,
                "probes should still run concurrently, got ${adapter.maxInFlight.get()}",
            )
            assertTrue(refreshed.all { it.parcel.lastCheckError == null }, "successful refreshes clear the soft error indicator")
            assertTrue(refreshed.all { it.missingMetadata.isEmpty() })
        } finally {
            closeDatabase(db)
        }
    }

    @Test
    fun `a carrier needing missing metadata surfaces the fields without counting a check, and refresh succeeds once the metadata is present`() = runTest {
        val db = freshTestDatabase("refresh-needs-metadata")
        try {
            val users = UserRepository(db)
            val parcels = ParcelRepository(db)
            val user = users.create("alice@example.com", "hash-1", displayName = "Alice")
            val field = MetadataField("postcode", "Recipient postcode", "The 4 digits of your postcode", "\\d{4}")
            val number = "3SPOSTNL00123456"
            val adapter = FakeCarrier(
                CarrierId.POSTNL,
                requiredMetadata = mapOf("postcode" to field),
                results = mapOf(number to FakeCarrier.deterministicResult(number)),
            )
            val service = DefaultRefreshService(
                parcels = parcels,
                registry = CarrierRegistry(mapOf(CarrierId.POSTNL to adapter)),
                rateLimiters = emptyMap(),
                settings = RefreshSettings(
                    staleAfter = 30.minutes,
                    maxConcurrentProbes = 4,
                    deliveredGrace = 3.days,
                ),
                probeTimeouts = mapOf(CarrierId.POSTNL to 10.seconds),
            )
            val parcel = parcels.createParcel(user.id, number, "P", "UNKNOWN", carrier = "postnl")

            val outcomes = service.refreshStale(listOf(parcel))
            assertEquals(1, outcomes.size)
            assertEquals(listOf(field), outcomes[0].missingMetadata)
            assertNull(outcomes[0].parcel.lastCheckedAt, "a missing-metadata probe is not a check")
            assertNull(outcomes[0].parcel.lastCheckError, "missing metadata is not an outage")

            val error = assertFailsWith<CarrierError.NeedsMetadata> { service.refresh(user.id, parcel.id) }
            assertEquals(listOf(field), error.fields)

            parcels.updateParcelForUser(
                userId = user.id,
                parcelId = parcel.id,
                displayName = "P",
                status = "UNKNOWN",
                carrier = "postnl",
                detectionMethod = "override",
                carrierMetadata = """{"postcode":"1234"}""",
                lastCheckedAt = null,
                archivedAt = null,
                lastCheckError = null,
                lastCheckErrorCode = null,
                rateLimitedUntil = null,
                estimatedDeliveryAt = null,
            )
            val refreshed = service.refresh(user.id, parcel.id)
            assertEquals(FakeCarrier.deterministicResult(number).status.name, refreshed.status)
            assertNotNull(refreshed.lastCheckedAt)
            assertNull(refreshed.lastCheckError)
        } finally {
            closeDatabase(db)
        }
    }

    @Test
    fun `a successful refresh persists the carrier-provided ETA and a later carrier answer without one clears it`() = runTest {
        val db = freshTestDatabase("refresh-eta")
        try {
            val users = UserRepository(db)
            val parcels = ParcelRepository(db)
            val user = users.create("alice@example.com", "hash-1", displayName = "Alice")
            val number = "1234567890"
            val withEta = FakeCarrier.deterministicResult(number)
            val withoutEta = TrackingResult(
                status = TrackingStatus.IN_TRANSIT,
                eta = null,
                carrierService = null,
                events = listOf(
                    TrackingEvent(Instant.parse("2026-01-01T00:00:00Z"), TrackingStatus.IN_TRANSIT, "In transit", null),
                ),
            )
            fun serviceWith(result: TrackingResult) = DefaultRefreshService(
                parcels = parcels,
                registry = CarrierRegistry(
                    mapOf(CarrierId.DHL to FakeCarrier(CarrierId.DHL, results = mapOf(number to result))),
                ),
                rateLimiters = emptyMap(),
                settings = RefreshSettings(
                    staleAfter = 30.minutes,
                    maxConcurrentProbes = 4,
                    deliveredGrace = 3.days,
                ),
                probeTimeouts = mapOf(CarrierId.DHL to 10.seconds),
            )
            val parcel = parcels.createParcel(user.id, number, "P", "UNKNOWN", carrier = "dhl")

            val refreshed = serviceWith(withEta).refresh(user.id, parcel.id)
            assertEquals(isoUtc(withEta.eta!!), refreshed.estimatedDeliveryAt)

            val cleared = serviceWith(withoutEta).refresh(user.id, parcel.id)
            assertNull(cleared.estimatedDeliveryAt, "a carrier that stops providing an ETA clears the stored one")
        } finally {
            closeDatabase(db)
        }
    }

    @Test
    fun `a rate-limited carrier marks the parcel with the structured code and backs the limiter off by the Retry-After hint`() = runTest {
        val db = freshTestDatabase("refresh-rate-limited")
        try {
            val users = UserRepository(db)
            val parcels = ParcelRepository(db)
            val user = users.create("alice@example.com", "hash-1", displayName = "Alice")
            val clock = ManualClock(kotlin.time.Instant.fromEpochMilliseconds(0))
            val limiter = CarrierRateLimiter(minInterval = 1.seconds, clock = clock)
            val service = DefaultRefreshService(
                parcels = parcels,
                registry = CarrierRegistry(mapOf(CarrierId.DHL to RateLimitedAdapter(CarrierId.DHL, 60.seconds))),
                rateLimiters = mapOf(CarrierId.DHL to limiter),
                settings = RefreshSettings(
                    staleAfter = 30.minutes,
                    maxConcurrentProbes = 4,
                    deliveredGrace = 3.days,
                ),
                probeTimeouts = mapOf(CarrierId.DHL to 10.seconds),
                clock = clock,
            )
            val parcel = parcels.createParcel(user.id, "1234567890", "P", "UNKNOWN", carrier = "dhl")

            val refreshed = service.refresh(user.id, parcel.id)

            assertEquals("rate_limited", refreshed.lastCheckErrorCode)
            assertEquals(isoUtc(clock.now + 60.seconds), refreshed.rateLimitedUntil)
            assertTrue(refreshed.lastCheckError!!.startsWith("carrier rate limit hit"))
            assertNotNull(refreshed.lastCheckedAt)

            var acquired = false
            val job = launch {
                limiter.acquire()
                acquired = true
            }
            yield()
            assertFalse(acquired, "the backOff must hold the next acquire")
            advanceTimeBy(59.seconds)
            runCurrent()
            assertFalse(acquired)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(acquired, "once the Retry-After hint has elapsed the limiter lets a probe through")
            job.join()
        } finally {
            closeDatabase(db)
        }
    }
}
