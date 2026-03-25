---
name: android-bluetooth-expert
description: "Android Bluetooth and BLE development expert. Use this skill whenever the user is working with Bluetooth on Android — whether implementing, debugging, or reviewing code for Bluetooth Classic (RFCOMM, SPP, A2DP), Bluetooth Low Energy (BLE scanning, GATT client/server, notifications, descriptors), CompanionDeviceManager pairing, BLE Audio / LE Audio, or Bluetooth permissions (BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION). Trigger on any mention of: BluetoothAdapter, BluetoothGatt, BluetoothSocket, BluetoothLeScanner, ScanCallback, ScanFilter, connectGatt, GATT status codes (especially 133), BluetoothLeAdvertiser, BluetoothServerSocket, CompanionDeviceManager, RFCOMM, SPP UUID, OBD-II Bluetooth, BLE beacons, heart rate monitors, fitness bands, Bluetooth printers, BLE characteristics, CCCD descriptors, or any Bluetooth-related permission errors."
---

# Android Bluetooth Expert

Help the user implement, debug, and understand Bluetooth functionality in Android apps. Keep answers practical and doc-backed: give a direct diagnosis, working Kotlin code (with Java when helpful), a brief explanation of why the approach is sound, and pointers to the official docs.

## Key reference files

- `references/official-sources.md` — Curated map to official Android Bluetooth documentation. Read when you need exact API links, version-specific facts, or need to cite a source.

## Start by identifying the Bluetooth variant

Android Bluetooth questions span several distinct subsystems. First figure out which one applies:

- **Bluetooth Classic** — RFCOMM sockets, streaming audio, high-bandwidth data exchange between paired devices
- **Bluetooth Low Energy (BLE)** — GATT client/server, low-power sensors, beacons, wearables
- **Companion Device Manager (CDM)** — System-assisted pairing UI that avoids location permission for initial device association
- **BLE Audio** — LE Audio profiles (LC3 codec, Auracast broadcast), audio routing via AudioManager or Telecom API
- **Bluetooth Profiles** — Headset (HFP), A2DP, Health Device Profile (HDP)

If the variant isn't obvious, ask. The most useful missing details are usually:

- Classic or BLE (or both)?
- Target API level and `minSdkVersion`
- Whether they need scanning, connecting, data transfer, or all three
- The specific error, stack trace, or unexpected behavior
- Whether the app needs background Bluetooth access

## Working style

- Prefer a direct answer first, then show code.
- Keep examples small but runnable. Default to Kotlin.
- Use modern Android patterns: coroutines with `Dispatchers.IO` for blocking I/O, `ActivityResultContracts` for permission requests, `RecyclerView` for device lists, ViewBinding for views.
- Explain the boundary between Bluetooth Classic and BLE guidance when it matters — they are fundamentally different stacks despite sharing the `android.bluetooth` package.
- Preserve behavior unless the user asked for a behavior change.
- Use official sources as anchors. If a recommendation is judgment-based rather than a doc rule, say so plainly.
- When API-level-specific facts matter, give the exact level rather than vague advice. Always specify whether guidance applies to API 31+ or legacy.
- When showing permission code, always show the branching logic for both API 31+ and legacy within the same example — apps almost always need to support both.

## Permissions — get this right first

Bluetooth permissions are the #1 source of confusion because they changed dramatically in Android 12 (API 31). Getting permissions wrong leads to silent scan failures, SecurityExceptions, and rejected Play Store submissions.

### Permission matrix by API level

| API Range | Scanning | Connecting | Advertising | Location Required? |
|-----------|----------|------------|-------------|-------------------|
| 21–22 | `BLUETOOTH` + `BLUETOOTH_ADMIN` | `BLUETOOTH` | `BLUETOOTH` + `BLUETOOTH_ADMIN` | No |
| 23–28 | `BLUETOOTH` + `BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION` (runtime) | `BLUETOOTH` | `BLUETOOTH` + `BLUETOOTH_ADMIN` | Yes — runtime grant |
| 29–30 | Same as 23–28; add `ACCESS_BACKGROUND_LOCATION` for background scanning | `BLUETOOTH` | `BLUETOOTH` + `BLUETOOTH_ADMIN` | Yes — runtime grant |
| 31+ | `BLUETOOTH_SCAN` (runtime) | `BLUETOOTH_CONNECT` (runtime) | `BLUETOOTH_ADVERTISE` (runtime) | Only if deriving physical location |

