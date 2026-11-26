package com.postbox.carrier

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * In-memory [CarrierAdapter] for tests and later detection/refresh services.
 * Pure and deterministic: the outcome of [track] depends only on the tracking
 * number and the [metadata] argument, never on call count, wall-clock time, or
 * randomness. Performs no I/O.
 *
 * The fake takes one of the six real [CarrierId] values via constructor and is
 * simply never registered under a real id in the production registry.
 *
 * The constructor maps are copied at construction — the same convention as
 * [CarrierRegistry] — so mutating a source map afterwards cannot change this
 * fake's behavior.
 */
class FakeCarrier(
    override val carrier: CarrierId,
    requiredMetadata: Map<String, MetadataField> = emptyMap(),
    results: Map<String, TrackingResult>,
) : CarrierAdapter {

    override val requiredMetadata: Map<String, MetadataField> = requiredMetadata.toMap()

    private val results: Map<String, TrackingResult> = results.toMap()

    /** [MetadataField.validationRegex] patterns compiled once, keyed by field. */
    private val validationPatterns: Map<MetadataField, Regex?> =
        requiredMetadata.values.associateWith { field -> field.validationRegex?.let(::Regex) }

    override suspend fun track(number: String, metadata: Map<String, String>): TrackingResult {
        // Metadata validation runs before the number lookup: a real carrier cannot
        // be queried without its required metadata. Consequence: an unknown number
        // with complete metadata yields NotFound; a known number with missing or
        // invalid metadata yields NeedsMetadata.
        val invalid = requiredMetadata.values.filter { field ->
            val value = metadata[field.name]
            val pattern = validationPatterns[field]
            value == null || (pattern != null && !pattern.matches(value))
        }
        if (invalid.isNotEmpty()) throw CarrierError.NeedsMetadata(invalid)

        // Keyed on the tracking number itself, which is what makes the outcome
        // deterministic per number. TrackingResult's constructor already orders
        // events by occurredAt, so the stored result satisfies the ordering contract.
        return results[number] ?: throw CarrierError.NotFound(number)
    }

    companion object {
        private val BASE: Instant = Instant.parse("2026-01-01T00:00:00Z")

        /**
         * Derives a stable [TrackingResult] from [number] alone, so services can
         * register many numbers cheaply while every number maps to exactly one
         * immutable result.
         */
        fun deterministicResult(number: String): TrackingResult {
            val statuses = TrackingStatus.entries
            val status = statuses[Math.floorMod(number.hashCode(), statuses.size)]
            return TrackingResult(
                status = status,
                eta = BASE.plus(3, ChronoUnit.DAYS),
                carrierService = "FAKE-EXPRESS",
                events = listOf(
                    TrackingEvent(BASE, TrackingStatus.PENDING, "Shipment registered", null),
                    TrackingEvent(BASE.plus(1, ChronoUnit.HOURS), TrackingStatus.IN_TRANSIT, "In transit", "Hub"),
                    TrackingEvent(BASE.plus(2, ChronoUnit.HOURS), status, "Latest scan", null),
                ),
            )
        }
    }
}
