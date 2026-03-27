package io.meshlink.power

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BatteryMonitorTest {

    @Test
    fun createBatteryMonitorReturnsNonNull() {
        val monitor = createBatteryMonitor()
        assertNotNull(monitor, "createBatteryMonitor() must not return null")
    }

    @Test
    fun batteryLevelReturnsValueInValidRange() {
        val monitor = createBatteryMonitor()
        val level = monitor.batteryLevel()
        assertTrue(level in -1..100, "batteryLevel() must be in -1..100 but was $level")
    }

    @Test
    fun isChargingReturnsBooleanWithoutThrowing() {
        val monitor = createBatteryMonitor()
        // Should complete without throwing; result is either true or false.
        val charging = monitor.isCharging()
        assertTrue(charging || !charging, "isCharging() must return a valid boolean")
    }

    @Test
    fun isAvailableReturnsBooleanWithoutThrowing() {
        val monitor = createBatteryMonitor()
        val available = monitor.isAvailable()
        assertTrue(available || !available, "isAvailable() must return a valid boolean")
    }

    @Test
    fun multipleCallsDoNotThrow() {
        val monitor = createBatteryMonitor()
        repeat(10) {
            monitor.batteryLevel()
            monitor.isCharging()
            monitor.isAvailable()
        }
    }
}
