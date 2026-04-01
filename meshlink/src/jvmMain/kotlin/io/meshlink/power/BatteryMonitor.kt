@file:JvmName("BatteryMonitorJvm")

package io.meshlink.power

private class JvmBatteryMonitor : BatteryMonitor {
    override fun batteryLevel(): Int = -1
    override fun isCharging(): Boolean = false
    override fun isAvailable(): Boolean = false
}

actual fun BatteryMonitor(): BatteryMonitor = JvmBatteryMonitor()
