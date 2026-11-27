package com.postbox.carrier

import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CarrierRateLimiterTest {

    /** Manually advanced clock so the test drives time explicitly instead of sleeping in real time. */
    private class ManualClock(var now: Instant) : Clock {
        override fun now(): Instant = now
    }

    @Test
    fun `second immediate acquire completes only after the minimum interval`() = runTest {
        val clock = ManualClock(Instant.fromEpochMilliseconds(0))
        val limiter = CarrierRateLimiter(minInterval = 5.seconds, clock = clock)

        limiter.acquire() // first call is immediate

        var secondCompleted = false
        val second = launch {
            limiter.acquire()
            secondCompleted = true
        }
        yield() // let the second acquire reach its delay
        assertFalse(secondCompleted)

        advanceTimeBy(4.seconds)
        assertFalse(secondCompleted) // interval not yet elapsed

        advanceTimeBy(1.seconds)
        // advanceTimeBy leaves tasks scheduled exactly at the new virtual time still queued,
        // so dispatch the ones pending at the deadline before asserting.
        runCurrent()
        assertTrue(secondCompleted) // interval elapsed in virtual time, no real-time sleep
        second.join()
    }

    @Test
    fun `backOff pushes the next acquire out by the given duration on top of the interval`() = runTest {
        val clock = ManualClock(Instant.fromEpochMilliseconds(0))
        val limiter = CarrierRateLimiter(minInterval = 5.seconds, clock = clock)

        limiter.acquire() // nextAllowed = +5s
        limiter.backOff(60.seconds) // a 429's Retry-After hint: nextAllowed = +65s

        var completed = false
        val job = launch {
            limiter.acquire()
            completed = true
        }
        yield()
        assertFalse(completed)

        advanceTimeBy(64.seconds)
        runCurrent()
        assertFalse(completed) // the hint has not fully elapsed yet

        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(completed)
        job.join()
    }

    @Test
    fun `acquire is immediate once the injected clock has passed the interval`() = runTest {
        val clock = ManualClock(Instant.fromEpochMilliseconds(0))
        val limiter = CarrierRateLimiter(minInterval = 5.seconds, clock = clock)

        limiter.acquire() // nextAllowed = +5s on the injected clock
        clock.now = Instant.fromEpochMilliseconds(10_000) // injected clock moved past nextAllowed

        var completed = false
        val job = launch {
            limiter.acquire()
            completed = true
        }
        yield()
        assertTrue(completed) // no delay: the injected clock, not a real one, decided
        job.join()
    }
}
