package io.meshlink.power

import platform.Foundation.NSProcessInfo

private class MacosBatteryMonitor : BatteryMonitor {

    override fun batteryLevel(): Int {
        // macOS does not expose a simple battery-level API from Kotlin/Native.
        // Return -1 (unknown) so the power module stays in BALANCED mode.
        return -1
    }

    override fun isCharging(): Boolean {
        // Not reliably detectable without IOKit from Kotlin/Native.
        return false
    }

    override fun isAvailable(): Boolean {
        // Battery monitoring is limited on macOS from Kotlin/Native.
        return false
    }
}

actual fun BatteryMonitor(): BatteryMonitor = MacosBatteryMonitor()
