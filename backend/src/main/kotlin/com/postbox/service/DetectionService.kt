package com.postbox.service

import com.postbox.carrier.CarrierId

class ServiceNotImplemented(val feature: String) : Exception("Not implemented yet: $feature")

enum class DetectionMethod {
    RULE,
    PROBE,
    OVERRIDE,
}

data class DetectionResult(
    val carrier: CarrierId?,
    val method: DetectionMethod,
    val candidates: List<CarrierId>,
)

/**
 * Resolves which carrier a tracking number belongs to (format rules plus a
 * parallel probe, first hit wins; a manual carrier override always wins).
 * The API layer maps [ServiceNotImplemented] from the wired default to
 * `501 not_implemented`.
 */
interface DetectionService {
    suspend fun detect(
        trackingNumber: String,
        carrierOverride: CarrierId?,
        metadata: Map<String, String>,
    ): DetectionResult
}

object NotImplementedDetectionService : DetectionService {
    override suspend fun detect(
        trackingNumber: String,
        carrierOverride: CarrierId?,
        metadata: Map<String, String>,
    ): DetectionResult = throw ServiceNotImplemented("POST /api/v1/parcels/detect")
}
