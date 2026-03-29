package io.meshlink.power

import kotlin.test.Test
import kotlin.test.assertEquals

class PowerModeEngineTest {

    @Test
    fun batteryThresholdsWithDownwardHysteresis() {
        var now = 0L
        val engine = PowerModeEngine(clock = { now })

        // High battery → Performance
        assertEquals(PowerMode.PERFORMANCE, engine.update(batteryPercent = 90, isCharging = false))

        // Drop to mid range → should NOT immediately go to Balanced (hysteresis)
        assertEquals(PowerMode.PERFORMANCE, engine.update(batteryPercent = 70, isCharging = false),
            "Downward transition should be delayed 30s")

        // Still within 30s → stays Performance
        now = 29_000L
        assertEquals(PowerMode.PERFORMANCE, engine.update(batteryPercent = 70, isCharging = false))

        // After 30s sustained → transitions to Balanced
        now = 31_000L
        assertEquals(PowerMode.BALANCED, engine.update(batteryPercent = 70, isCharging = false))

        // Drop to low → hysteresis again
        assertEquals(PowerMode.BALANCED, engine.update(batteryPercent = 20, isCharging = false))
        now = 62_000L
        assertEquals(PowerMode.POWER_SAVER, engine.update(batteryPercent = 20, isCharging = false))

        // Upward transition is immediate (no hysteresis)
        assertEquals(PowerMode.PERFORMANCE, engine.update(batteryPercent = 90, isCharging = false),
            "Upward transition should be immediate")

        // Charging forces Performance regardless of battery
        now = 100_000L
        engine.update(batteryPercent = 10, isCharging = false) // go low
        now = 131_000L
        assertEquals(PowerMode.POWER_SAVER, engine.update(batteryPercent = 10, isCharging = false))
        assertEquals(PowerMode.PERFORMANCE, engine.update(batteryPercent = 10, isCharging = true),
            "Charging should force Performance")
    }

    // --- Batch 12 Cycle 4: Battery threshold boundaries ---

    @Test
    fun batteryThresholdBoundaryValues() {
        var now = 0L
        val engine = PowerModeEngine(hysteresisMillis = 0, clock = { now })

        // modeForBattery: >80→PERFORMANCE, >=30→BALANCED, <30→POWER_SAVER
        assertEquals(PowerMode.PERFORMANCE, engine.update(100, false))
        assertEquals(PowerMode.PERFORMANCE, engine.update(81, false))

        // At exactly 80: NOT > 80 → BALANCED (two calls: first sets pending, second applies)
        now += 1
        engine.update(80, false)
        assertEquals(PowerMode.BALANCED, engine.update(80, false))

        // Exactly 30: >= 30 → BALANCED (stays)
        assertEquals(PowerMode.BALANCED, engine.update(30, false))

        // At 29: < 30 → POWER_SAVER
        now += 1
        engine.update(29, false)
        assertEquals(PowerMode.POWER_SAVER, engine.update(29, false))

        // At 0: edge
        assertEquals(PowerMode.POWER_SAVER, engine.update(0, false))

        // Charging always overrides (upward → immediate)
        assertEquals(PowerMode.PERFORMANCE, engine.update(0, true))
    }
}
