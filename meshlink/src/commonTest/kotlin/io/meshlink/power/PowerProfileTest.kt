package io.meshlink.power

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerProfileTest {

    // ── forMode returns correct profile ─────────────────────────

    @Test
    fun forMode_performance_returnsPerformanceProfile() {
        val p = PowerProfile.forMode(PowerMode.PERFORMANCE)
        assertEquals(PowerProfile.PERFORMANCE, p)
    }

    @Test
    fun forMode_balanced_returnsBalancedProfile() {
        val p = PowerProfile.forMode(PowerMode.BALANCED)
        assertEquals(PowerProfile.BALANCED, p)
    }

    @Test
    fun forMode_powerSaver_returnsPowerSaverProfile() {
        val p = PowerProfile.forMode(PowerMode.POWER_SAVER)
        assertEquals(PowerProfile.POWER_SAVER, p)
    }

    // ── Advertising intervals ───────────────────────────────────

    @Test
    fun performance_advertisingInterval_250ms() {
        assertEquals(250L, PowerProfile.PERFORMANCE.advertisingIntervalMs)
    }

    @Test
    fun balanced_advertisingInterval_500ms() {
        assertEquals(500L, PowerProfile.BALANCED.advertisingIntervalMs)
    }

    @Test
    fun powerSaver_advertisingInterval_1000ms() {
        assertEquals(1_000L, PowerProfile.POWER_SAVER.advertisingIntervalMs)
    }

    // ── Scan timing ─────────────────────────────────────────────

    @Test
    fun performance_scanOn4s_scanOff1s() {
        assertEquals(4_000L, PowerProfile.PERFORMANCE.scanOnMs)
        assertEquals(1_000L, PowerProfile.PERFORMANCE.scanOffMs)
    }

    @Test
    fun balanced_scanOn3s_scanOff3s() {
        assertEquals(3_000L, PowerProfile.BALANCED.scanOnMs)
        assertEquals(3_000L, PowerProfile.BALANCED.scanOffMs)
    }

    @Test
    fun powerSaver_scanOn1s_scanOff5s() {
        assertEquals(1_000L, PowerProfile.POWER_SAVER.scanOnMs)
        assertEquals(5_000L, PowerProfile.POWER_SAVER.scanOffMs)
    }

    // ── Scan duty percent ───────────────────────────────────────

    @Test
    fun performance_scanDuty_80percent() {
        assertEquals(80, PowerProfile.PERFORMANCE.scanDutyPercent)
    }

    @Test
    fun balanced_scanDuty_50percent() {
        assertEquals(50, PowerProfile.BALANCED.scanDutyPercent)
    }

    @Test
    fun powerSaver_scanDuty_16percent() {
        assertEquals(16, PowerProfile.POWER_SAVER.scanDutyPercent)
    }

    // ── Max connections ─────────────────────────────────────────

    @Test
    fun performance_maxConnections_8() {
        assertEquals(8, PowerProfile.PERFORMANCE.maxConnections)
    }

    @Test
    fun balanced_maxConnections_4() {
        assertEquals(4, PowerProfile.BALANCED.maxConnections)
    }

    @Test
    fun powerSaver_maxConnections_1() {
        assertEquals(1, PowerProfile.POWER_SAVER.maxConnections)
    }

    // ── Gossip multiplier ───────────────────────────────────────

    @Test
    fun performance_gossipMultiplier_1x() {
        assertEquals(1.0, PowerProfile.PERFORMANCE.gossipMultiplier)
    }

    @Test
    fun balanced_gossipMultiplier_1_5x() {
        assertEquals(1.5, PowerProfile.BALANCED.gossipMultiplier)
    }

    @Test
    fun powerSaver_gossipMultiplier_3x() {
        assertEquals(3.0, PowerProfile.POWER_SAVER.gossipMultiplier)
    }

    // ── Aggressive discovery ────────────────────────────────────

    @Test
    fun performance_isAggressiveDiscovery() {
        assertTrue(PowerProfile.PERFORMANCE.isAggressiveDiscovery)
    }

    @Test
    fun balanced_isNotAggressiveDiscovery() {
        assertFalse(PowerProfile.BALANCED.isAggressiveDiscovery)
    }

    @Test
    fun powerSaver_isNotAggressiveDiscovery() {
        assertFalse(PowerProfile.POWER_SAVER.isAggressiveDiscovery)
    }

    // ── Custom profile ──────────────────────────────────────────

    @Test
    fun customProfile_computesScanDutyCorrectly() {
        val custom = PowerProfile(
            advertisingIntervalMs = 100L,
            scanOnMs = 2_000L,
            scanOffMs = 8_000L,
            maxConnections = 2,
            gossipMultiplier = 2.0,
        )
        assertEquals(20, custom.scanDutyPercent)
    }

    @Test
    fun customProfile_zeroDuration_scanDutyIsZero() {
        val custom = PowerProfile(
            advertisingIntervalMs = 100L,
            scanOnMs = 0L,
            scanOffMs = 0L,
            maxConnections = 1,
            gossipMultiplier = 1.0,
        )
        assertEquals(0, custom.scanDutyPercent)
    }

    // ── Data class equality ─────────────────────────────────────

    @Test
    fun dataClassEquality_sameValues_areEqual() {
        val a = PowerProfile(250L, 4_000L, 1_000L, 8, 1.0)
        val b = PowerProfile(250L, 4_000L, 1_000L, 8, 1.0)
        assertEquals(a, b)
    }
}
