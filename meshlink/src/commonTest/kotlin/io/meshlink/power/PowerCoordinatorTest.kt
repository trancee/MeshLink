package io.meshlink.power

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerCoordinatorTest {

    private fun coordinator(
        clock: () -> Long = { 0L },
        hysteresisMillis: Long = 0L,
    ) = PowerCoordinator(clock = clock, hysteresisMillis = hysteresisMillis)

    // ── 1. Mode transitions ───────────────────────────────────────

    @Test
    fun initialModeIsPerformance() {
        val pc = coordinator()
        assertEquals("PERFORMANCE", pc.currentMode)
    }

    @Test
    fun lowBatteryTransitionsToBalancedThenPowerSaver() {
        val pc = coordinator()
        // First call sets pending downgrade, second applies it (hysteresis even at 0ms)
        pc.updateBattery(50, isCharging = false)
        val r1 = pc.updateBattery(50, isCharging = false)
        assertIs<ModeChangeResult.Changed>(r1)
        assertEquals("PERFORMANCE", r1.oldMode)
        assertEquals("BALANCED", r1.newMode)
        assertEquals("BALANCED", pc.currentMode)

        pc.updateBattery(20, isCharging = false)
        pc.updateBattery(20, isCharging = false)
        assertEquals("POWER_SAVER", pc.currentMode)
    }

    @Test
    fun chargingOverridesToPerformance() {
        val pc = coordinator()
        pc.updateBattery(20, isCharging = false)
        pc.updateBattery(20, isCharging = false)
        assertEquals("POWER_SAVER", pc.currentMode)

        // Charging upgrade is immediate (no hysteresis)
        val result = pc.updateBattery(20, isCharging = true)
        assertIs<ModeChangeResult.Changed>(result)
        assertEquals("PERFORMANCE", pc.currentMode)
    }

    @Test
    fun sameModeIsUnchanged() {
        val pc = coordinator()
        val result = pc.updateBattery(90, isCharging = false)
        assertIs<ModeChangeResult.Unchanged>(result)
        assertEquals("PERFORMANCE", pc.currentMode)
    }

    @Test
    fun hysteresisDelaysDowngrade() {
        var now = 0L
        val pc = PowerCoordinator(clock = { now }, hysteresisMillis = 5000L)

        // First call: requests downgrade, but hysteresis delays it
        pc.updateBattery(50, isCharging = false)
        assertEquals("PERFORMANCE", pc.currentMode)

        // Still within hysteresis window
        now = 3000L
        pc.updateBattery(50, isCharging = false)
        assertEquals("PERFORMANCE", pc.currentMode)

        // After hysteresis window
        now = 5000L
        val result = pc.updateBattery(50, isCharging = false)
        assertIs<ModeChangeResult.Changed>(result)
        assertEquals("BALANCED", pc.currentMode)
    }

    // ── 2. Memory pressure evaluation ─────────────────────────────

    @Test
    fun noPressureBelow50Percent() {
        val pc = coordinator()
        assertNull(pc.evaluatePressure(49))
        assertNull(pc.evaluatePressure(0))
    }

    @Test
    fun moderatePressureAt50Percent() {
        val pc = coordinator()
        assertEquals(MemoryPressure.MODERATE, pc.evaluatePressure(50))
        assertEquals(MemoryPressure.MODERATE, pc.evaluatePressure(69))
    }

    @Test
    fun highPressureAt70Percent() {
        val pc = coordinator()
        assertEquals(MemoryPressure.HIGH, pc.evaluatePressure(70))
        assertEquals(MemoryPressure.HIGH, pc.evaluatePressure(89))
    }

    @Test
    fun criticalPressureAt90Percent() {
        val pc = coordinator()
        assertEquals(MemoryPressure.CRITICAL, pc.evaluatePressure(90))
        assertEquals(MemoryPressure.CRITICAL, pc.evaluatePressure(100))
    }

    // ── 3. Shed actions ───────────────────────────────────────────

    @Test
    fun moderateShedsRelayBuffersOnly() {
        val pc = coordinator()
        val actions = pc.computeShedActions(MemoryPressure.MODERATE, 5, 1000, 10)
        assertEquals(1, actions.size)
        assertEquals(ShedAction.RELAY_BUFFERS_CLEARED, actions[0].action)
        assertEquals(5, actions[0].count)
    }

    @Test
    fun highShedsRelayAndDedup() {
        val pc = coordinator()
        val actions = pc.computeShedActions(MemoryPressure.HIGH, 5, 1000, 10)
        assertEquals(2, actions.size)
        assertEquals(ShedAction.RELAY_BUFFERS_CLEARED, actions[0].action)
        assertEquals(ShedAction.DEDUP_TRIMMED, actions[1].action)
        assertEquals(1000, actions[1].count)
    }

    @Test
    fun criticalShedsAllTiers() {
        val pc = coordinator()
        val actions = pc.computeShedActions(MemoryPressure.CRITICAL, 5, 1000, 10)
        assertEquals(3, actions.size)
        assertEquals(ShedAction.RELAY_BUFFERS_CLEARED, actions[0].action)
        assertEquals(ShedAction.DEDUP_TRIMMED, actions[1].action)
        assertEquals(ShedAction.CONNECTIONS_DROPPED, actions[2].action)
        assertEquals(10, actions[2].count)
    }

    // ── 4. Buffer pressure warning ────────────────────────────────

    @Test
    fun noWarningBelow80Percent() {
        val pc = coordinator()
        assertFalse(pc.shouldWarnBufferPressure(79, 100))
    }

    @Test
    fun warningAt80Percent() {
        val pc = coordinator()
        assertTrue(pc.shouldWarnBufferPressure(80, 100))
        assertTrue(pc.shouldWarnBufferPressure(100, 100))
    }

    @Test
    fun noWarningWhenCapacityDisabled() {
        val pc = coordinator()
        assertFalse(pc.shouldWarnBufferPressure(100, 0))
    }

    // ── 5. Custom power mode override ─────────────────────────────

    @Test
    fun customPowerMode_overrides_battery_logic() {
        val pc = coordinator()
        pc.setCustomPowerMode(PowerMode.POWER_SAVER)
        assertEquals("POWER_SAVER", pc.currentMode)

        val result = pc.updateBattery(100, isCharging = true)
        assertIs<ModeChangeResult.Unchanged>(result)
        assertEquals("POWER_SAVER", pc.currentMode)
    }

    @Test
    fun clearCustomPowerMode_resumes_battery_logic() {
        val pc = coordinator()
        pc.setCustomPowerMode(PowerMode.POWER_SAVER)
        assertEquals("POWER_SAVER", pc.currentMode)

        pc.setCustomPowerMode(null)
        assertNull(pc.customPowerMode)

        // Battery logic resumes — charging should switch to PERFORMANCE
        val result = pc.updateBattery(100, isCharging = true)
        assertIs<ModeChangeResult.Changed>(result)
        assertEquals("PERFORMANCE", pc.currentMode)
    }

    @Test
    fun setCustomPowerMode_returns_changed_when_different() {
        val pc = coordinator()
        assertEquals("PERFORMANCE", pc.currentMode)

        val r1 = pc.setCustomPowerMode(PowerMode.BALANCED)
        assertIs<ModeChangeResult.Changed>(r1)
        assertEquals("PERFORMANCE", r1.oldMode)
        assertEquals("BALANCED", r1.newMode)

        // Same mode again → Unchanged
        val r2 = pc.setCustomPowerMode(PowerMode.BALANCED)
        assertIs<ModeChangeResult.Unchanged>(r2)

        // Clear back — mode stays BALANCED until battery update
        val r3 = pc.setCustomPowerMode(null)
        assertIs<ModeChangeResult.Unchanged>(r3)
        assertEquals("BALANCED", pc.currentMode)
    }
}