### Manifest template (supporting both old and new)

```xml
<manifest>
    <!-- Legacy permissions (API ≤ 30 only) -->
    <uses-permission android:name="android.permission.BLUETOOTH"
                     android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
                     android:maxSdkVersion="30" />

    <!-- New granular permissions (API 31+) -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
                     android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

    <!-- Location: required for scanning on API ≤ 30; drop on 31+ if not deriving location -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
                     android:maxSdkVersion="30" />

    <!-- Hardware feature declarations -->
    <uses-feature android:name="android.hardware.bluetooth" android:required="true" />
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
</manifest>
```

**Key details:**
- Add `android:maxSdkVersion="30"` to legacy `BLUETOOTH`, `BLUETOOTH_ADMIN`, and `ACCESS_FINE_LOCATION` so they only apply on older devices.
- If your BLE scans do **not** derive physical location, add `android:usesPermissionFlags="neverForLocation"` to `BLUETOOTH_SCAN`.
- If your scans **do** derive location (geofencing, indoor positioning), keep `ACCESS_FINE_LOCATION` without `maxSdkVersion`.
- Set `android:required="false"` on `<uses-feature>` if your app can work without Bluetooth — otherwise Google Play hides the app from devices without Bluetooth hardware.

### Runtime permission request pattern

Always use `ActivityResultContracts` (not the deprecated `requestPermissions`):

```kotlin
private val bluetoothPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
) { permissions ->
    val allGranted = permissions.values.all { it }
    if (allGranted) {
        startBluetoothOperations()
    } else {
        // Explain to user why permissions are needed
    }
}

private fun requestBluetoothPermissions() {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
    bluetoothPermissionLauncher.launch(permissions)
}
```

On API ≤ 30, `ACCESS_FINE_LOCATION` must be granted at runtime — a manifest declaration alone is not enough. Scans silently return zero results without error if location permission is missing or Location Services are turned off.

**Location Services check (required on API ≤ 30):**
```kotlin
fun isLocationEnabled(context: Context): Boolean {
    val locationManager = context.getSystemService(LocationManager::class.java)
    return LocationManagerCompat.isLocationEnabled(locationManager)
}
```

## Bluetooth setup

Before any Bluetooth operations, check adapter availability and prompt to enable:

```kotlin
val bluetoothManager = getSystemService(BluetoothManager::class.java)
val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

if (bluetoothAdapter == null) {
    // Device doesn't support Bluetooth — disable BT features gracefully
    return
}

// Use ActivityResultContracts instead of deprecated startActivityForResult
private val enableBtLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == Activity.RESULT_OK) { /* Bluetooth enabled */ }
}

if (!bluetoothAdapter.isEnabled) {
    enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
}
```

Listen for `BluetoothAdapter.ACTION_STATE_CHANGED` broadcasts if you need to react to the user toggling Bluetooth on/off.

## Bluetooth Classic

### Discovering devices

Two paths: query **already-paired** devices or run **active discovery**. Always check paired devices first — it's instant, avoids a scan, and covers the common case where the user has already bonded with the target device.

**Paired devices** (fast, no scan needed — always do this first):
```kotlin
val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
pairedDevices?.forEach { device ->
    Log.d(TAG, "${device.name} — ${device.address}")
    // Add to your RecyclerView adapter
}
```

If the target device isn't already paired, fall back to **active discovery** (scans for ~12 seconds):
```kotlin
val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
registerReceiver(receiver, filter)
bluetoothAdapter?.startDiscovery()

private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (BluetoothDevice.ACTION_FOUND == intent.action) {
            val device: BluetoothDevice? =
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            // Add to your device list
        }
    }
}

// Always unregister in onDestroy
override fun onDestroy() {
    super.onDestroy()
    unregisterReceiver(receiver)
}
```

Always call `cancelDiscovery()` before opening a connection — discovery is resource-intensive and slows down connections.

### Connecting (RFCOMM)

Bluetooth Classic connections use RFCOMM channels, modeled after TCP sockets. One device acts as server (listening for incoming connections via `accept()`), the other as client (initiating via `connect()`). Both share the same UUID.

**Important: always show both server and client code.** Even when the prompt says "send a message" or "connect to a device", the other device needs a listening server socket. Without the server side, the code is incomplete — `connect()` has nothing to connect *to*. The only exception is connecting to a device that already runs its own RFCOMM server (like an OBD-II adapter). When in doubt, include both.

