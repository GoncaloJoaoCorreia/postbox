package com.postbox.carrier

/**
 * One carrier's tracking adapter. Failures are signaled by throwing
 * [CarrierError] subclasses — [CarrierError.NotFound] is the detection
 * engine's elimination signal, [CarrierError.NeedsMetadata] carries the
 * missing fields — so `track` returns only success values.
 */
interface CarrierAdapter {
    /** This adapter's carrier identity. */
    val carrier: CarrierId

    /** Metadata the adapter requires on a `track` call; may be empty. */
    val requiredMetadata: Map<String, MetadataField>

    /**
     * Looks up [number] through this adapter's channel.
     *
     * @throws CarrierError.NotFound if this carrier has no tracking record for [number].
     * @throws CarrierError.NeedsMetadata if [metadata] lacks a field in [requiredMetadata].
     */
    suspend fun track(number: String, metadata: Map<String, String>): TrackingResult
}

/**
 * Maps [CarrierId] to its [CarrierAdapter]; iteration order is the backing
 * map's insertion order, so probing is deterministic.
 */
class CarrierRegistry(private val adapters: Map<CarrierId, CarrierAdapter>) {

    /** The registered adapter for [carrier], or null when unregistered. */
    fun lookup(carrier: CarrierId): CarrierAdapter? = adapters[carrier]

    /** All registered adapters, in backing-map insertion order. */
    val all: List<CarrierAdapter> = adapters.values.toList()
}
