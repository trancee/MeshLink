package ch.trancee.meshlink.platform.android.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import ch.trancee.meshlink.api.BatterySnapshot
import ch.trancee.meshlink.power.BatteryMonitor

internal class AndroidBatteryMonitor(context: Context) : BatteryMonitor {
    private val applicationContext: Context = context.applicationContext
    private var receiver: BroadcastReceiver? = null

    override fun start(onSnapshot: (BatterySnapshot) -> Unit) {
        stop()
        val newReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    emitSnapshot(intent = intent, onSnapshot = onSnapshot)
                }
            }
        receiver = newReceiver
        val stickyIntent =
            applicationContext.registerReceiver(
                newReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
        emitSnapshot(intent = stickyIntent, onSnapshot = onSnapshot)
    }

    override fun stop() {
        receiver?.let { registeredReceiver ->
            runCatching { applicationContext.unregisterReceiver(registeredReceiver) }
        }
        receiver = null
    }

    private fun emitSnapshot(intent: Intent?, onSnapshot: (BatterySnapshot) -> Unit) {
        intent ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        onSnapshot(
            BatterySnapshot(level = level.toFloat() / scale.toFloat(), isCharging = isCharging)
        )
    }
}