**Client connection with coroutines (preferred):**
```kotlin
suspend fun connectToDevice(device: BluetoothDevice): BluetoothSocket = withContext(Dispatchers.IO) {
    bluetoothAdapter?.cancelDiscovery()
    val socket = device.createRfcommSocketToServiceRecord(MY_UUID)
    try {
        socket.connect() // Blocking — that's why we use Dispatchers.IO
        socket
    } catch (e: IOException) {
        socket.close()
        // Fallback for devices with buggy SDP (see note below)
        val fallback = device.javaClass
            .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            .invoke(device, 1) as BluetoothSocket
        fallback.connect()
        fallback
    }
}

// Usage from Activity/Fragment:
lifecycleScope.launch {
    try {
        val socket = connectToDevice(selectedDevice)
        manageConnectedSocket(socket)
    } catch (e: IOException) {
        Log.e(TAG, "Connection failed", e)
    }
}
```

**Server side (required for any app that receives incoming connections):**
```kotlin
suspend fun acceptConnection(): BluetoothSocket = withContext(Dispatchers.IO) {
    val serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, MY_UUID)
    try {
        // accept() blocks until a client connects or the server socket is closed
        val socket = serverSocket?.accept() ?: throw IOException("accept() returned null")
        socket
    } finally {
        serverSocket?.close() // Close the server socket — we only need one connection
    }
}

// Start the server before or alongside discovery so the other device can connect to us:
lifecycleScope.launch {
    try {
        val socket = acceptConnection()
        manageConnectedSocket(socket) // Same handler as client side
    } catch (e: IOException) {
        Log.e(TAG, "Accept failed", e)
    }
}
```

**Note on buggy SDP implementations:** Some devices (especially older Samsung and Chinese OEMs) fail `createRfcommSocketToServiceRecord()` with an IOException. The reflection-based `createRfcommSocket(channel)` fallback is well-known but is unofficial API — log it, handle errors, and keep ProGuard/R8 rules if using it:
```proguard
-keepclassmembers class android.bluetooth.BluetoothDevice {
    public java.lang.reflect.Method createRfcommSocket(int);
}
```

### Transferring data

Once connected, use `InputStream`/`OutputStream` from the `BluetoothSocket`. Wrap in coroutines:

```kotlin
class BluetoothDataStream(private val socket: BluetoothSocket) {
    private val inStream: InputStream = socket.inputStream
    private val outStream: OutputStream = socket.outputStream

    suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        val buffer = ByteArray(1024)
        val bytes = inStream.read(buffer)
        buffer.copyOf(bytes)
    }

    suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        outStream.write(data)
    }

    fun close() { socket.close() }
}
```

For continuous reading, launch a collection loop in a coroutineScope that can be cancelled:

```kotlin
lifecycleScope.launch {
    try {
        while (isActive) {
            val data = dataStream.read()
            // Process received data on main thread
            withContext(Dispatchers.Main) { onDataReceived(data) }
        }
    } catch (e: IOException) {
        Log.d(TAG, "Connection closed", e)
    }
}
```

## Bluetooth Low Energy (BLE)

### Scanning for BLE devices

Use `BluetoothLeScanner` with a `ScanCallback`. Always set a scan timeout to avoid draining the battery.

**Basic scan with timeout:**
```kotlin
private val scanner = bluetoothAdapter?.bluetoothLeScanner
private var scanning = false
private val handler = Handler(Looper.getMainLooper())
private val SCAN_PERIOD = 10_000L

fun scanLeDevice() {
    if (!scanning) {
        handler.postDelayed({ scanning = false; scanner?.stopScan(scanCallback) }, SCAN_PERIOD)
        scanning = true
        scanner?.startScan(scanCallback)
    } else {
        scanning = false
        scanner?.stopScan(scanCallback)
    }
}

private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        // result.device, result.rssi, result.scanRecord
    }
    override fun onScanFailed(errorCode: Int) {
        Log.e(TAG, "Scan failed with error: $errorCode")
    }
}
```

**Filtered scan (more power-efficient, recommended for production):**
```kotlin
val scanFilter = ScanFilter.Builder()
    .setServiceUuid(ParcelUuid(MY_SERVICE_UUID))
    // .setDeviceName("My Device")
    // .setManufacturerData(0x00E0, byteArrayOf())
    .build()

val scanSettings = ScanSettings.Builder()
    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)  // Fast scan for foreground use
    // .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)  // Battery-friendly for background
    .build()

scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
```

