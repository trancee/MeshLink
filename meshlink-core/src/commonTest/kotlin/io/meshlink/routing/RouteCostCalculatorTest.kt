package io.meshlink.routing

import io.meshlink.routing.RouteCostCalculator.Companion.RSSI_MAX
import io.meshlink.routing.RouteCostCalculator.Companion.RSSI_MIN
import io.meshlink.routing.RoutingTable.Companion.MAX_ROUTE_COST
import kotlin.test.Test
import kotlin.test.assertTrue

class RouteCostCalculatorTest {

    private val calc = RouteCostCalculator()

    // ── Perfect conditions ────────────────────────────────────────────

    @Test
    fun perfectConditionsYieldLowCost() {
        val cost = calc.calculateCost(rssi = -30, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        assertTrue(cost > 0.0, "cost must be positive")
        assertTrue(cost < 0.5, "perfect conditions should yield very low cost, got $cost")
    }

    // ── RSSI (signal strength) ────────────────────────────────────────

    @Test
    fun weakerSignalYieldsHigherCost() {
        val strong = calc.calculateCost(rssi = -30, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        val weak = calc.calculateCost(rssi = -80, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        assertTrue(weak > strong, "weak RSSI ($weak) should cost more than strong RSSI ($strong)")
    }

    @Test
    fun strongestRssiProducesLowestBaseCost() {
        val strongest = calc.calculateCost(rssi = RSSI_MAX, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        val mid = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        assertTrue(strongest < mid)
    }

    @Test
    fun weakestRssiProducesHighestBaseCost() {
        val weakest = calc.calculateCost(rssi = RSSI_MIN, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        val mid = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        assertTrue(weakest > mid)
    }

    // ── Packet loss ───────────────────────────────────────────────────

    @Test
    fun packetLossProportionallyIncreasesCost() {
        val noLoss = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        val halfLoss = calc.calculateCost(rssi = -60, packetLossRate = 0.5, ageMs = 0, flapCount = 0)
        // 50% loss → loss_multiplier = 2.0 → cost should double
        val ratio = halfLoss / noLoss
        assertTrue(ratio in 1.9..2.1, "50%% loss should ~double cost, ratio = $ratio")
    }

    @Test
    fun highPacketLossYieldsHighCost() {
        val low = calc.calculateCost(rssi = -60, packetLossRate = 0.1, ageMs = 0, flapCount = 0)
        val high = calc.calculateCost(rssi = -60, packetLossRate = 0.9, ageMs = 0, flapCount = 0)
        assertTrue(high > low * 5, "90%% loss should be much more expensive than 10%% loss")
    }

    // ── Freshness ─────────────────────────────────────────────────────

    @Test
    fun staleRouteYieldsHigherCost() {
        val fresh = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        val stale = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 120_000, flapCount = 0)
        assertTrue(stale > fresh, "stale route ($stale) should cost more than fresh ($fresh)")
    }

    @Test
    fun oneHalfLifeDoublesCostContribution() {
        val fresh = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        val oneHalfLife = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 60_000, flapCount = 0)
        // freshness_penalty goes from 1.0 to 2.0 → cost doubles
        val ratio = oneHalfLife / fresh
        assertTrue(ratio in 1.9..2.1, "one half-life should ~double cost, ratio = $ratio")
    }

    // ── Stability ─────────────────────────────────────────────────────

    @Test
    fun unstableRouteYieldsHigherCost() {
        val stable = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        val flappy = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = 10)
        assertTrue(flappy > stable, "unstable route ($flappy) should cost more than stable ($stable)")
    }

    @Test
    fun stabilityPenaltyIsAdditive() {
        val base = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        val withFlaps = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = 5)
        val expectedIncrease = 5 * 0.1
        val actualIncrease = withFlaps - base
        assertTrue(
            kotlin.math.abs(actualIncrease - expectedIncrease) < 0.001,
            "stability penalty should add ${expectedIncrease}, got $actualIncrease"
        )
    }

    // ── Input clamping ────────────────────────────────────────────────

    @Test
    fun rssiClampedToValidRange() {
        val belowMin = calc.calculateCost(rssi = -150, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        val atMin = calc.calculateCost(rssi = RSSI_MIN, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        assertTrue(belowMin == atMin, "RSSI below min should be clamped to min")

        val aboveMax = calc.calculateCost(rssi = 0, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        val atMax = calc.calculateCost(rssi = RSSI_MAX, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        assertTrue(aboveMax == atMax, "RSSI above max should be clamped to max")
    }

    @Test
    fun packetLossRateClampedToValidRange() {
        val negative = calc.calculateCost(rssi = -60, packetLossRate = -0.5, ageMs = 0, flapCount = 0)
        val zero = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        assertTrue(negative == zero, "negative loss rate should clamp to 0.0")

        val overOne = calc.calculateCost(rssi = -60, packetLossRate = 1.5, ageMs = 0, flapCount = 0)
        val atMax = calc.calculateCost(rssi = -60, packetLossRate = 0.99, ageMs = 0, flapCount = 0)
        assertTrue(overOne == atMax, "loss rate > 1.0 should clamp to 0.99")
    }

    @Test
    fun negativeAgeClampedToZero() {
        val negAge = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = -1000, flapCount = 0)
        val zeroAge = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        assertTrue(negAge == zeroAge, "negative age should clamp to 0")
    }

    @Test
    fun negativeFlapCountClampedToZero() {
        val negFlaps = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = -5)
        val zeroFlaps = calc.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        assertTrue(negFlaps == zeroFlaps, "negative flap count should clamp to 0")
    }

    // ── Bounds guarantee ──────────────────────────────────────────────

    @Test
    fun costAlwaysPositive() {
        val cost = calc.calculateCost(rssi = RSSI_MAX, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        assertTrue(cost > 0.0, "cost must always be positive, got $cost")
    }

    @Test
    fun costAlwaysFinite() {
        // Even with extreme inputs, cost must be finite
        val cost = calc.calculateCost(rssi = RSSI_MIN, packetLossRate = 0.99, ageMs = Long.MAX_VALUE, flapCount = Int.MAX_VALUE)
        assertTrue(cost.isFinite(), "cost must be finite, got $cost")
    }

    @Test
    fun costNeverExceedsMaxRouteCost() {
        val cost = calc.calculateCost(rssi = RSSI_MIN, packetLossRate = 0.99, ageMs = Long.MAX_VALUE, flapCount = Int.MAX_VALUE)
        assertTrue(cost <= MAX_ROUTE_COST, "cost ($cost) must not exceed MAX_ROUTE_COST ($MAX_ROUTE_COST)")
    }

    @Test
    fun worstCaseHitsMaxRouteCost() {
        val cost = calc.calculateCost(rssi = RSSI_MIN, packetLossRate = 0.99, ageMs = Long.MAX_VALUE, flapCount = Int.MAX_VALUE)
        assertTrue(cost == MAX_ROUTE_COST, "worst case should be clamped to MAX_ROUTE_COST")
    }

    // ── Configurable parameters ───────────────────────────────────────

    @Test
    fun customFreshnessHalfLifeAffectsCost() {
        val fast = RouteCostCalculator(freshnessHalfLifeMs = 10_000)
        val slow = RouteCostCalculator(freshnessHalfLifeMs = 120_000)
        val age = 30_000L
        val costFast = fast.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = age, flapCount = 0)
        val costSlow = slow.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = age, flapCount = 0)
        assertTrue(costFast > costSlow, "shorter half-life should penalize staleness more")
    }

    @Test
    fun customStabilityPenaltyAffectsCost() {
        val harsh = RouteCostCalculator(stabilityPenaltyPerFlap = 1.0)
        val lenient = RouteCostCalculator(stabilityPenaltyPerFlap = 0.01)
        val costHarsh = harsh.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = 5)
        val costLenient = lenient.calculateCost(rssi = -60, packetLossRate = 0.0, ageMs = 0, flapCount = 5)
        assertTrue(costHarsh > costLenient, "higher penalty per flap should yield higher cost")
    }

    // ── Boundary RSSI values ──────────────────────────────────────────

    @Test
    fun boundaryRssiValuesProduceValidCosts() {
        val atMin = calc.calculateCost(rssi = RSSI_MIN, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        val atMax = calc.calculateCost(rssi = RSSI_MAX, packetLossRate = 0.0, ageMs = 0, flapCount = 0)
        assertTrue(atMin > 0.0 && atMin <= MAX_ROUTE_COST)
        assertTrue(atMax > 0.0 && atMax <= MAX_ROUTE_COST)
        assertTrue(atMin > atMax, "min RSSI should cost more than max RSSI")
    }

    // ── Combined worst-case factors ───────────────────────────────────

    @Test
    fun allBadFactorsCombineCorrectly() {
        val cost = calc.calculateCost(rssi = -90, packetLossRate = 0.8, ageMs = 300_000, flapCount = 20)
        assertTrue(cost > 10.0, "all bad factors combined should yield high cost, got $cost")
        assertTrue(cost <= MAX_ROUTE_COST)
    }
}
