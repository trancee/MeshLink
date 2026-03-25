package io.meshlink.power

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionLimiterTest {

    @Test
    fun enforcesPerModeLimitsAndEvictsOnDowngrade() {
        val limiter = ConnectionLimiter()

        // Performance mode: limit = 8
        limiter.setMode(PowerMode.PERFORMANCE)
        // Add 8 connections
        for (i in 1..8) assertTrue(limiter.tryAdd("peer$i"), "Connection $i should be allowed")
        // 9th rejected
        assertFalse(limiter.tryAdd("peer9"), "9th connection should be rejected in Performance")

        // Downgrade to Balanced (limit = 4) — should evict 4 idle connections
        val evicted = limiter.setMode(PowerMode.BALANCED)
        assertEquals(4, evicted.size, "Should evict 4 connections on downgrade to Balanced")
        assertEquals(4, limiter.connectionCount())

        // Can't add more (at limit)
        assertFalse(limiter.tryAdd("peer10"), "At Balanced limit — should reject")

        // Remove one, then can add
        limiter.remove(limiter.connections().first())
        assertTrue(limiter.tryAdd("peer10"), "After remove, should accept")

        // Downgrade to PowerSaver (limit = 1)
        val evicted2 = limiter.setMode(PowerMode.POWER_SAVER)
        assertEquals(3, evicted2.size, "Should evict 3 on downgrade to PowerSaver")
        assertEquals(1, limiter.connectionCount())
    }
}