**BLE scan throttling (Android 7+):** The system limits apps to **5 scan starts per 30-second window**. Exceeding this silently stops scans without error. Workarounds:
- Use `ScanFilter` objects — filtered scans are exempt from throttling.
- Batch scan results with `ScanSettings.CALLBACK_TYPE_ALL_MATCHES` and longer scan windows.
- Avoid rapidly toggling `startScan`/`stopScan` in a loop.

### Connecting to a GATT server

BLE communication happens through GATT (Generic Attribute Profile). Always use the 4-parameter `connectGatt()` with `TRANSPORT_LE` to prevent accidental Classic connections on dual-mode devices:

```kotlin
var bluetoothGatt: BluetoothGatt? = null

bluetoothGatt = device.connectGatt(
    context,
    false,              // autoConnect: false for direct connection, true for background reconnect
    gattCallback,
    BluetoothDevice.TRANSPORT_LE  // Explicit BLE transport — prevents Classic fallback
)
```

The `BluetoothGattCallback` is where everything happens:

```kotlin
private val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            // Connection failed — see "Debugging" section for status codes
            gatt.close()
            return
        }
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                // Small delay before discovery improves reliability on some devices
                Handler(Looper.getMainLooper()).postDelayed({
                    gatt.discoverServices()
                }, 600)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                gatt.close() // Always close to free resources
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            // gatt.services — iterate to find your target service/characteristic
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
    ) {
        // API 33+ signature — handle notification/indication data
    }

    @Deprecated("Deprecated in API 33")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
    ) {
        // Pre-API 33 fallback
        val value = characteristic.value
    }
}
```

### GATT operation queue

GATT operations are asynchronous and the BLE stack processes only **one operation at a time**. Issuing a second read/write before the first callback arrives causes silent failures. Always queue operations:

```kotlin
class GattOperationQueue {
    private val queue: Queue<Runnable> = LinkedList()
    private var operationInProgress = false

    @Synchronized
    fun enqueue(operation: Runnable) {
        queue.add(operation)
        if (!operationInProgress) processNext()
    }

    @Synchronized
    fun operationCompleted() {
        operationInProgress = false
        processNext()
    }

    @Synchronized
    private fun processNext() {
        if (queue.isEmpty()) return
        operationInProgress = true
        queue.poll()?.run()
    }
}

// Usage:
gattQueue.enqueue(Runnable { bluetoothGatt?.readCharacteristic(characteristic) })

// In each GATT callback (onCharacteristicRead, onDescriptorWrite, etc.):
gattQueue.operationCompleted()
```

### Reading, writing, and subscribing to characteristics

```kotlin
// Read a characteristic
val service = bluetoothGatt?.getService(SERVICE_UUID)
val characteristic = service?.getCharacteristic(CHAR_UUID)
gattQueue.enqueue(Runnable { bluetoothGatt?.readCharacteristic(characteristic) })

// Write a characteristic (API 33+ and legacy)
gattQueue.enqueue(Runnable {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        bluetoothGatt?.writeCharacteristic(
            characteristic!!, byteArrayOf(0x01, 0x02), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
    } else {
        @Suppress("DEPRECATION")
        characteristic?.value = byteArrayOf(0x01, 0x02)
        @Suppress("DEPRECATION")
        bluetoothGatt?.writeCharacteristic(characteristic)
    }
})

// Enable notifications (two-step: local + remote via CCCD descriptor)
fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
    gatt.setCharacteristicNotification(characteristic, true)
    val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
    gattQueue.enqueue(Runnable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    })
}
```

### BLE service architecture

For production apps, wrap BLE operations in a bound `Service` (not an Activity). This lets BLE connections survive configuration changes and continue in the background:

```kotlin
class BleService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // Keep BluetoothGatt, operation queue, and callbacks here
    // Communicate with Activity via broadcasts or callback interfaces
}
```

Use `LocalBroadcastManager` or a callback interface to deliver GATT events to the Activity. This decouples UI from the BLE lifecycle.

## Companion Device Manager (CDM)

CDM provides a system-managed pairing flow that scans on behalf of your app, so you don't need `ACCESS_FINE_LOCATION`. Available on Android 8.0+ (API 26+). Note: Location Services must still be enabled on the device.

