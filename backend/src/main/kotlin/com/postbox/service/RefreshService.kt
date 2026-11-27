package com.postbox.service

import com.postbox.RefreshSettings
import com.postbox.carrier.CarrierAdapter
import com.postbox.carrier.CarrierError
import com.postbox.carrier.CarrierId
import com.postbox.carrier.CarrierRateLimiter
import com.postbox.carrier.CarrierRegistry
import com.postbox.carrier.MetadataField
import com.postbox.carrier.TrackingEvent
import com.postbox.carrier.TrackingResult
import com.postbox.carrier.TrackingStatus
import com.postbox.db.ParcelRecord
import com.postbox.db.ParcelRepository
import com.postbox.db.isoUtc
import com.postbox.db.parseIsoUtc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * On-demand refresh: [refreshStale] refreshes the stale parcels of a list before
 * the app-open response is returned, and [refresh] forces one parcel regardless of
 * staleness. A carrier failure never fails the call — cached data stays and the
 * parcel is marked with a soft `lastCheckError` indicator. A [CarrierError.NeedsMetadata]
 * from the resolved carrier is not an outage either: [refresh] rethrows it and
 * [refreshStale] reports the missing fields on the outcome, so the caller can ask
 * the user for exactly those fields.
 */
interface RefreshService {
    /**
     * Forces a refresh of one own parcel, regardless of TTL or final-state grace.
     *
     * @throws ParcelNotFound when the parcel does not exist (or belongs to another user).
     * @throws CarrierError.NeedsMetadata when the resolved carrier requires fields the parcel lacks.
     */
    suspend fun refresh(userId: String, parcelId: String): ParcelRecord

    /**
     * Refreshes the stale parcels in [owned] (TTL and final-state grace apply) and
     * returns the full list with the refreshed records in place, order preserved.
     * A parcel whose carrier needs missing metadata comes back unchanged with the
     * missing fields on [RefreshOutcome.missingMetadata].
     */
    suspend fun refreshStale(owned: List<ParcelRecord>): List<RefreshOutcome>
}

/** One parcel after a list refresh: the (possibly refreshed) record plus any metadata the carrier still requires. */
data class RefreshOutcome(
    val parcel: ParcelRecord,
    val missingMetadata: List<MetadataField> = emptyList(),
)

/** Thrown when [RefreshService.refresh] is called for a parcel that does not exist (or belongs to another user). */
class ParcelNotFound(val parcelId: String) : Exception("No parcel with this id exists")

/**
 * Refreshes parcels from their registered carrier adapter: new events are inserted
 * (deduplicated by the `uq_parcel_events_idempotency` unique index), the status is
 * recalculated from the carrier result and `last_checked_at` is updated.
 *
 * Staleness: a parcel with no `last_checked_at`, or older than [RefreshSettings.staleAfter],
 * is refreshed. DELIVERED parcels skip refresh once [RefreshSettings.deliveredGrace] has
 * passed since their last check. In-flight carrier probes are capped at
 * [RefreshSettings.maxConcurrentProbes] while each carrier's own rate limiter is always
 * respected (adapters pace themselves inside `track`), and every probe is bounded by the
 * carrier's configured request timeout — the limiter wait happens inside that timeout, so
 * one hanging or throttled carrier delays nothing beyond its own timeout. A failed probe
 * (error or timeout) leaves the cached data untouched and marks the parcel with a soft
 * "carrier unreachable, data from {time}" indicator. A [CarrierError.RateLimited] probe
 * backs the carrier's limiter off by its `Retry-After` hint and marks the parcel with the
 * structured `rate_limited` code plus `rate_limited_until` instead. A
 * [CarrierError.NeedsMetadata] probe is not a failure: it is not counted as a check
 * (lastCheckedAt and lastCheckError stay untouched) and the missing fields are reported
 * to the caller instead.
 */
