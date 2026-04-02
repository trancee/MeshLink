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
        assertEquals(250L, PowerProfile.PERFORMANCE.advertisingIntervalMillis)
    }

    @Test
    fun balanced_advertisingInterval_500ms() {
        assertEquals(500L, PowerProfile.BALANCED.advertisingIntervalMillis)
    }

    @Test
    fun powerSaver_advertisingInterval_1000ms() {
        assertEquals(1_000L, PowerProfile.POWER_SAVER.advertisingIntervalMillis)
    }

    // ── Scan timing ─────────────────────────────────────────────

    @Test
    fun performance_scanOn4s_scanOff1s() {
        assertEquals(4_000L, PowerProfile.PERFORMANCE.scanOnMillis)
        assertEquals(1_000L, PowerProfile.PERFORMANCE.scanOffMillis)
    }

    @Test
    fun balanced_scanOn3s_scanOff3s() {
        assertEquals(3_000L, PowerProfile.BALANCED.scanOnMillis)
        assertEquals(3_000L, PowerProfile.BALANCED.scanOffMillis)
    }

    @Test
    fun powerSaver_scanOn1s_scanOff5s() {
        assertEquals(1_000L, PowerProfile.POWER_SAVER.scanOnMillis)
        assertEquals(5_000L, PowerProfile.POWER_SAVER.scanOffMillis)
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
            advertisingIntervalMillis = 100L,
            scanOnMillis = 2_000L,
            scanOffMillis = 8_000L,
            maxConnections = 2,
        )
        assertEquals(20, custom.scanDutyPercent)
    }

    @Test
    fun customProfile_zeroDuration_scanDutyIsZero() {
        val custom = PowerProfile(
            advertisingIntervalMillis = 100L,
            scanOnMillis = 0L,
            scanOffMillis = 0L,
            maxConnections = 1,
        )
        assertEquals(0, custom.scanDutyPercent)
    }

    // ── Data class equality ─────────────────────────────────────

    @Test
    fun dataClassEquality_sameValues_areEqual() {
        val a = PowerProfile(250L, 4_000L, 1_000L, 8)
        val b = PowerProfile(250L, 4_000L, 1_000L, 8)
        assertEquals(a, b)
    }
}
