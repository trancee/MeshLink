package io.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleAutoRecoveryTest {

    @Test
    fun activityResetsSilenceTimer() {
        var now = 0L
        val recovery = BleAutoRecovery(silenceThresholdMillis = 100, clock = { now })

        now = 90L
        recovery.recordActivity()
        now = 150L

        assertEquals(60L, recovery.silenceDurationMillis(), "Duration should be since last activity")
        assertFalse(recovery.isSilent(), "Should not be silent after recent activity")
    }

    @Test
    fun isSilentReturnsFalseWithinThreshold() {
        var now = 0L
        val recovery = BleAutoRecovery(silenceThresholdMillis = 100, clock = { now })

        now = 99L
        assertFalse(recovery.isSilent(), "Should not be silent at 99ms with 100ms threshold")
    }

    @Test
    fun isSilentReturnsTrueAfterThreshold() {
        var now = 0L
        val recovery = BleAutoRecovery(silenceThresholdMillis = 100, clock = { now })

        now = 100L
        assertTrue(recovery.isSilent(), "Should be silent at exactly 100ms threshold")

        now = 200L
        assertTrue(recovery.isSilent(), "Should be silent well past threshold")
    }

    @Test
    fun canRecoverTrueInitially() {
        val recovery = BleAutoRecovery(clock = { 0L })
        assertTrue(recovery.canRecover(), "Should allow recovery initially with no prior attempts")
    }

    @Test
    fun canRecoverFalseAfterThreeRecoveriesInSameHour() {
        var now = 0L
        val recovery = BleAutoRecovery(maxRecoveriesPerHour = 3, clock = { now })

        assertTrue(recovery.recordRecovery(), "1st recovery should be allowed")
        now = 1000L
        assertTrue(recovery.recordRecovery(), "2nd recovery should be allowed")
        now = 2000L
        assertTrue(recovery.recordRecovery(), "3rd recovery should be allowed")
        now = 3000L
        assertFalse(recovery.canRecover(), "4th recovery should be blocked")
    }

    @Test
    fun recordRecoveryReturnsFalseWhenRateLimited() {
        var now = 0L
        val recovery = BleAutoRecovery(maxRecoveriesPerHour = 2, clock = { now })

        assertTrue(recovery.recordRecovery(), "1st recovery allowed")
        now = 100L
        assertTrue(recovery.recordRecovery(), "2nd recovery allowed")
        now = 200L
        assertFalse(recovery.recordRecovery(), "3rd recovery rejected (rate limited)")
    }

    @Test
    fun oldRecoveriesExpireAllowingNewOnes() {
        var now = 0L
        val recovery = BleAutoRecovery(maxRecoveriesPerHour = 3, clock = { now })

        recovery.recordRecovery()
        now = 1000L
        recovery.recordRecovery()
        now = 2000L
        recovery.recordRecovery()
        assertFalse(recovery.canRecover(), "Should be rate limited after 3 recoveries")

        // Advance past 1 hour from the first recovery
        now = 3_600_001L
        assertTrue(recovery.canRecover(), "Should allow recovery after oldest expires")
        assertEquals(2, recovery.recoveriesInLastHour(), "Only 2 recoveries within the last hour")
    }

    @Test
    fun silenceDurationMsTracksCorrectly() {
        var now = 0L
        val recovery = BleAutoRecovery(clock = { now })

        assertEquals(0L, recovery.silenceDurationMillis(), "Initially zero")

        now = 500L
        assertEquals(500L, recovery.silenceDurationMillis(), "500ms since construction")

        recovery.recordActivity()
        assertEquals(0L, recovery.silenceDurationMillis(), "Reset to zero after activity")

        now = 750L
        assertEquals(250L, recovery.silenceDurationMillis(), "250ms since last activity")
    }

    @Test
    fun recoveriesInLastHourCountsCorrectly() {
        var now = 0L
        val recovery = BleAutoRecovery(maxRecoveriesPerHour = 10, clock = { now })

        assertEquals(0, recovery.recoveriesInLastHour(), "No recoveries initially")

        recovery.recordRecovery()
        now = 1000L
        recovery.recordRecovery()
        now = 2000L
        recovery.recordRecovery()

        assertEquals(3, recovery.recoveriesInLastHour(), "3 recoveries recorded")

        // Expire the first two
        now = 3_601_001L
        assertEquals(1, recovery.recoveriesInLastHour(), "Only 1 recovery within last hour")

        // Expire all
        now = 3_602_001L
        assertEquals(0, recovery.recoveriesInLastHour(), "All recoveries expired")
    }

    @Test
    fun resetClearsAllState() {
        var now = 0L
        val recovery = BleAutoRecovery(
            silenceThresholdMillis = 100,
            maxRecoveriesPerHour = 3,
            clock = { now },
        )

        now = 200L
        recovery.recordRecovery()
        recovery.recordRecovery()
        assertTrue(recovery.isSilent(), "Should be silent before reset")
        assertEquals(2, recovery.recoveriesInLastHour(), "2 recoveries before reset")

        recovery.reset()
        assertFalse(recovery.isSilent(), "Should not be silent after reset")
        assertEquals(0, recovery.recoveriesInLastHour(), "No recoveries after reset")
        assertEquals(0L, recovery.silenceDurationMillis(), "Silence duration reset to zero")
    }

    @Test
    fun multipleActivityRecordingsKeepTimerFresh() {
        var now = 0L
        val recovery = BleAutoRecovery(silenceThresholdMillis = 100, clock = { now })

        // Keep recording activity before the threshold
        now = 50L
        recovery.recordActivity()
        now = 100L
        recovery.recordActivity()
        now = 150L
        recovery.recordActivity()
        now = 200L
        recovery.recordActivity()

        // Only 0ms since last activity at now=200
        assertFalse(recovery.isSilent(), "Continuous activity should prevent silence")
        assertEquals(0L, recovery.silenceDurationMillis())

        // Now let silence happen from 200ms
        now = 300L
        assertTrue(recovery.isSilent(), "100ms of silence should trigger")
    }

    @Test
    fun customThresholdsWork() {
        var now = 0L
        val recovery = BleAutoRecovery(
            silenceThresholdMillis = 500,
            maxRecoveriesPerHour = 1,
            clock = { now },
        )

        // Custom silence threshold
        now = 499L
        assertFalse(recovery.isSilent(), "Just under custom threshold")
        now = 500L
        assertTrue(recovery.isSilent(), "At custom threshold")

        // Custom max recoveries
        assertTrue(recovery.recordRecovery(), "1st recovery allowed with limit=1")
        assertFalse(recovery.recordRecovery(), "2nd recovery blocked with limit=1")

        // Verify the object accepted the custom cooldown (no public accessor needed; construction succeeds)
    }

    @Test
    fun boundaryExactlyAtOneHourRecoveryExpiry() {
        var now = 0L
        val recovery = BleAutoRecovery(maxRecoveriesPerHour = 1, clock = { now })

        recovery.recordRecovery()
        assertFalse(recovery.canRecover(), "Rate limited immediately after recovery")

        // Exactly at 1 hour: the timestamp at 0 should expire (<=)
        now = 3_600_000L
        assertTrue(recovery.canRecover(), "Recovery recorded at t=0 should expire at t=3600000")
    }

    @Test
    fun isSilentBoundaryExactlyAtThreshold() {
        var now = 0L
        val recovery = BleAutoRecovery(silenceThresholdMillis = 60_000L, clock = { now })

        now = 59_999L
        assertFalse(recovery.isSilent(), "1ms before threshold")

        now = 60_000L
        assertTrue(recovery.isSilent(), "Exactly at threshold")

        now = 60_001L
        assertTrue(recovery.isSilent(), "1ms past threshold")
    }
}
