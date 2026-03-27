package io.meshlink.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveTimeoutTest {

    @Test
    fun firstSightingReturnsDefaultTimeout() {
        val at = AdaptiveTimeout(defaultTimeoutMs = 30_000L)
        at.recordSighting("A", timestampMs = 1000L)
        assertEquals(30_000L, at.getTimeout("A"))
    }

    @Test
    fun unknownPeerReturnsDefaultTimeout() {
        val at = AdaptiveTimeout(defaultTimeoutMs = 30_000L)
        assertEquals(30_000L, at.getTimeout("unknown"))
    }

    @Test
    fun timeoutConvergesToObservedIntervalTimesMultiplier() {
        val at = AdaptiveTimeout(
            minTimeoutMs = 1_000L,
            maxTimeoutMs = 120_000L,
            multiplier = 3.0,
            alpha = 0.3,
            defaultTimeoutMs = 30_000L,
        )
        // Simulate steady 10 000 ms interval over many sightings
        var t = 0L
        at.recordSighting("A", t)
        repeat(50) {
            t += 10_000L
            at.recordSighting("A", t)
        }
        // EWMA should converge to 10 000; timeout = 10 000 × 3 = 30 000
        assertEquals(30_000L, at.getTimeout("A"))
    }

    @Test
    fun timeoutRespectsMinBound() {
        val at = AdaptiveTimeout(
            minTimeoutMs = 5_000L,
            maxTimeoutMs = 120_000L,
            multiplier = 3.0,
            alpha = 0.3,
        )
        // Very fast advertiser: 100 ms interval → raw = 300 ms → clamped to 5 000
        var t = 0L
        at.recordSighting("fast", t)
        repeat(50) {
            t += 100L
            at.recordSighting("fast", t)
        }
        assertEquals(5_000L, at.getTimeout("fast"))
    }

    @Test
    fun timeoutRespectsMaxBound() {
        val at = AdaptiveTimeout(
            minTimeoutMs = 5_000L,
            maxTimeoutMs = 120_000L,
            multiplier = 3.0,
            alpha = 0.3,
        )
        // Very slow advertiser: 60 000 ms interval → raw = 180 000 → clamped to 120 000
        var t = 0L
        at.recordSighting("slow", t)
        repeat(50) {
            t += 60_000L
            at.recordSighting("slow", t)
        }
        assertEquals(120_000L, at.getTimeout("slow"))
    }

    @Test
    fun ewmaSmoothsOutJitter() {
        val at = AdaptiveTimeout(
            minTimeoutMs = 1_000L,
            maxTimeoutMs = 120_000L,
            multiplier = 3.0,
            alpha = 0.3,
            defaultTimeoutMs = 30_000L,
        )
        // Establish a baseline of 1 000 ms
        var t = 0L
        at.recordSighting("J", t)
        repeat(30) {
            t += 1_000L
            at.recordSighting("J", t)
        }
        val baselineTimeout = at.getTimeout("J")

        // Inject one large spike (10 000 ms)
        t += 10_000L
        at.recordSighting("J", t)
        val afterSpike = at.getTimeout("J")

        // After spike the timeout should increase, but NOT jump to 10 000 × 3
        assertTrue(afterSpike > baselineTimeout, "Spike should push timeout up")
        assertTrue(afterSpike < 10_000L * 3, "EWMA should dampen the spike")
    }

    @Test
    fun evictedPeerReturnsDefaultOnNextSighting() {
        val at = AdaptiveTimeout(
            minTimeoutMs = 1_000L,
            maxTimeoutMs = 120_000L,
            multiplier = 3.0,
            alpha = 0.3,
            defaultTimeoutMs = 30_000L,
        )
        var t = 0L
        at.recordSighting("E", t)
        repeat(10) {
            t += 2_000L
            at.recordSighting("E", t)
        }
        // Timeout should reflect observed interval
        val before = at.getTimeout("E")
        assertTrue(before != 30_000L, "Should have adapted away from default")

        at.evict("E")
        // Unknown after eviction → default
        assertEquals(30_000L, at.getTimeout("E"))

        // New sighting after eviction → still default (only one sighting)
        at.recordSighting("E", t + 5_000L)
        assertEquals(30_000L, at.getTimeout("E"))
    }

    @Test
    fun defaultTimeoutClampedToMinMax() {
        val at = AdaptiveTimeout(
            minTimeoutMs = 10_000L,
            maxTimeoutMs = 20_000L,
            defaultTimeoutMs = 50_000L,
        )
        // Default exceeds max → clamped
        assertEquals(20_000L, at.getTimeout("X"))
    }

    @Test
    fun multipleSightingsAtSameTimestampDoNotUpdateEwma() {
        val at = AdaptiveTimeout(defaultTimeoutMs = 30_000L)
        at.recordSighting("S", 1000L)
        at.recordSighting("S", 1000L) // delta = 0 → ignored
        assertEquals(30_000L, at.getTimeout("S"))
    }
}
