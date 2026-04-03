package io.meshlink.routing

import io.meshlink.util.ByteArrayKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteCostCalculatorTest {

    private fun key(id: Int): ByteArrayKey =
        ByteArrayKey(ByteArray(12) { id.toByte() })

    private val peerA = key(0x0A)
    private val peerB = key(0x0B)

    @Test
    fun defaultCostWhenNoData() {
        val calc = RouteCostCalculator()
        assertEquals(RouteCostCalculator.DEFAULT_COST, calc.computeCost(peerA))
        assertFalse(calc.hasData(peerA))
    }

    @Test
    fun rssiBaseCostFormula() {
        var now = 1000L
        val calc = RouteCostCalculator(clock = { now }, keepaliveIntervalMillis = 15_000L)

        // Excellent signal: RSSI = -50 → base_cost = max(1, round(0/5)) = 1
        calc.recordMeasurement(peerA, rssi = -50, lossRate = 0.0)
        // New link: stability penalty = 3, freshness = 1.0
        // cost = 1 * 1.0 * 1.0 + 3 = 4.0
        assertEquals(4.0, calc.computeCost(peerA))

        // Weak signal: RSSI = -80 → base_cost = max(1, round(30/5)) = 6
        calc.recordMeasurement(peerB, rssi = -80, lossRate = 0.0)
        // cost = 6 * 1.0 * 1.0 + 3 = 9.0
        assertEquals(9.0, calc.computeCost(peerB))
    }

    @Test
    fun lossRateMultiplier() {
        var now = 1000L
        val calc = RouteCostCalculator(
            clock = { now },
            keepaliveIntervalMillis = 15_000L,
            routeCostChangeThreshold = 0.0, // disable dampening for test
        )

        // RSSI = -60 → base_cost = 2, loss = 20% → multiplier = 3.0
        calc.recordMeasurement(peerA, rssi = -60, lossRate = 0.20)
        // cost = 2 * 3.0 * 1.0 + 3 = 9.0
        assertEquals(9.0, calc.computeCost(peerA))

        // No loss
        calc.recordMeasurement(peerB, rssi = -60, lossRate = 0.0)
        // cost = 2 * 1.0 * 1.0 + 3 = 5.0
        assertEquals(5.0, calc.computeCost(peerB))
    }

    @Test
    fun stabilityPenaltyDecays() {
        var now = 1000L
        val calc = RouteCostCalculator(
            clock = { now },
            keepaliveIntervalMillis = 15_000L,
            routeCostChangeThreshold = 0.0,
        )

        calc.recordMeasurement(peerA, rssi = -50, lossRate = 0.0)
        // New link: stability = 3
        assertEquals(4.0, calc.computeCost(peerA)) // 1 + 3

        calc.recordStableInterval(peerA) // stable = 1, penalty = 2
        assertEquals(3.0, calc.computeCost(peerA)) // 1 + 2

        calc.recordStableInterval(peerA) // stable = 2, penalty = 1
        assertEquals(2.0, calc.computeCost(peerA)) // 1 + 1

        calc.recordStableInterval(peerA) // stable = 3, penalty = 0
        assertEquals(1.0, calc.computeCost(peerA)) // 1 + 0
    }

    @Test
    fun disconnectResetsStability() {
        var now = 1000L
        val calc = RouteCostCalculator(
            clock = { now },
            keepaliveIntervalMillis = 15_000L,
            routeCostChangeThreshold = 0.0,
        )

        calc.recordMeasurement(peerA, rssi = -50, lossRate = 0.0)
        repeat(3) { calc.recordStableInterval(peerA) }
        assertEquals(1.0, calc.computeCost(peerA)) // fully stable

        calc.recordDisconnect(peerA)
        assertEquals(4.0, calc.computeCost(peerA)) // stability penalty = 3 again
    }

    @Test
    fun freshnessPenaltyForStaleLinks() {
        var now = 1000L
        val calc = RouteCostCalculator(
            clock = { now },
            keepaliveIntervalMillis = 15_000L,
            routeCostChangeThreshold = 0.0,
        )

        calc.recordMeasurement(peerA, rssi = -50, lossRate = 0.0)
        repeat(3) { calc.recordStableInterval(peerA) }
        assertEquals(1.0, calc.computeCost(peerA)) // fresh

        // Advance past 2× keepalive (30s) → stale
        now += 31_000
        // cost = 1 * 1.0 * 1.5 + 0 = 1.5
        assertEquals(1.5, calc.computeCost(peerA))
    }

    @Test
    fun changeThresholdDampening() {
        var now = 1000L
        val calc = RouteCostCalculator(
            clock = { now },
            keepaliveIntervalMillis = 15_000L,
            routeCostChangeThreshold = 0.30, // 30% threshold
        )

        calc.recordMeasurement(peerA, rssi = -70, lossRate = 0.0)
        val initial = calc.computeCost(peerA) // 4*1*1 + 3 = 7.0

        // Small RSSI change: -70 → -71 → base cost still rounds to 4
        calc.recordMeasurement(peerA, rssi = -71, lossRate = 0.0)
        assertEquals(initial, calc.computeCost(peerA), "Small change should be dampened")
    }

    @Test
    fun removePeerClearsState() {
        val calc = RouteCostCalculator()
        calc.recordMeasurement(peerA, rssi = -60, lossRate = 0.0)
        assertTrue(calc.hasData(peerA))

        calc.removePeer(peerA)
        assertFalse(calc.hasData(peerA))
        assertEquals(RouteCostCalculator.DEFAULT_COST, calc.computeCost(peerA))
    }

    @Test
    fun clearRemovesAllState() {
        val calc = RouteCostCalculator()
        calc.recordMeasurement(peerA, rssi = -60, lossRate = 0.0)
        calc.recordMeasurement(peerB, rssi = -70, lossRate = 0.1)
        assertTrue(calc.hasData(peerA))
        assertTrue(calc.hasData(peerB))

        calc.clear()
        assertFalse(calc.hasData(peerA))
        assertFalse(calc.hasData(peerB))
    }

    @Test
    fun marginalRssiWithHighLoss() {
        var now = 1000L
        val calc = RouteCostCalculator(
            clock = { now },
            keepaliveIntervalMillis = 15_000L,
            routeCostChangeThreshold = 0.0,
        )

        // Worst case: RSSI = -90 (base=8), loss=20% (×3.0), new link (+3)
        calc.recordMeasurement(peerA, rssi = -90, lossRate = 0.20)
        // cost = 8 * 3.0 * 1.0 + 3 = 27.0
        assertEquals(27.0, calc.computeCost(peerA))
    }

    @Test
    fun lossRateClampedToValidRange() {
        var now = 1000L
        val calc = RouteCostCalculator(
            clock = { now },
            keepaliveIntervalMillis = 15_000L,
            routeCostChangeThreshold = 0.0,
        )

        // Loss rate > 1.0 should be clamped to 1.0
        calc.recordMeasurement(peerA, rssi = -50, lossRate = 2.0)
        // base=1, loss multiplier = 1+(1.0*10) = 11.0, fresh=1.0, stability=3
        // cost = 1 * 11.0 * 1.0 + 3 = 14.0
        assertEquals(14.0, calc.computeCost(peerA))
    }
}
