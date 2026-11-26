package com.postbox.carrier

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Suspend-based per-carrier rate limiter: every [acquire] is spaced at least [minInterval]
 * apart. The interval is a constructor parameter only — carrier defaults (e.g. DHL's one
 * call per 5s) belong to config, not here. The [clock] is injectable so tests can drive
 * virtual time instead of sleeping in real time.
 */
class CarrierRateLimiter(
    private val minInterval: Duration,
    private val clock: Clock = Clock.System,
) {
    init {
        require(minInterval > Duration.ZERO) { "minInterval must be positive, was $minInterval" }
    }

    private val mutex = Mutex()
    private var nextAllowed: Instant = clock.now()

    suspend fun acquire(): Unit = mutex.withLock {
        val remaining = nextAllowed - clock.now()
        if (remaining > Duration.ZERO) delay(remaining)
        // Advance by the minimum interval; resync to now if the limiter was idle so a
        // stale nextAllowed cannot let later calls through unpaced.
        nextAllowed = maxOf(nextAllowed, clock.now()) + minInterval
    }
}
