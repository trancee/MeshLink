---
name: android-17-ble-migration
description: Android 17 BLE migration reference. Covers breaking BLE changes stabilizing in Android 17 — BluetoothSocket read loops needing an explicit -1 disconnect check, autonomous re-pairing's EXTRA_PAIRING_CONTEXT, stricter 5-scan-starts-per-30s rate limiting, background audio/alerts needing a While-In-Use-eligible foreground service, Background Activity Launch (BAL) hardening blocking background activity launches, Doze/screen-off scan suspension requiring PendingIntent-based scanning, foregroundServiceType="connectedDevice" enforcement throwing SecurityException, MAC address rotation breaking device-identity caching, and the new USE_LOOPBACK_INTERFACE permission. Use when migrating a BLE app to Android 17, auditing BluetoothSocket read loops, handling ACTION_PAIRING_REQUEST, debugging silent scan failures, muted background alerts, blocked background activity launches, dead screen-off scanning, foreground-service SecurityExceptions, or MAC-rotation device loss.
---

<essential_principles>

Sources: https://bleadvertiserapp.medium.com/android-17-ble-changes-that-will-break-your-app-e2c50c163de8, https://bleadvertiserapp.medium.com/android-17-behavioral-changes-that-impact-ble-apps-and-how-to-fix-them-9ea1a06c7c16

**Android 17 BLE changes** land stable Q2 2026 (Pixel betas already ship them). Almost none of them crash your app — each **degrades silently**: no exception, no logcat line, just missing scan results, hung sockets, muted alerts, blocked launches, or devices that quietly stop being recognized. Audit for all of the following before users update.

### 1. BluetoothSocket read loop — check for -1, don't rely on IOException

On Android 17 a clean remote disconnect returns `-1` from `inputStream.read()` **before** any `IOException` is thrown. A loop that only catches `IOException` to detect disconnection can hang instead of terminating, burning CPU and blocking reconnection.

```kotlin
fun readLoop(socket: BluetoothSocket) {
    val inputStream = socket.inputStream
    val buffer = ByteArray(1024)
    try {
        while (true) {
            val bytes = inputStream.read(buffer)
            if (bytes == -1) {
                handleDisconnect()   // remote disconnected cleanly
                break
            }
            processData(buffer, bytes)
        }
    } catch (e: IOException) {
        handleError(e)              // still catch real I/O errors
    }
}
```

### 2. Autonomous re-pairing — check EXTRA_PAIRING_CONTEXT

Android 17 lets the OS automatically resolve Bluetooth bond loss. `ACTION_PAIRING_REQUEST` now carries `EXTRA_PAIRING_CONTEXT`. Apps that auto-confirm pairing requests (kiosk/IoT companion apps) must skip system-initiated re-pairs or they'll interfere with automatic bond restoration.

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
        val pairingContext = intent.getIntExtra(
            BluetoothDevice.EXTRA_PAIRING_CONTEXT, -1
        )
        if (pairingContext == BluetoothDevice.PAIRING_CONTEXT_AUTONOMOUS) {
            return   // let the OS handle it
        }
        handleUserPairing(intent)
    }
}
```

### 3. Scan rate throttling — back off, don't restart immediately

Android limits scanning to 5 start/stop cycles per 30-second window; exceeding it returns `SCAN_FAILED_APPLICATION_REGISTRATION_FAILED` — usually with **no exception and no log**. This has existed since Android 7 but remains the single most common cause of BLE scan failures developers misdiagnose as permission or hardware bugs, and Android 17 enforces it more strictly. The most common trigger: restarting the scan on every `ACTION_STATE_CHANGED` when a user toggles Bluetooth off/on.

```kotlin
// Debounce the Bluetooth-toggle restart instead of firing immediately
private val bluetoothStateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
            BluetoothAdapter.STATE_ON ->
                Handler(Looper.getMainLooper()).postDelayed({ startScanning() }, 1500)
            BluetoothAdapter.STATE_OFF -> stopScanning()
        }
    }
}

// Exponential backoff on repeated SCAN_FAILED, capped at 30s
class BleScanner(private val bluetoothLeScanner: BluetoothLeScanner) {
    private var retryCount = 0
    private val handler = Handler(Looper.getMainLooper())

