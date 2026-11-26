package com.postbox.service

import com.postbox.carrier.CarrierError
import com.postbox.carrier.CarrierId
import com.postbox.carrier.CarrierRateLimiter
import com.postbox.carrier.CarrierRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Rules-plus-probe detection: [TrackingNumberRules] produce the ranked candidates,
 * all registered candidate adapters are probed concurrently (each paced by its
 * per-carrier rate limiter) and the first adapter returning real tracking data
 * wins. A [CarrierError] of any kind (NotFound, NeedsMetadata, CarrierUnavailable,
 * RateLimited) only eliminates the candidate. A manual [carrierOverride] always
 * wins and is never probed away. When no probe hits, the top-ranked candidate is
 * kept provisionally (method [DetectionMethod.RULE]) until a later re-probe.
 */
class RuleBasedDetectionService(
    private val registry: CarrierRegistry,
    private val rateLimiters: Map<CarrierId, CarrierRateLimiter>,
) : DetectionService {

    override suspend fun detect(
        trackingNumber: String,
        carrierOverride: CarrierId?,
        metadata: Map<String, String>,
    ): DetectionResult {
        val candidates = TrackingNumberRules.candidatesFor(trackingNumber)
        if (carrierOverride != null) {
            return DetectionResult(carrierOverride, DetectionMethod.OVERRIDE, candidates)
        }
        val winner = probeFirstHit(trackingNumber, candidates, metadata)
        return if (winner != null) {
            DetectionResult(winner, DetectionMethod.PROBE, candidates)
        } else {
            DetectionResult(candidates.firstOrNull(), DetectionMethod.RULE, candidates)
        }
    }

    private suspend fun probeFirstHit(
        trackingNumber: String,
        candidates: List<CarrierId>,
        metadata: Map<String, String>,
    ): CarrierId? = coroutineScope {
        val hit = CompletableDeferred<CarrierId>()
        val jobs = candidates.mapNotNull { carrier ->
            val adapter = registry.lookup(carrier) ?: return@mapNotNull null
            launch {
                try {
                    rateLimiters[carrier]?.acquire()
                    adapter.track(trackingNumber, metadata)
                    hit.complete(carrier)
                } catch (e: CarrierError) {
                }
            }
        }
        jobs.joinAll()
        if (hit.isCompleted) hit.await() else null
    }
}
