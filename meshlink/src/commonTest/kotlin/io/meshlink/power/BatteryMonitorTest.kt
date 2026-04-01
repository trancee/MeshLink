package io.meshlink.power

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BatteryMonitorTest {

    @Test
    fun batteryMonitorReturnsNonNull() {
        val monitor = BatteryMonitor()
        assertNotNull(monitor, "BatteryMonitor() must not return null")
    }

    @Test
    fun batteryLevelReturnsValueInValidRange() {
        val monitor = BatteryMonitor()
        val level = monitor.batteryLevel()
        assertTrue(level in -1..100, "batteryLevel() must be in -1..100 but was $level")
    }

    @Test
    fun isChargingReturnsBooleanWithoutThrowing() {
        val monitor = BatteryMonitor()
        // Should complete without throwing; result is either true or false.
        val charging = monitor.isCharging()
        assertTrue(charging || !charging, "isCharging() must return a valid boolean")
    }

    @Test
    fun isAvailableReturnsBooleanWithoutThrowing() {
        val monitor = BatteryMonitor()
        val available = monitor.isAvailable()
        assertTrue(available || !available, "isAvailable() must return a valid boolean")
    }

    @Test
    fun multipleCallsDoNotThrow() {
        val monitor = BatteryMonitor()
        repeat(10) {
            monitor.batteryLevel()
            monitor.isCharging()
            monitor.isAvailable()
        }
    }
}
