package com.postbox.carrier

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class FakeCarrierTest {

    private val knownNumber = "1Z999AA10123456784"
    private val knownResult = FakeCarrier.deterministicResult(knownNumber)
    private val fake = FakeCarrier(
        carrier = CarrierId.DHL,
        results = mapOf(knownNumber to knownResult),
    )

    @Test
    fun `same tracking number always returns the identical result across calls`() = runTest {
        val first = fake.track(knownNumber, emptyMap())
        val second = fake.track(knownNumber, emptyMap())

        assertEquals(first, second)
        assertEquals(knownResult, first)
        assertEquals(
            first.events.sortedBy { it.occurredAt },
            first.events,
            "events must be ordered by occurredAt",
        )
    }

    @Test
    fun `result built from unsorted events is returned with events ordered by occurredAt`() = runTest {
        val unsorted = TrackingResult(
            status = TrackingStatus.IN_TRANSIT,
            eta = null,
            carrierService = null,
            events = listOf(
                TrackingEvent(Instant.parse("2026-01-03T00:00:00Z"), TrackingStatus.DELIVERED, "delivered", null),
                TrackingEvent(Instant.parse("2026-01-01T00:00:00Z"), TrackingStatus.PENDING, "registered", null),
                TrackingEvent(Instant.parse("2026-01-02T00:00:00Z"), TrackingStatus.IN_TRANSIT, "in transit", null),
            ),
        )
        val carrier = FakeCarrier(CarrierId.CTT, results = mapOf("X" to unsorted))

        val result = carrier.track("X", emptyMap())

        assertEquals(listOf("registered", "in transit", "delivered"), result.events.map { it.description })
    }

    @Test
    fun `unknown tracking number throws NotFound carrying that number`() = runTest {
        val error = assertFailsWith<CarrierError.NotFound> {
            fake.track("UNKNOWN-123", emptyMap())
        }

        assertEquals("UNKNOWN-123", error.number)
    }

    @Test
    fun `missing required metadata throws NeedsMetadata naming the missing fields`() = runTest {
        val field = MetadataField("order_id", "Order ID", "hint", null)
        val carrier = FakeCarrier(
            carrier = CarrierId.DPD,
            requiredMetadata = mapOf("order_id" to field),
            results = mapOf(knownNumber to knownResult),
        )

        val error = assertFailsWith<CarrierError.NeedsMetadata> {
            carrier.track(knownNumber, emptyMap())
        }

        assertEquals(listOf(field), error.fields)
    }

    @Test
    fun `metadata value failing validationRegex throws NeedsMetadata, valid value passes`() = runTest {
        val field = MetadataField("pin", "PIN", "4 digits", "\\d{4}")
        val carrier = FakeCarrier(
            carrier = CarrierId.PAACK,
            requiredMetadata = mapOf("pin" to field),
            results = mapOf(knownNumber to knownResult),
        )

        val error = assertFailsWith<CarrierError.NeedsMetadata> {
            carrier.track(knownNumber, mapOf("pin" to "abc"))
        }
        assertEquals(listOf(field), error.fields)

        assertEquals(knownResult, carrier.track(knownNumber, mapOf("pin" to "1234")))
    }

    @Test
    fun `metadata validation runs before the number lookup`() = runTest {
        val field = MetadataField("order_id", "Order ID", "hint", null)
        val carrier = FakeCarrier(
            carrier = CarrierId.POSTNL,
            requiredMetadata = mapOf("order_id" to field),
            results = emptyMap(),
        )

        // Unknown number AND missing metadata: NeedsMetadata wins per the ordering rule.
        assertFailsWith<CarrierError.NeedsMetadata> {
            carrier.track("UNKNOWN-123", emptyMap())
        }
    }

    @Test
    fun `carrier and requiredMetadata are constructor-configured and the fake participates in a registry`() = runTest {
        val field = MetadataField("order_id", "Order ID", "hint", null)
        val carrier = FakeCarrier(
            carrier = CarrierId.ECOSCOOTING,
            requiredMetadata = mapOf("order_id" to field),
            results = mapOf(knownNumber to knownResult),
        )
        val registry = CarrierRegistry(mapOf(CarrierId.ECOSCOOTING to carrier))

        assertEquals(CarrierId.ECOSCOOTING, carrier.carrier)
        assertEquals(mapOf("order_id" to field), carrier.requiredMetadata)
        assertEquals(listOf(carrier), registry.all)
        assertEquals(knownResult, registry.all.single().track(knownNumber, mapOf("order_id" to "A1")))
        assertSame(carrier, registry.lookup(CarrierId.ECOSCOOTING))
    }
}