    fun startScanWithBackoff() {
        val delayMs = minOf((2.0.pow(retryCount) * 1000).toLong(), 30_000L)
        handler.postDelayed({
            val result = startScan() // false on SCAN_FAILED
            if (!result) { retryCount++; startScanWithBackoff() } else { retryCount = 0 }
        }, delayMs)
    }
}
```

Log a `ble_scan_failed_rate_limit` analytics event whenever `SCAN_FAILED_APPLICATION_REGISTRATION_FAILED` fires — a spike means you have a scan-lifecycle bug, and Logcat's "scanning too frequently" warning is otherwise your only signal.

### 4. Background audio — needs a foreground service with WIU, started at the right time

Android 17 restricts background audio (playback, focus requests, volume changes) to apps with a foreground service that has **While-In-Use (WIU)** capability. A BLE app that beeps/alerts on a background device-detected event gets **silently muted** without it. The critical detail: a foreground service only gets WIU if it was **started while the app was visible to the user**, or in direct response to a `MediaSessionEvent`. A service started from `BOOT_COMPLETED`, an alarm, or any other background trigger does **not** get WIU — audio from it fails silently even with the manifest declaration below.

```xml
<service
    android:name=".BleScanService"
    android:foregroundServiceType="connectedDevice|mediaPlayback" />
```

```kotlin
// ✅ Start the FGS while the app is foregrounded/visible — WIU-eligible
val intent = Intent(this, BleMonitorService::class.java)
ContextCompat.startForegroundService(this, intent)

// ❌ Starting the FGS from BOOT_COMPLETED, then playing audio later = silent failure
```

If alerts must fire in all cases, start a `mediaPlayback`-typed foreground service proactively while the user is still in the app, before it backgrounds. Track `audio_playback_attempted_background` / `audio_playback_failed_background` to quantify impact post-rollout.

### 5. Background Activity Launch (BAL) hardening — stop auto-launching UI from background

Android 17 extends BAL restrictions to `IntentSender`, and deprecates `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` in favor of granular controls like `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`. A common BLE pattern — auto-opening a device dashboard when a peripheral connects in the background — is now **blocked**, silently breaking that UX flow.

```kotlin
// ✅ User-initiated: a tappable notification instead of a direct launch
val notification = NotificationCompat.Builder(this, CHANNEL_ID)
    .setContentTitle("Device Connected")
    .setContentText("Tap to open ${device.name}")
    .setContentIntent(pendingIntent)
    .build()
notificationManager.notify(NOTIF_ID, notification)

// ❌ Blocked on Android 17
startActivity(deviceDashboardIntent)
```

Search the codebase for `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` and replace with `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`; Android Studio's lint now flags this. Track `bal_blocked` when a launch gets suppressed.

### 6. Doze / screen-off scanning — switch to PendingIntent-based scanning

OEM Doze implementations (Xiaomi, OnePlus, Samsung on Android 15+) increasingly suspend non-batched BLE scanning entirely once the screen is off, even with `ScanFilter`s applied, and may deliver batched results only once every 5 minutes — real-time background monitoring effectively stops working and users churn without knowing it's a system restriction.

```kotlin
val pendingIntent = PendingIntent.getBroadcast(
    context, REQUEST_CODE, Intent(context, BleScanReceiver::class.java),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
)
val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(YOUR_SERVICE_UUID)).build()
val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
bluetoothLeScanner.startScan(listOf(scanFilter), settings, pendingIntent)
```

The system wakes your process via the `PendingIntent` when a match is found, without a persistent scan. A `ScanFilter` is mandatory for background scanning — unfiltered background scans are explicitly stopped on screen-off across modern builds. For continuous connection maintenance (not just discovery), migrate to `CompanionDeviceManager`, which carries special system privileges for keeping connections alive.

### 7. Foreground service type enforcement — connectedDevice is mandatory, check permission first

Android validates at service-creation time that the app holds every permission its declared foreground service type requires. A BLE foreground service must declare `foregroundServiceType="connectedDevice"` and the app must hold `BLUETOOTH_CONNECT` **at the moment the service starts** — otherwise Android throws `SecurityException` (a crash, not a silent failure) instead of ignoring it. This bites services started from background contexts, or when the user revokes the Bluetooth permission while the service is running.

```xml
<service
    android:name=".BleConnectionService"
    android:foregroundServiceType="connectedDevice"
    android:exported="false" />
