package io.meshlink.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {

    @Test
    fun allowsUpToLimitRejectsExcess() {
        var now = 0L
        val limiter = RateLimiter(maxEvents = 3, windowMillis = 1000, clock = { now })

        // First 3 events within window → allowed
        assertTrue(limiter.tryAcquire("sender-a"), "1st event allowed")
        assertTrue(limiter.tryAcquire("sender-a"), "2nd event allowed")
        assertTrue(limiter.tryAcquire("sender-a"), "3rd event allowed")

        // 4th event within same window → rejected
        assertFalse(limiter.tryAcquire("sender-a"), "4th event rejected (over limit)")

        // Different sender → independent limit
        assertTrue(limiter.tryAcquire("sender-b"), "Different sender allowed")

        // Advance past window → sender-a can send again
        now = 1001L
        assertTrue(limiter.tryAcquire("sender-a"), "After window expires, allowed again")
    }

    // --- Batch 10 Cycle 8: Exact boundary timing ---

    @Test
    fun windowBoundaryRejectsAtExactEdgeAllowsAfter() {
        var now = 0L
        val limiter = RateLimiter(maxEvents = 1, windowMillis = 1000, clock = { now })

        // Event at t=0
        assertTrue(limiter.tryAcquire("k"), "First event at t=0 allowed")

        // At t=1000: event at t=0 is exactly windowMillis old → pruneAll removes it (now - 0 > 1000 is false)
        // The condition is `now - it > windowMillis`, so 1000 - 0 = 1000, NOT > 1000 → NOT pruned → still rejected
        now = 1000L
        assertFalse(limiter.tryAcquire("k"), "At exact boundary t=1000, event at t=0 should NOT be pruned (1000 - 0 == 1000, not > 1000)")

        // At t=1001: 1001 - 0 = 1001 > 1000 → pruned → allowed
        now = 1001L
        assertTrue(limiter.tryAcquire("k"), "At t=1001, event at t=0 should be pruned and new event allowed")
    }

    // --- Batch 13 Cycle 4: Old events pruned within key ---

    @Test
    fun oldEventsPrunedDoNotAccumulate() {
        var now = 0L
        val limiter = RateLimiter(maxEvents = 2, windowMillis = 100, clock = { now })

        // Fill limit at t=0
        assertTrue(limiter.tryAcquire("k"))
        assertTrue(limiter.tryAcquire("k"))
        assertFalse(limiter.tryAcquire("k"), "At limit")

        // Advance past window — old events pruned on next tryAcquire
        now = 200L
        assertTrue(limiter.tryAcquire("k"), "Old events pruned, slot available")
        assertTrue(limiter.tryAcquire("k"), "Second slot available")
        assertFalse(limiter.tryAcquire("k"), "At limit again")

        // Repeat many times — proves no unbounded growth within a key
        for (cycle in 1..100) {
            now = 200L + (cycle * 200L)
            assertTrue(limiter.tryAcquire("k"), "Cycle $cycle: old events pruned")
        }
    }
}
