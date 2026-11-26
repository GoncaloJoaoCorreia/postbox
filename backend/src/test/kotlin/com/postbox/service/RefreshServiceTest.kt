package com.postbox.service

import com.postbox.RefreshSettings
import com.postbox.carrier.CarrierAdapter
import com.postbox.carrier.CarrierId
import com.postbox.carrier.CarrierRegistry
import com.postbox.carrier.FakeCarrier
import com.postbox.carrier.MetadataField
import com.postbox.carrier.TrackingResult
import com.postbox.db.ParcelRepository
import com.postbox.db.UserRepository
import com.postbox.db.closeDatabase
import com.postbox.db.freshTestDatabase
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

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
            assertTrue(refreshed.all { it.lastCheckedAt != null }, "every stale parcel must be refreshed")
            assertEquals(6, adapter.calls.get())
            assertTrue(
                adapter.maxInFlight.get() <= 4,
                "at most maxConcurrentProbes probes may be in flight, got ${adapter.maxInFlight.get()}",
            )
            assertTrue(
                adapter.maxInFlight.get() > 1,
                "probes should still run concurrently, got ${adapter.maxInFlight.get()}",
            )
            assertTrue(refreshed.all { it.lastCheckError == null }, "successful refreshes clear the soft error indicator")
        } finally {
            closeDatabase(db)
        }
    }
}