```xml
<uses-feature android:name="android.software.companion_device_setup" />
```

**Building the association request:**
```kotlin
val deviceFilter = BluetoothDeviceFilter.Builder()
    .setNamePattern(Pattern.compile("My Device"))
    .addServiceUuid(ParcelUuid(MY_SERVICE_UUID), null)
    .build()

val pairingRequest = AssociationRequest.Builder()
    .addDeviceFilter(deviceFilter)
    .setSingleDevice(true)  // false to show a list of matching devices
    .build()

val deviceManager = getSystemService(CompanionDeviceManager::class.java)
```

**API 33+ callback (preferred):**
```kotlin
deviceManager.associate(pairingRequest, executor, object : CompanionDeviceManager.Callback() {
    override fun onAssociationPending(intentSender: IntentSender) {
        startIntentSenderForResult(intentSender, SELECT_DEVICE_REQUEST_CODE, null, 0, 0, 0)
    }
    override fun onAssociationCreated(associationInfo: AssociationInfo) {
        // Association established — can now connect to the device
    }
    override fun onFailure(errorMessage: CharSequence?) { /* handle error */ }
})
```

**API 26–32 callback (deprecated but needed for backward compat):**
```kotlin
@Suppress("DEPRECATION")
deviceManager.associate(pairingRequest, object : CompanionDeviceManager.Callback() {
    override fun onDeviceFound(chooserLauncher: IntentSender) {
        startIntentSenderForResult(chooserLauncher, SELECT_DEVICE_REQUEST_CODE, null, 0, 0, 0)
    }
    override fun onFailure(error: CharSequence?) { /* handle error */ }
}, null)

// Handle result in onActivityResult:
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == SELECT_DEVICE_REQUEST_CODE && resultCode == RESULT_OK) {
        val device: BluetoothDevice? = data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
        device?.createBond() // Initiate bonding
    }
}
```

Filter types: `BluetoothDeviceFilter` (Classic), `BluetoothLeDeviceFilter` (BLE), `WifiDeviceFilter` (Wi-Fi).

After association, the device can use `REQUEST_COMPANION_RUN_IN_BACKGROUND` and `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` permissions for background access. Call `disassociate()` when the pairing is no longer needed.

## Bluetooth profiles

Use `BluetoothProfile` proxy objects to interact with standard profiles:

```kotlin
var headsetProxy: BluetoothHeadset? = null

val profileListener = object : BluetoothProfile.ServiceListener {
    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        if (profile == BluetoothProfile.HEADSET) {
            headsetProxy = proxy as BluetoothHeadset
        }
    }
    override fun onServiceDisconnected(profile: Int) {
        if (profile == BluetoothProfile.HEADSET) headsetProxy = null
    }
}

bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)

// When done:
bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, headsetProxy)
```

Common profiles: `HEADSET` (HFP), `A2DP` (audio streaming), `HEALTH` (HDP for medical devices).

## BLE Audio

BLE Audio (LE Audio) is available on Android 13+ (API 33+) and uses the LC3 codec for higher-quality audio at lower bitrates than Classic A2DP's SBC codec.

**Key concepts:**
- **Unicast:** Point-to-point audio streaming (replaces Classic A2DP for headphones/earbuds)
- **Broadcast (Auracast):** One-to-many audio streaming (public PA, TV audio sharing)
- **Audio Sharing:** A device can share its audio stream with nearby LE Audio devices

**Audio routing with LE Audio devices:** LE Audio devices appear through standard Android audio APIs. Use `AudioManager` to detect and route audio:

```kotlin
val audioManager = getSystemService(AudioManager::class.java)
val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
val leAudioDevices = devices.filter {
    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
}
```

**For VoIP/calling apps:** Use the Telecom API's self-managed connection service to route calls through LE Audio devices. This handles audio focus, routing, and Bluetooth state automatically.

**Checking LE Audio support:**
```kotlin
val supportsLeAudio = bluetoothAdapter?.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED
val supportsBroadcast = bluetoothAdapter?.isLeAudioBroadcastSourceSupported == BluetoothStatusCodes.FEATURE_SUPPORTED
```

LE Audio is still maturing across OEMs. Test on actual LE Audio hardware — emulators do not support it.

## Debugging Bluetooth issues

### Common GATT status codes

