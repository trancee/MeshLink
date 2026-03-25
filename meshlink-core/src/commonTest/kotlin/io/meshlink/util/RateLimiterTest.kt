package io.meshlink.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {

    @Test
    fun allowsUpToLimitRejectsExcess() {
        var now = 0L
        val limiter = RateLimiter(maxEvents = 3, windowMs = 1000, clock = { now })

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
}
