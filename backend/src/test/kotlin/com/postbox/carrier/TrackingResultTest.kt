package com.postbox.carrier

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackingResultTest {

    @Test
    fun `events are ordered by occurredAt even when constructed out of order`() {
        val t1 = Instant.parse("2026-11-20T10:00:00Z")
        val t2 = Instant.parse("2026-11-21T10:00:00Z")
        val t3 = Instant.parse("2026-11-22T10:00:00Z")
        val result = TrackingResult(
            status = TrackingStatus.IN_TRANSIT,
            eta = null,
            carrierService = null,
            events = listOf(
                TrackingEvent(t3, TrackingStatus.IN_TRANSIT, "scanned", null),
                TrackingEvent(t1, TrackingStatus.PENDING, "label created", "Lisbon"),
                TrackingEvent(t2, TrackingStatus.OUT_FOR_DELIVERY, "out for delivery", null),
            ),
        )
        assertEquals(listOf(t1, t2, t3), result.events.map { it.occurredAt })
    }
}
