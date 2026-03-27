package io.meshlink.routing

import kotlin.test.Test
import kotlin.test.assertEquals

class GossipThrottleTest {

    private val base = 1000L
    private val throttle = GossipThrottle(baseIntervalMs = base)

    @Test
    fun belowThresholdReturnsBaseInterval() {
        assertEquals(base, throttle.effectiveInterval(50))
    }

    @Test
    fun atThresholdReturnsBaseInterval() {
        assertEquals(base, throttle.effectiveInterval(100))
    }

    @Test
    fun aboveThresholdScalesLinearly() {
        // 150 / 100 = 1.5× → 1500
        assertEquals(1500L, throttle.effectiveInterval(150))
    }

    @Test
    fun atMaxMultiplierCap() {
        // 500 / 100 = 5.0× exactly at cap → 5000
        assertEquals(5000L, throttle.effectiveInterval(500))
    }

    @Test
    fun aboveMaxMultiplierStaysCapped() {
        // 1000 / 100 = 10.0× but capped at 5× → 5000
        assertEquals(5000L, throttle.effectiveInterval(1000))
    }

    @Test
    fun zeroBaseIntervalReturnsZero() {
        val t = GossipThrottle(baseIntervalMs = 0)
        assertEquals(0L, t.effectiveInterval(200))
    }

    @Test
    fun negativeBaseIntervalReturnsZero() {
        val t = GossipThrottle(baseIntervalMs = -500)
        assertEquals(0L, t.effectiveInterval(50))
    }

    @Test
    fun singleRouteReturnsBase() {
        assertEquals(base, throttle.effectiveInterval(1))
    }

    @Test
    fun zeroRoutesReturnsBase() {
        assertEquals(base, throttle.effectiveInterval(0))
    }

    @Test
    fun multiplierReturnsCorrectValue() {
        assertEquals(1.0, throttle.multiplier(50))
        assertEquals(1.0, throttle.multiplier(100))
        assertEquals(2.0, throttle.multiplier(200))
        assertEquals(5.0, throttle.multiplier(600))
    }

    @Test
    fun veryLargeRouteCountDoesNotOverflow() {
        val t = GossipThrottle(baseIntervalMs = Long.MAX_VALUE / 10)
        // Capped at 5× so result = (MAX/10) * 5 = MAX/2, no overflow
        val result = t.effectiveInterval(Int.MAX_VALUE)
        assertEquals((Long.MAX_VALUE / 10).toDouble() * 5.0, result.toDouble(), 1.0)
    }

    @Test
    fun customThresholdAndMaxMultiplier() {
        val t = GossipThrottle(baseIntervalMs = 200, scaleThreshold = 50, maxMultiplier = 3.0)
        // Below custom threshold
        assertEquals(200L, t.effectiveInterval(30))
        // Above custom threshold: 75/50 = 1.5× → 300
        assertEquals(300L, t.effectiveInterval(75))
        // Capped at 3×: 200 * 3 = 600
        assertEquals(600L, t.effectiveInterval(200))
    }
}
