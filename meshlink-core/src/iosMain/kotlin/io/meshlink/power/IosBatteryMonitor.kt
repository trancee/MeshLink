package io.meshlink.power

import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState

private class IosBatteryMonitor : BatteryMonitor {

    override fun batteryLevel(): Int {
        UIDevice.currentDevice.batteryMonitoringEnabled = true
        val level = UIDevice.currentDevice.batteryLevel
        return if (level < 0f) -1 else (level * 100).toInt()
    }

    override fun isCharging(): Boolean {
        UIDevice.currentDevice.batteryMonitoringEnabled = true
        val state = UIDevice.currentDevice.batteryState
        return state == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
            state == UIDeviceBatteryState.UIDeviceBatteryStateFull
    }

    override fun isAvailable(): Boolean = true
}

actual fun createBatteryMonitor(): BatteryMonitor = IosBatteryMonitor()
