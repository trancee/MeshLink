package io.meshlink.platform

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import io.meshlink.MeshLink
import io.meshlink.util.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Abstract foreground [Service] that hosts a [MeshLink] instance.
 *
 * Subclasses must implement [createNotification] and [createMeshLink].
 *
 * Key behaviours:
 * - Returned as `START_STICKY` so the system restarts it after a crash.
 * - Crash-loop breaker: if the service is restarted ≥ 3 times within 60 s
 *   it inserts a 30 s delay before starting MeshLink.
 * - Automatically switches power modes on battery-low / battery-okay broadcasts.
 * - Calls [MeshLink.stop] with a graceful drain on [onDestroy].
 */
abstract class MeshLinkService : Service() {

    /** Provide the foreground notification shown while MeshLink runs. */
    abstract fun createNotification(): Notification

    /** Configure and return the [MeshLink] instance this service will host. */
    abstract fun createMeshLink(): MeshLink

    /** Notification ID used for [startForeground]. Override to customise. */
    open val notificationId: Int = 7_336

    // -- internal state -------------------------------------------------------

    private var meshLink: MeshLink? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // crash-loop tracking
    private val startTimestamps = mutableListOf<Long>()
    private val crashWindowMillis = 60_000L
    private val crashThreshold = 3
    private val crashDelayMillis = 30_000L

    // battery broadcast receiver
    private var batteryReceiver: BroadcastReceiver? = null

    // -- lifecycle ------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(notificationId, createNotification())

        val now = currentTimeMillis()
        startTimestamps.add(now)
        startTimestamps.removeAll { now - it > crashWindowMillis }

        if (startTimestamps.size >= crashThreshold) {
            serviceScope.launch {
                delay(crashDelayMillis)
                initMeshLink()
            }
        } else {
            initMeshLink()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        unregisterBatteryReceiver()
        meshLink?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    // -- internal helpers -----------------------------------------------------

    private fun initMeshLink() {
        if (meshLink != null) return
        val ml = createMeshLink()
        meshLink = ml
        ml.start()
        registerBatteryReceiver()
    }

    private fun registerBatteryReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_LOW -> {
                        meshLink?.updateBattery(batteryPercent = 10, isCharging = false)
                    }
                    Intent.ACTION_BATTERY_OKAY -> {
                        meshLink?.updateBattery(batteryPercent = 80, isCharging = false)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        registerReceiver(receiver, filter)
        batteryReceiver = receiver
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // already unregistered
            }
        }
        batteryReceiver = null
    }
}