```

```kotlin
if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
    == PackageManager.PERMISSION_GRANTED) {
    ContextCompat.startForegroundService(context, serviceIntent)
} else {
    // Request permission or degrade gracefully
}
```

Audit every `<service>` element now — a foreground service targeting Android 14+ without a declared `foregroundServiceType` is already broken on newer devices, and 17 tightens enforcement further.

### 8. MAC address rotation — stop identifying devices by MAC

BLE peripherals (and phones acting as peripherals) increasingly rotate their MAC address on short intervals, and Android 17 continues hardening MAC exposure. Caching/identifying devices by `scanResult.device.address` goes stale within minutes, surfacing as "the app keeps losing my device" churn.

```kotlin
// ❌ MAC address rotates
val cachedDevice = deviceMap[scanResult.device.address]

// ✅ Identify by service UUID or manufacturer-specific data
val manufacturerData = scanResult.scanRecord?.getManufacturerSpecificData(COMPANY_ID)
val deviceId = generateStableId(manufacturerData)
val cachedDevice = deviceMap[deviceId]
```

For companion-app use cases, prefer `CompanionDeviceManager` — the system maintains the device association across MAC rotations. Track `device_identity_collision` when a cached ID doesn't match an incoming scan result.

### 9. Loopback interface permission — new in Android 17

Android 17 adds an install-time permission, `USE_LOOPBACK_INTERFACE`, gating cross-app/cross-profile communication over `127.0.0.1` (previously implicit under `INTERNET`). BLE gateway/bridge apps that pipe scan data to a local HTTP server or between processes over loopback break silently without it.

```xml
<uses-permission android:name="android.permission.USE_LOOPBACK_INTERFACE" />
```

### Analytics to instrument before rollout

Because every change above degrades silently, instrument these before Android 17 hits users, not after:

- Events: `ble_scan_started`/`ble_scan_stopped` (with `foreground: boolean`), `ble_device_found` (`source: pendingIntent|callback`), `fgs_started` (`wiu_eligible: boolean`, `trigger: user|boot|alarm`), `audio_playback_attempted_background`/`audio_playback_failed_background`, `bal_blocked`, `device_identity_collision`, `ble_scan_failed_rate_limit`.
- Funnels: app open → permission granted → first scan → first device found (onboarding health); background service start → FGS created → connection maintained (background retention); device connect → alert → user opens app (re-engagement).
- Daily dashboards: background scan success rate segmented by Android version; FGS crash rate from `SecurityException`; notification tap-through rate (proxy for the BAL migration in #5).

### Pre-release checklist (do before Android 17 stable, June 2026)

1. Read loops: explicit `-1` check, not exception-only disconnect detection.
2. Pairing receivers: handle `EXTRA_PAIRING_CONTEXT`, bail out on `PAIRING_CONTEXT_AUTONOMOUS`.
3. Scan restarts: debounce Bluetooth-toggle restarts, add exponential backoff + failure logging.
4. Background alerts: start the FGS while the app is visible (not from boot/alarm) and declare `connectedDevice|mediaPlayback`.
5. Background activity launches: replace `IntentSender`/`startActivity` auto-launch with a tappable notification; drop `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`.
6. Background scanning: switch to `PendingIntent`-based scanning with mandatory `ScanFilter`s; consider `CompanionDeviceManager` for persistent connections.
7. Foreground services: declare `foregroundServiceType="connectedDevice"` and check `BLUETOOTH_CONNECT` before starting.
8. Device identity: key caches by service UUID/manufacturer data, not MAC address.
9. Loopback IPC: declare `USE_LOOPBACK_INTERFACE` if using `127.0.0.1` sockets.

None of these require an architecture change — they're surgical, testable fixes. Skipping them means Android 17 finds them in production reviews instead of your test suite.

### Trend to watch

Google is moving Bluetooth bond management and background execution control toward the OS and away from apps. Expect tighter limits on intercepting/modifying pairing flows and background launches in future releases, and — per these sources' prediction — background BLE scanning without a declared foreground service type may become blocked outright (not just throttled) within a couple of major versions after 17.

</essential_principles>