class DefaultRefreshService(
    private val parcels: ParcelRepository,
    private val registry: CarrierRegistry,
    private val rateLimiters: Map<CarrierId, CarrierRateLimiter>,
    private val settings: RefreshSettings,
    private val probeTimeouts: Map<CarrierId, Duration>,
    private val clock: Clock = Clock.System,
) : RefreshService {

    private val probeSlots = Semaphore(settings.maxConcurrentProbes)

    /** SQLite allows a single writer; serialize the DB-write sections of concurrent refreshes. */
    private val dbMutex = Mutex()

    override suspend fun refresh(userId: String, parcelId: String): ParcelRecord {
        val parcel = withContext(Dispatchers.IO) { parcels.findParcelForUser(userId, parcelId) }
            ?: throw ParcelNotFound(parcelId)
        return refreshParcel(parcel)
    }

    override suspend fun refreshStale(owned: List<ParcelRecord>): List<RefreshOutcome> {
        val now = clock.now()
        return coroutineScope {
            owned.map { parcel ->
                async {
                    if (!isStale(parcel, now)) return@async RefreshOutcome(parcel)
                    try {
                        RefreshOutcome(refreshParcel(parcel))
                    } catch (e: CarrierError.NeedsMetadata) {
                        RefreshOutcome(parcel, missingMetadata = e.fields)
                    }
                }
            }.awaitAll()
        }
    }

    private fun isStale(parcel: ParcelRecord, now: Instant): Boolean {
        val lastCheckedAt = parcel.lastCheckedAt?.let(::parseIsoUtc) ?: return true
        if (parcel.status == TrackingStatus.DELIVERED.name && now - lastCheckedAt > settings.deliveredGrace) {
            return false
        }
        return now - lastCheckedAt > settings.staleAfter
    }

    private suspend fun refreshParcel(parcel: ParcelRecord): ParcelRecord {
        val carrier = parcel.carrier?.let(CarrierId::fromId) ?: return parcel
        val adapter = registry.lookup(carrier) ?: return markUnreachable(parcel)
        return when (val outcome = probe(carrier, adapter, parcel)) {
            is ProbeOutcome.Success -> {
                dbMutex.withLock {
                    for (event in outcome.result.events) {
                        withContext(Dispatchers.IO) {
                            parcels.addEventForUser(
                                userId = parcel.userId,
                                parcelId = parcel.id,
                                occurredAt = isoUtc(event.occurredAt),
                                status = event.status.name,
                                description = event.description,
                                location = event.location,
                                raw = rawEventJson(event),
                            )
                        }
                    }
                }
                persist(
                    parcel,
                    status = outcome.result.status.name,
                    lastCheckedAt = isoUtc(clock.now()),
                    lastCheckError = null,
                    lastCheckErrorCode = null,
                    rateLimitedUntil = null,
                    estimatedDeliveryAt = outcome.result.eta?.let(::isoUtc),
                )
            }
            is ProbeOutcome.RateLimited -> markRateLimited(parcel, outcome.retryAfter)
            ProbeOutcome.Unreachable -> markUnreachable(parcel)
        }
    }

    private suspend fun probe(carrier: CarrierId, adapter: CarrierAdapter, parcel: ParcelRecord): ProbeOutcome {
        val metadata = parcel.carrierMetadata
            ?.let { Json.decodeFromString<Map<String, String>>(it) }
            ?: emptyMap()
        val timeout = probeTimeouts[carrier] ?: DEFAULT_PROBE_TIMEOUT
        return try {
            val result = probeSlots.withPermit {
                withTimeoutOrNull(timeout) { adapter.track(parcel.trackingNumber, metadata) }
            }
            if (result != null) ProbeOutcome.Success(result) else ProbeOutcome.Unreachable
        } catch (e: CancellationException) {
            throw e
        } catch (e: CarrierError.NeedsMetadata) {
            throw e
        } catch (e: CarrierError.RateLimited) {
            log.info(
                "Carrier '{}' rate-limited the probe for parcel {}, backing off for {}",
                carrier.id, parcel.id, e.retryAfter,
            )
            rateLimiters[carrier]?.backOff(e.retryAfter)
            ProbeOutcome.RateLimited(e.retryAfter)
        } catch (e: Exception) {
            log.warn("Carrier '{}' probe for parcel {} failed: {}", carrier.id, parcel.id, e.message)
            ProbeOutcome.Unreachable
        }
    }

    private sealed interface ProbeOutcome {
        data class Success(val result: TrackingResult) : ProbeOutcome
        data object Unreachable : ProbeOutcome
        data class RateLimited(val retryAfter: Duration) : ProbeOutcome
    }

    private suspend fun markUnreachable(parcel: ParcelRecord): ParcelRecord {
        val dataFrom = parcel.lastCheckedAt?.let { "data from $it" } ?: "no carrier data yet"
        return persist(
            parcel,
            status = parcel.status,
            lastCheckedAt = isoUtc(clock.now()),
            lastCheckError = "carrier unreachable, $dataFrom",
            lastCheckErrorCode = "carrier_unreachable",
            rateLimitedUntil = null,
            estimatedDeliveryAt = parcel.estimatedDeliveryAt,
        )
    }

    private suspend fun markRateLimited(parcel: ParcelRecord, retryAfter: Duration): ParcelRecord {
        val now = clock.now()
        val dataFrom = parcel.lastCheckedAt?.let { "data from $it" } ?: "no carrier data yet"
        return persist(
            parcel,
            status = parcel.status,
            lastCheckedAt = isoUtc(now),
            lastCheckError = "carrier rate limit hit (retry after ${retryAfter.inWholeSeconds}s), $dataFrom",
            lastCheckErrorCode = "rate_limited",
            rateLimitedUntil = isoUtc(now + retryAfter),
            estimatedDeliveryAt = parcel.estimatedDeliveryAt,
        )
    }

    private suspend fun persist(
        parcel: ParcelRecord,
        status: String,
        lastCheckedAt: String?,
        lastCheckError: String?,
        lastCheckErrorCode: String?,
        rateLimitedUntil: String?,
        estimatedDeliveryAt: String?,
    ): ParcelRecord {
        return dbMutex.withLock {
            withContext(Dispatchers.IO) {
                parcels.updateParcelForUser(
                    userId = parcel.userId,
                    parcelId = parcel.id,
                    displayName = parcel.displayName,
                    status = status,
                    carrier = parcel.carrier,
                    detectionMethod = parcel.detectionMethod,
                    carrierMetadata = parcel.carrierMetadata,
                    lastCheckedAt = lastCheckedAt,
                    archivedAt = parcel.archivedAt,
                    lastCheckError = lastCheckError,
                    lastCheckErrorCode = lastCheckErrorCode,
                    rateLimitedUntil = rateLimitedUntil,
                    estimatedDeliveryAt = estimatedDeliveryAt,
                )
                parcels.findParcelForUser(parcel.userId, parcel.id)!!
            }
        }
    }

    private fun rawEventJson(event: TrackingEvent): String = Json.encodeToString(
        buildJsonObject {
            put("occurredAt", isoUtc(event.occurredAt))
            put("status", event.status.name)
            put("description", event.description)
            event.location?.let { put("location", it) }
        },
    )

    companion object {
        private val log = LoggerFactory.getLogger(DefaultRefreshService::class.java)
        private val DEFAULT_PROBE_TIMEOUT = 10.seconds
    }
}
