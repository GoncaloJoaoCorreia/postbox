package com.postbox.carrier

import kotlin.time.Duration

/**
 * Sealed error vocabulary for carrier adapters; [NotFound] is the signal by which
 * the detection engine eliminates carriers.
 */
sealed class CarrierError : Exception() {
    class NotFound(val number: String) : CarrierError()

    class NeedsMetadata(val fields: List<MetadataField>) : CarrierError()

    class CarrierUnavailable(override val cause: Throwable) : CarrierError()

    class RateLimited(val retryAfter: Duration) : CarrierError()
}
