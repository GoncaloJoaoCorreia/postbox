package com.postbox.carrier

import java.time.Instant

enum class TrackingStatus {
    PENDING,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    EXCEPTION,
    UNKNOWN,
}

data class TrackingEvent(
    val occurredAt: Instant,
    val status: TrackingStatus,
    val description: String,
    val location: String?,
)

/**
 * Guarantees [events] are ordered by [TrackingEvent.occurredAt] regardless of input order.
 */
class TrackingResult(
    val status: TrackingStatus,
    val eta: Instant?,
    val carrierService: String?,
    events: List<TrackingEvent>,
) {
    val events: List<TrackingEvent> = events.sortedBy { it.occurredAt }
}
