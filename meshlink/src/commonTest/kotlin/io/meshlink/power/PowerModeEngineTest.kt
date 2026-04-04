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

        // Drop to mid range but within dead zone (79 > 80-2=78) → stays Performance
        assertEquals(PowerMode.PERFORMANCE, engine.update(batteryPercent = 79, isCharging = false),
            "79% is within ±2% dead zone of 80 threshold — should stay PERFORMANCE")

        // Drop below dead zone (77 ≤ 78) → starts hysteresis
        assertEquals(PowerMode.PERFORMANCE, engine.update(batteryPercent = 77, isCharging = false),
            "Downward transition should be delayed 30s")

        // Still within 30s → stays Performance
        now = 29_000L
        assertEquals(PowerMode.PERFORMANCE, engine.update(batteryPercent = 77, isCharging = false))

        // After 30s sustained → transitions to Balanced
        now = 31_000L
        assertEquals(PowerMode.BALANCED, engine.update(batteryPercent = 77, isCharging = false))

        // Drop to low → hysteresis again (need to drop below 30-2=28)
        assertEquals(PowerMode.BALANCED, engine.update(batteryPercent = 20, isCharging = false))
        now = 62_000L
        assertEquals(PowerMode.POWER_SAVER, engine.update(batteryPercent = 20, isCharging = false))

        // Upward transition requires rising above threshold + dead zone (30+2=32)
        assertEquals(PowerMode.POWER_SAVER, engine.update(batteryPercent = 31, isCharging = false),
            "31% is within dead zone of 30 threshold — should stay POWER_SAVER")
        assertEquals(PowerMode.BALANCED, engine.update(batteryPercent = 33, isCharging = false),
            "33% is above dead zone — upward transition should be immediate")

        // Further upward: need to rise above 80+2=82
        assertEquals(PowerMode.BALANCED, engine.update(batteryPercent = 81, isCharging = false),
            "81% is within dead zone of 80 threshold — should stay BALANCED")
        assertEquals(PowerMode.PERFORMANCE, engine.update(batteryPercent = 83, isCharging = false),
            "83% is above dead zone — upward transition should be immediate")

        // Charging forces Performance regardless of battery
        now = 100_000L
        engine.update(batteryPercent = 10, isCharging = false) // go low
        now = 131_000L
        assertEquals(PowerMode.POWER_SAVER, engine.update(batteryPercent = 10, isCharging = false))
        assertEquals(PowerMode.PERFORMANCE, engine.update(batteryPercent = 10, isCharging = true),
            "Charging should force Performance")
    }

    @Test
    fun batteryThresholdBoundaryValues() {
        var now = 0L
        val engine = PowerModeEngine(hysteresisMillis = 0, clock = { now })

        // Start at 100% → Performance
        assertEquals(PowerMode.PERFORMANCE, engine.update(100, false))
        assertEquals(PowerMode.PERFORMANCE, engine.update(81, false))

        // Dead zone: at 79%, still PERFORMANCE (> 80-2=78)
        assertEquals(PowerMode.PERFORMANCE, engine.update(79, false))

        // At 78%: exactly at dead zone boundary (78 > 78 is false) → triggers downgrade
        now += 1
        engine.update(78, false) // sets pending
        assertEquals(PowerMode.BALANCED, engine.update(78, false))

        // Dead zone for 30 threshold: 29% still BALANCED (>= 30-2=28)
        assertEquals(PowerMode.BALANCED, engine.update(29, false))

        // At 27%: below dead zone → triggers downgrade
        now += 1
        engine.update(27, false)
        assertEquals(PowerMode.POWER_SAVER, engine.update(27, false))

        // Upward: need to reach 30+2=32 to leave POWER_SAVER
        assertEquals(PowerMode.POWER_SAVER, engine.update(31, false))
        assertEquals(PowerMode.BALANCED, engine.update(32, false),
            "32% (>= 30+2) should upgrade to BALANCED")

        // Upward: need to reach 80+2=82+1=83 to leave BALANCED (> 82)
        assertEquals(PowerMode.BALANCED, engine.update(82, false))
        assertEquals(PowerMode.PERFORMANCE, engine.update(83, false),
            "83% (> 80+2) should upgrade to PERFORMANCE")

        // At 0: edge
        now += 1
        engine.update(0, false)
        assertEquals(PowerMode.POWER_SAVER, engine.update(0, false))

        // Charging always overrides
        assertEquals(PowerMode.PERFORMANCE, engine.update(0, true))
    }

    @Test
    fun deadZonePreventsFlapAtExactThreshold() {
        var now = 0L
        val engine = PowerModeEngine(hysteresisMillis = 0, clock = { now })

        // Start in PERFORMANCE at 83%
        assertEquals(PowerMode.PERFORMANCE, engine.update(83, false))

        // Oscillate around 80%: 81 → 79 → 81 → 79
        assertEquals(PowerMode.PERFORMANCE, engine.update(81, false)) // within dead zone
        assertEquals(PowerMode.PERFORMANCE, engine.update(79, false)) // within dead zone (> 78)
        assertEquals(PowerMode.PERFORMANCE, engine.update(81, false)) // within dead zone
        assertEquals(PowerMode.PERFORMANCE, engine.update(79, false)) // within dead zone
        // No flapping! Dead zone prevents mode changes for ±2% oscillation
    }

    @Test
    fun customDeadZone() {
        var now = 0L
        val engine = PowerModeEngine(hysteresisMillis = 0, deadZonePercent = 5, clock = { now })

        // With 5% dead zone, need to drop below 75% to leave PERFORMANCE
        assertEquals(PowerMode.PERFORMANCE, engine.update(90, false))
        assertEquals(PowerMode.PERFORMANCE, engine.update(76, false)) // within 5% dead zone
        now += 1
        engine.update(74, false) // below dead zone
        assertEquals(PowerMode.BALANCED, engine.update(74, false))
    }

    @Test
    fun customThresholds() {
        var now = 0L
        val engine = PowerModeEngine(
            hysteresisMillis = 0,
            thresholds = listOf(90, 50),
            clock = { now },
        )

        // Custom thresholds: >90 = PERFORMANCE, 50-90 = BALANCED, <50 = POWER_SAVER
        assertEquals(PowerMode.PERFORMANCE, engine.update(95, false))

        // Drop below 90-2=88 → BALANCED
        now += 1
        engine.update(87, false)
        assertEquals(PowerMode.BALANCED, engine.update(87, false))

        // Drop below 50-2=48 → POWER_SAVER
        now += 1
        engine.update(47, false)
        assertEquals(PowerMode.POWER_SAVER, engine.update(47, false))

        // Rise above 50+2=52 → BALANCED
        assertEquals(PowerMode.BALANCED, engine.update(53, false))

        // Rise above 90+2=92 → PERFORMANCE
        assertEquals(PowerMode.PERFORMANCE, engine.update(93, false))
    }
}
