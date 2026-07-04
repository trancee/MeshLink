package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.BatterySnapshot
import ch.trancee.meshlink.power.BatteryMonitor
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryLevelDidChangeNotification
import platform.UIKit.UIDeviceBatteryStateDidChangeNotification
import platform.UIKit.UIDeviceBatteryState

internal class IosBatteryMonitor : BatteryMonitor {
    private var observers: List<Any?>? = null

    override fun start(onSnapshot: (BatterySnapshot) -> Unit) {
        stop()
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true

        fun emitSnapshot() {
            val level = device.batteryLevel
            if (level < 0f) {
                return
            }
            val isCharging =
                device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
                    device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateFull
            onSnapshot(BatterySnapshot(level = level, isCharging = isCharging))
        }

        val center = NSNotificationCenter.defaultCenter
        val levelObserver =
            center.addObserverForName(
                name = UIDeviceBatteryLevelDidChangeNotification,
                `object` = device,
                queue = null,
            ) { _ -> emitSnapshot() }
        val stateObserver =
            center.addObserverForName(
                name = UIDeviceBatteryStateDidChangeNotification,
                `object` = device,
                queue = null,
            ) { _ -> emitSnapshot() }
        observers = listOf(levelObserver, stateObserver)
        emitSnapshot()
    }

    override fun stop() {
        observers?.forEach { observer -> observer?.let(NSNotificationCenter.defaultCenter::removeObserver) }
        observers = null
        UIDevice.currentDevice.batteryMonitoringEnabled = false
    }
}
