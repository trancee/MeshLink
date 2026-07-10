package ch.trancee.meshlink.reference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service used during Android direct proof to keep the process and CPU awake
 * while the device is discovering peers.
 *
 * This is part of the live-proof mitigation for doze-sensitive Android OEM builds.
 */
public class DirectProofPowerService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(): Unit {
        super.onCreate()
        Log.i(
            TAG,
            "REFERENCE_AUTOMATION direct-proof service stage=onCreate wakeLockHeld=${wakeLock?.isHeld == true}",
        )
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android validates at service-creation time that the app holds every permission its
        // declared foregroundServiceType ("connectedDevice") requires, and throws SecurityException
        // -- not a silent failure -- if BLUETOOTH_CONNECT is missing at the exact moment
        // startForeground() is called. This can happen if the user revokes the permission while
        // this service is (or is about to be) running (see android-17-ble-migration skill #7).
        if (!hasBluetoothConnectPermission()) {
            Log.w(
                TAG,
                "REFERENCE_AUTOMATION direct-proof service stage=onStartCommand " +
                    "missing BLUETOOTH_CONNECT; aborting startForeground and stopping self",
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        Log.i(
            TAG,
            "REFERENCE_AUTOMATION direct-proof service stage=onStartCommand " +
                "wakeLockHeld=${wakeLock?.isHeld == true} flags=$flags startId=$startId",
        )
        return START_STICKY
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        // BLUETOOTH_CONNECT only exists as a runtime permission from API 31 (S) onward; below
        // that, foreground-service-type enforcement for "connectedDevice" doesn't gate on it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy(): Unit {
        Log.i(
            TAG,
            "REFERENCE_AUTOMATION direct-proof service stage=onDestroy wakeLockHeld=${wakeLock?.isHeld == true}",
        )
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock =
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MeshLinkReference:DirectProofWakeLock",
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        createChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("MeshLink direct proof running")
            .setContentText("Keeping Bluetooth discovery awake during the live-proof session.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps MeshLink direct-proof live sessions awake during Bluetooth discovery."
            }
        manager.createNotificationChannel(channel)
    }

    public companion object {
        private const val TAG = "MeshLinkReference"
        private const val CHANNEL_ID = "meshlink_reference_direct_proof_power"
        private const val CHANNEL_NAME = "MeshLink direct proof"
        private const val NOTIFICATION_ID = 4201
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        public fun start(context: Context): Intent {
            return Intent(context, DirectProofPowerService::class.java)
        }
    }
}