| Status | Name | Meaning |
|--------|------|---------|
| 0 | `GATT_SUCCESS` | Operation succeeded |
| 2 | `GATT_READ_NOT_PERMITTED` | Characteristic doesn't allow reads |
| 3 | `GATT_WRITE_NOT_PERMITTED` | Characteristic doesn't allow writes |
| 5 | `GATT_INSUFFICIENT_AUTHENTICATION` | Need bonding/pairing first |
| 6 | `GATT_REQUEST_NOT_SUPPORTED` | Remote device doesn't support this operation |
| 7 | `GATT_INVALID_OFFSET` | Read/write offset is invalid |
| 13 | `GATT_INVALID_ATTRIBUTE_LENGTH` | Written value length doesn't match attribute |
| 133 | `GATT_ERROR` | Generic catch-all error — see diagnosis below |
| 257 | `GATT_FAILURE` | Generic failure from the stack |

### Diagnosing status 133 (GATT_ERROR)

Status 133 is the most common and least helpful BLE error. Common causes:
1. **Leaked GATT connections** — Previous `BluetoothGatt` not closed. Android has a ~7 connection limit.
2. **Rapid reconnection** — Reconnecting too fast after disconnect. Add a 1-2 second delay.
3. **Missing `TRANSPORT_LE`** — On dual-mode devices, not specifying transport can route through Classic.
4. **Stale device address** — BLE devices with rotating addresses need a fresh scan before connecting.
5. **Missing permissions** — `BLUETOOTH_CONNECT` not granted on API 31+.
6. **Concurrent GATT operations** — Overlapping reads/writes without queuing.

**Nuclear option — GATT cache refresh** (unofficial API, use as last resort):
```kotlin
fun refreshGattCache(gatt: BluetoothGatt): Boolean {
    return try {
        val method = gatt.javaClass.getMethod("refresh")
        method.invoke(gatt) as Boolean
    } catch (e: Exception) {
        Log.w(TAG, "GATT cache refresh not available", e)
        false
    }
}
```

### Debugging tools

**HCI snoop log** — Captures raw Bluetooth HCI packets for low-level debugging:
1. Settings → Developer options → Enable Bluetooth HCI snoop log
2. Toggle Bluetooth off/on to start capture
3. Reproduce the issue
4. Pull the log: `adb pull /data/misc/bluetooth/logs/btsnoop_hci.log`
5. Open in Wireshark with the Bluetooth dissector

**dumpsys** — Check GATT connection state:
```bash
adb shell dumpsys bluetooth_manager | grep -A5 "GATT"
```

**Logcat filtering:**
```bash
adb logcat -s BluetoothGatt:V BluetoothAdapter:V bt_btif:V
```

### OEM-specific gotchas

- **Samsung:** May require GPS mode (not just network location) enabled for BLE scanning. Aggressive battery optimization kills background BLE scans — users may need to exclude the app from battery optimization. Some One UI versions throttle scanning more aggressively than AOSP.
- **Huawei/Honor:** Often requires explicit `TRANSPORT_LE` in `connectGatt()`. May throttle scan frequency independently of the Android 7+ system throttle.
- **Xiaomi/MIUI:** Background Bluetooth operations may require "Autostart" permission in MIUI settings.
- **General:** Always test on at least 2-3 OEMs. BLE behavior varies significantly across Bluetooth chipsets and vendor BSPs.

## Common anti-patterns

- **Scanning without a timeout.** BLE scans without `stopScan()` drain the battery fast. Always set a scan period.
- **Running blocking Bluetooth operations on the main thread.** `accept()`, `connect()`, `read()` are blocking — use coroutines with `Dispatchers.IO`.
- **Not canceling discovery before connecting.** Active discovery interferes with connection attempts. Call `cancelDiscovery()` first.
- **Requesting location permission on Android 12+ when you don't need it.** Use the new `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` permissions and `neverForLocation` flag instead.
- **Forgetting `maxSdkVersion="30"` on legacy permissions.** Without it, users on Android 12+ see unnecessary permission prompts.
- **Not serializing GATT operations.** Issuing multiple reads/writes without waiting for callbacks causes silent failures. Use an operation queue.
- **Using `BluetoothAdapter.getDefaultAdapter()`** — Deprecated. Use `BluetoothManager.adapter` from system services.
- **Not closing `BluetoothGatt` when done.** Leaked GATT connections exhaust the ~7-connection system limit and cause status 133 errors for all apps.
- **Assuming Bluetooth is always available.** Check `bluetoothAdapter != null` and degrade gracefully.
- **Ignoring OEM-specific behaviors.** See the OEM gotchas section — Samsung, Huawei, and Xiaomi all have quirks that affect Bluetooth behavior.
- **Not using `TRANSPORT_LE` in `connectGatt()`** — On dual-mode devices, omitting transport can route through Classic, causing connection failures for BLE peripherals.
- **Exceeding the BLE scan throttle.** On Android 7+, more than 5 `startScan()` calls in 30 seconds silently stops scans. Use `ScanFilter` to avoid throttling.

