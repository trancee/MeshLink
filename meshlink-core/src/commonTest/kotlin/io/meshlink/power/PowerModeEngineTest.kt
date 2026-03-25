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
}
