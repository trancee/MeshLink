package ch.trancee.meshlink.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the device is in deep Doze, as `PowerManager` tells, now and on each change. */
internal class DozeWatcher(
    context: Context,
) : BroadcastReceiver() {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val mutableDoze = MutableStateFlow(power.isDeviceIdleMode)

    /** Whether the device is in deep Doze. */
    val doze: StateFlow<Boolean> = mutableDoze

    init {
        // ACTION_DEVICE_IDLE_MODE_CHANGED is a protected system broadcast sent only to registered receivers.
        context.registerReceiver(this, IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        mutableDoze.value = power.isDeviceIdleMode
    }
}