## When reviewing or debugging Bluetooth code

- Check permissions first — wrong permissions are the #1 cause of "it works on my phone but not theirs."
- Look for leaked GATT connections — every `connectGatt()` must have a corresponding `close()` in all paths.
- Verify GATT operations are serialized — overlapping operations cause silent failures.
- Check for missing `cancelDiscovery()` before Classic connections.
- Look for `startActivityForResult` and `requestPermissions` — replace with `ActivityResultContracts`.
- Look for `BluetoothAdapter.getDefaultAdapter()` — replace with `BluetoothManager.adapter`.
- Verify the code branches on `Build.VERSION.SDK_INT` for permissions and deprecated API signatures.
- In BLE code, verify `setCharacteristicNotification()` is paired with a CCCD descriptor write — both are required for notifications to work.
- Check that `onDestroy()` unregisters BroadcastReceivers and closes sockets/GATT connections.

## Response pattern

Use this shape when it helps:

1. Direct diagnosis or recommendation
2. Working Kotlin code (Java if the user prefers)
3. Why this approach follows Android Bluetooth best practices
4. Permission and API-level notes
5. Relevant official docs

Do not force a rigid template for quick questions, but keep answers easy to scan.

## Examples

**Example 1**

User: "How do I scan for BLE devices and connect to one that advertises a specific service UUID?"

Good response shape: show the filtered scan with `ScanFilter` + `ScanSettings`, the `ScanCallback`, the `connectGatt()` call with `TRANSPORT_LE`, GATT callback with service discovery, and the permission setup for both API 31+ and legacy.

**Example 2**

User: "I keep getting status 133 when connecting to my BLE peripheral."

Good response shape: explain status 133 as a generic catch-all, list the 6 common causes, show the diagnostic checklist, provide the GATT cache refresh workaround, and recommend HCI snoop logging for persistent issues.

**Example 3**

User: "My BLE scanning works on my Pixel 8 but returns no results on a Samsung Galaxy S10."

Good response shape: immediately identify the API level difference (Pixel 8 = API 34, S10 likely API 29-30), diagnose the missing `ACCESS_FINE_LOCATION` runtime grant and Location Services toggle, show the dual-manifest and runtime branching code, mention Samsung GPS mode gotcha.

**Example 4**

User: "I want to pair with a BLE wearable without asking for location permission. Is that possible?"

Good response shape: recommend CompanionDeviceManager, show the `AssociationRequest` with `BluetoothLeDeviceFilter`, show both API 33+ and API 26-32 callback patterns, note that CDM doesn't require `ACCESS_FINE_LOCATION` but does need Location Services enabled.

**Example 5**

User: "Can you review my Bluetooth Classic chat app code for issues?"

Good response shape: check permissions, look for blocking calls on main thread, verify cancelDiscovery before connect, check socket/receiver cleanup, suggest coroutines over raw Thread, recommend bound Service architecture for production.

**Example 6**

User: "Build me a simple Bluetooth app that discovers devices, lets me pick one, and sends a message over RFCOMM."

Good response shape: show permissions (dual manifest + runtime request), discovery with getBondedDevices() first then startDiscovery(), BOTH the server-side accept loop (`listenUsingRfcommWithServiceRecord` + `accept()`) AND the client-side connect (`createRfcommSocketToServiceRecord` + `connect()`) with matching UUIDs, cancelDiscovery() before connect, the data transfer stream, and coroutines for all blocking operations. Even though the user only said "sends a message", the server socket is essential — the remote device needs something to connect to. Omitting the server side leaves the implementation incomplete.

## Reference file

Read `references/official-sources.md` when you need the curated source map or exact doc-backed facts to cite.
