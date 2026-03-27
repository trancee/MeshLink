package io.meshlink.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircuitBreakerTest {

    @Test
    fun tripsAfterFailuresResetsAfterCooldown() {
        var now = 0L
        val breaker = CircuitBreaker(
            maxFailures = 3,
            windowMs = 5000,
            cooldownMs = 30_000,
            clock = { now },
        )

        // Initially closed (allows attempts)
        assertTrue(breaker.allowAttempt(), "Should be closed initially")

        // Record 2 failures — still closed
        breaker.recordFailure()
        breaker.recordFailure()
        assertTrue(breaker.allowAttempt(), "2 failures — still closed")

        // 3rd failure within window → trips open
        breaker.recordFailure()
        assertFalse(breaker.allowAttempt(), "3 failures in window — should be open")

        // Still open before cooldown
        now = 29_000L
        assertFalse(breaker.allowAttempt(), "Still in cooldown")

        // After cooldown → resets to closed
        now = 31_000L
        assertTrue(breaker.allowAttempt(), "After cooldown — should be closed again")

        // Failures outside window don't count together
        now = 100_000L
        breaker.recordFailure()
        now = 106_000L // > 5s later
        breaker.recordFailure()
        breaker.recordFailure()
        // Only 2 failures within current window
        assertTrue(breaker.allowAttempt(), "Failures outside window should not accumulate")
    }

    // --- Batch 11 Cycle 2: Exact cooldown boundary ---

    @Test
    fun cooldownBoundaryAllowsAtExactMs() {
        var now = 0L
        val breaker = CircuitBreaker(maxFailures = 1, windowMs = 10_000, cooldownMs = 5_000, clock = { now })

        breaker.recordFailure() // trips at t=0
        assertFalse(breaker.allowAttempt(), "Should be tripped")

        // At exactly cooldownMs: 5000 - 0 = 5000 >= 5000 → should allow (>= check)
        now = 5_000L
        assertTrue(breaker.allowAttempt(), "At exact cooldown boundary, should reset (>= check)")
    }

    // --- Batch 11 Cycle 8: Failure window boundary ---

    @Test
    fun failureAtExactWindowBoundaryStillCounted() {
        var now = 0L
        val breaker = CircuitBreaker(maxFailures = 2, windowMs = 5_000, cooldownMs = 10_000, clock = { now })

        // Failure at t=0
        now = 0L
        breaker.recordFailure()

        // At t=5000: 5000 - 0 = 5000, NOT > 5000 → NOT pruned → still counts
        now = 5_000L
        breaker.recordFailure() // 2 failures in window → should trip
        assertFalse(breaker.allowAttempt(), "2 failures within window boundary should trip")
    }
}
