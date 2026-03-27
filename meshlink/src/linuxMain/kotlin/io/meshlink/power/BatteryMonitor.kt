package io.meshlink.power

private class LinuxBatteryMonitor : BatteryMonitor {
    override fun batteryLevel(): Int = -1
    override fun isCharging(): Boolean = false
    override fun isAvailable(): Boolean = false
}

actual fun createBatteryMonitor(): BatteryMonitor = LinuxBatteryMonitor()
