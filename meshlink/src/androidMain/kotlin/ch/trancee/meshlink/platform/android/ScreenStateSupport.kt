package ch.trancee.meshlink.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

// Registers a BroadcastReceiver for ACTION_SCREEN_ON/ACTION_SCREEN_OFF so the supplementary
// PendingIntent scan channel (see BackgroundScanSupport.kt) only runs while it can actually help --
// screen off, where Doze can suspend the primary ScanCallback-based scan -- rather than for the
// whole transport lifetime. Running it unconditionally caused a real hardware regression
// (2026-07-10, see docs/explanation/android-ble-skill-alignment-review.md): a concurrent scan
// registration with a mismatched scan mode starved the primary channel of results entirely and
// deadlocked the handshake.
internal fun BleTransportAdapter.registerScreenStateReceiver(): Unit {
    if (screenStateReceiver != null) return
    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        log("screen off: starting supplementary background scan channel")
                        startBackgroundScan()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        log("screen on: stopping supplementary background scan channel")
                        stopBackgroundScan()
                    }
                }
            }
        }
    context.registerDynamicReceiver(
        receiver = receiver,
        actions = listOf(Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON),
    )
    screenStateReceiver = receiver
}

internal fun BleTransportAdapter.unregisterScreenStateReceiver(): Unit {
    val receiver = screenStateReceiver ?: return
    screenStateReceiver = null
    context.unregisterDynamicReceiverQuietly(receiver)
}

/**
 * Whether the screen is currently on ("interactive"). Defaults to `true` (interactive) if the
 * platform can't answer -- i.e. this errs towards *not* starting the supplementary scan channel
 * when unsure, which is the safer choice given the concurrent-scan-mode regression this channel has
 * already caused once on a real device; a missed Doze-survival window is recoverable (the next
 * genuine ACTION_SCREEN_OFF starts it), while running an extra mismatched scan concurrently is not
 * easily observable from the field.
 */
internal fun Context.isScreenInteractive(): Boolean {
    return runCatching { getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull()
        ?: true
}
