package ch.trancee.meshlink.platform.android.scan

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import ch.trancee.meshlink.platform.android.BleTransportAdapter
import ch.trancee.meshlink.platform.android.power.PowerProfile
import kotlinx.coroutines.launch

// A PendingIntent-registered scan is a supplementary discovery channel alongside the primary
// ScanCallback-based scan driven by BleTransportDiscoveryLifecycle -- it exists specifically for
// the case the android-17-ble-migration skill (\u00a76) describes: modern OEM Doze implementations
// increasingly suspend non-batched, callback-based scanning once the screen is off, even with a
// ScanFilter applied, while a PendingIntent-registered scan keeps being serviced by the platform
// and can redeliver a match even when regular callback delivery has stalled. It intentionally does
// NOT replace the ScanCallback path or its retry/backoff/watchdog state machine in
// BleTransportDiscoveryLifecycle -- both scans run concurrently, using the same mandatory
// ScanFilter, and both funnel matches into the same handleScanResult() so PeerRegistry/
// BleTransportLinkRegistry state is a single source of truth regardless of which channel a given
// result arrived on.
//
// Two lessons learned the hard way from a real hardware regression (2026-07-10, see
// docs/explanation/android-ble-skill-alignment-review.md) shape how this channel is actually run:
//
// 1. It must use the SAME ScanSettings.scanMode as the primary channel, not a hardcoded
//    SCAN_MODE_LOW_POWER. Registering two concurrent scans from the same app with *different* scan
//    modes is a known Android BLE-stack footgun: the platform can silently arbitrate down to the
//    most conservative duty cycle across all of an app's active scan registrations, which starved
//    the primary SCAN_MODE_LOW_LATENCY channel of results entirely on a real device pairing and
//    deadlocked the handshake. See backgroundScanSettings().
// 2. It should only be registered while it is actually needed -- screen off, when Doze can suspend
//    callback-based scanning -- rather than unconditionally for the whole transport lifetime. See
//    ScreenStateSupport.kt's registerScreenStateReceiver(), which starts/stops this channel.
//
// This channel only survives the current process staying alive -- the BroadcastReceiver below is
// registered dynamically (Context.registerReceiver), not declared in a manifest, so it cannot wake
// a fully-killed process the way a manifest-declared receiver could. Waking a killed process and
// resuming meshing without a live MeshEngine instance would require a much larger, standalone
// headless-background-service architecture and is a separate, larger decision than this scan
// channel; see docs/explanation/android-ble-skill-alignment-review.md for that follow-up.
private const val BACKGROUND_SCAN_ACTION_SUFFIX = ".MESHLINK_BACKGROUND_SCAN_RESULT"

internal fun backgroundScanAction(packageName: String): String {
    return packageName + BACKGROUND_SCAN_ACTION_SUFFIX
}

internal fun BleTransportAdapter.registerBackgroundScanReceiver(): Unit {
    if (backgroundScanReceiver != null) return
    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent == null) return
                handleBackgroundScanIntent(intent)
            }
        }
    context.registerDynamicReceiver(
        receiver = receiver,
        action = backgroundScanAction(context.packageName),
    )
    backgroundScanReceiver = receiver
}

internal fun BleTransportAdapter.unregisterBackgroundScanReceiver(): Unit {
    val receiver = backgroundScanReceiver ?: return
    backgroundScanReceiver = null
    context.unregisterDynamicReceiverQuietly(receiver)
}

internal fun BleTransportAdapter.handleBackgroundScanIntent(intent: Intent): Unit {
    val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, Int.MIN_VALUE)
    if (errorCode != Int.MIN_VALUE) {
        log("background scan failed errorCode=$errorCode errorName=${scanErrorCodeName(errorCode)}")
        return
    }
    val results =
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
            ?: return
    results.forEach { result ->
        coroutineScope.launch(scanProcessingDispatcher) { handleScanResult(result) }
    }
}

@SuppressLint("MissingPermission")
internal fun BleTransportAdapter.startBackgroundScan(): Unit {
    if (backgroundScanActive) return
    val scanner = scanner ?: return
    val pendingIntent = backgroundScanPendingIntent()
    runCatching {
            scanner.startScan(
                buildScanFilters(),
                backgroundScanSettings(currentPowerProfile),
                pendingIntent,
            )
        }
        .onFailure { error -> log("background scan start failed: ${error.message.orEmpty()}") }
        .onSuccess { resultCode ->
            backgroundScanActive = true
            log("background scan start invoked resultCode=$resultCode")
        }
}

@SuppressLint("MissingPermission")
internal fun BleTransportAdapter.stopBackgroundScan(): Unit {
    if (!backgroundScanActive) return
    val scanner = scanner ?: return
    backgroundScanActive = false
    runCatching { scanner.stopScan(backgroundScanPendingIntent()) }
        .onFailure { error -> log("background scan stop failed: ${error.message.orEmpty()}") }
}

// Mirrors the primary ScanCallback-based channel's current PowerProfile-driven scan mode instead
// of hardcoding SCAN_MODE_LOW_POWER: running two concurrent scan registrations from the same app
// with different scan modes let the platform silently arbitrate both down to the more conservative
// duty cycle, starving the primary channel of results on a real device pairing (2026-07-10). Now
// that this channel is also screen-off-gated (see ScreenStateSupport.kt), matching the scan mode
// is a second, independent layer of defense against that same class of interference.
internal fun backgroundScanSettings(currentPowerProfile: PowerProfile): ScanSettings {
    return ScanSettings.Builder().setScanMode(currentPowerProfile.scanMode).build()
}

internal fun BleTransportAdapter.backgroundScanPendingIntent(): PendingIntent {
    val intent = Intent(backgroundScanAction(context.packageName)).setPackage(context.packageName)
    val flags =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
    return PendingIntent.getBroadcast(context, BACKGROUND_SCAN_REQUEST_CODE, intent, flags)
}

private const val BACKGROUND_SCAN_REQUEST_CODE = 0x4d4c4253 // "MLBS" (MeshLink Background Scan)
