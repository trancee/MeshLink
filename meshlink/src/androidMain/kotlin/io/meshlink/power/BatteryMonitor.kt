@file:JvmName("BatteryMonitorAndroid")

package io.meshlink.power

import android.content.Context
import android.os.BatteryManager

class AndroidBatteryMonitor : BatteryMonitor {

    companion object {
        private var appContext: Context? = null

        /** Must be called once with the application context before use. */
        fun init(context: Context) {
            appContext = context.applicationContext
        }
    }

    private val batteryManager: BatteryManager?
        get() = appContext?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    override fun batteryLevel(): Int =
        batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    override fun isCharging(): Boolean =
        batteryManager?.isCharging ?: false

    override fun isAvailable(): Boolean = true
}

actual fun BatteryMonitor(): BatteryMonitor = AndroidBatteryMonitor()
