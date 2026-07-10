package ch.trancee.meshlink.platform.android

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
    val scanner = scanner ?: return
    val pendingIntent = backgroundScanPendingIntent()
    runCatching { scanner.startScan(buildScanFilters(), backgroundScanSettings(), pendingIntent) }
        .onFailure { error -> log("background scan start failed: ${error.message.orEmpty()}") }
        .onSuccess { resultCode -> log("background scan start invoked resultCode=$resultCode") }
}

@SuppressLint("MissingPermission")
internal fun BleTransportAdapter.stopBackgroundScan(): Unit {
    val scanner = scanner ?: return
    runCatching { scanner.stopScan(backgroundScanPendingIntent()) }
        .onFailure { error -> log("background scan stop failed: ${error.message.orEmpty()}") }
}

// SCAN_MODE_LOW_POWER matches the skill's own PendingIntent-scanning example: this channel exists
// to survive Doze/screen-off, not to compete on latency with the foreground ScanCallback path,
// which already applies the caller-selected PowerProfile-driven scan mode.
internal fun backgroundScanSettings(): ScanSettings {
    return ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
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
