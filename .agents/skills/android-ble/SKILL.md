---
name: android-ble
description: Android BLE development reference. Covers GATT/ATT concepts, central/peripheral roles, permissions (BLUETOOTH_SCAN/CONNECT/ADVERTISE + neverForLocation on Android 12+, legacy BLUETOOTH/ACCESS_FINE_LOCATION for older, uses-feature declaration), scanning (BluetoothLeScanner, ScanFilter, ScanSettings, PendingIntent for background), GATT connections (connectGatt, autoConnect, BluetoothGattCallback, service discovery, memory-safe read/write/notify APIs since API 33 vs deprecated overloads, CCCD notifications), bound service pattern (BluetoothLeService lifecycle), background BLE (CompanionDeviceManager, WorkManager, foreground service), and app-layer security. Use when implementing BLE scanning, GATT connections, notifications, background BLE, or any Android BLE question.
---

<essential_principles>

**Android BLE** — built-in platform support for Bluetooth Low Energy in the central role. Scan for peripherals, connect to GATT servers, read/write characteristics, receive notifications.

### BLE Concepts

| Term | Meaning |
|------|---------|
| **GATT** | Generic Attribute Profile — spec for sending/receiving "attributes" over BLE. All BLE app profiles are based on GATT. |
| **ATT** | Attribute Protocol — underlies GATT. Each attribute identified by 128-bit **UUID**. |
| **Service** | Collection of characteristics (e.g. "Heart Rate Monitor" service). |
| **Characteristic** | Single value + 0-n descriptors. The unit of data you read/write. |
| **Descriptor** | Metadata about a characteristic (human-readable description, acceptable range, CCCD for notifications). |
| **Central** | Device that scans and initiates connections (your Android phone). |
| **Peripheral** | Device that advertises and waits for connections (sensor, tracker). |
| **GATT Client** | Sends data requests (typically the phone app). |
| **GATT Server** | Fulfills data requests (typically the peripheral). |

### End-to-End Workflow

```
1. Declare permissions in manifest
2. Get BluetoothAdapter, check Bluetooth enabled
3. Scan for BLE devices (BluetoothLeScanner)
4. Connect to GATT server (device.connectGatt)
5. Discover services (gatt.discoverServices)
6. Read/write characteristics, enable notifications
7. Close connection when done (gatt.close)
```

### Permissions (Android 12+ vs Legacy)

Targeting API 31+: declare runtime permissions `BLUETOOTH_SCAN` (add `android:usesPermissionFlags="neverForLocation"` unless you truly derive location from scans), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` — plus legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` capped with `android:maxSdkVersion="30"` for pre-12 devices. Targeting API ≤30: declare `BLUETOOTH`, `BLUETOOTH_ADMIN`, and the runtime permission `ACCESS_FINE_LOCATION` (scans can reveal location on old versions). All of these except plain `BLUETOOTH`/`BLUETOOTH_ADMIN` must be requested at runtime. Full matrix and manifest snippets in `references/permissions.md`.

### Scanning

```kotlin
val scanner = bluetoothAdapter.bluetoothLeScanner
val handler = Handler(Looper.getMainLooper())

// Always set a time limit!
handler.postDelayed({ scanner.stopScan(callback) }, 10_000)
scanner.startScan(callback)
```

Use `startScan(filters, settings, callback)` to filter by service UUID, device name, etc.

**Rules:** Stop scanning as soon as you find the target. Never scan in a loop. Can't scan BLE and Classic simultaneously.

### Connecting

```kotlin
// Direct connect (fail if device not available)
val gatt = device.connectGatt(context, false, gattCallback)

// Auto-connect (reconnect when peripheral comes in range)
val gatt = device.connectGatt(context, true, gattCallback)
```

### BluetoothGattCallback — The Core API Surface

```kotlin
object : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            gatt.discoverServices()  // always discover after connect
        }
    }
    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        // gatt.services now available
    }
    // API 33+: memory-safe overload — value is a snapshot, doesn't race characteristic.getValue()
    override fun onCharacteristicRead(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        // async read result
    }
    override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray) {
        // notification/indication received
    }
}
```

**API 33+ (Android 13+) deprecated the old `onCharacteristicRead(gatt, char, status)` / `onCharacteristicChanged(gatt, char)` overloads** (and `characteristic.getValue()`/`setValue()`) because they read a mutable `characteristic` object that can change concurrently. Use the `value: ByteArray`-carrying overloads above. If `minSdk < 33`, keep both overloads (the new one wins on API 33+; only the old one fires below it).

### Enable Notifications

```kotlin
gatt.setCharacteristicNotification(characteristic, true)
// Must also write to the CCCD descriptor:
val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)  // memory-safe, API 33+
} else {
    @Suppress("DEPRECATION")
    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    @Suppress("DEPRECATION")
    gatt.writeDescriptor(descriptor)
}
```

Same split applies to writes: `gatt.writeCharacteristic(characteristic, value, writeType)` (API 33+, returns `Int` status) replaces the deprecated `characteristic.setValue(...)` + `gatt.writeCharacteristic(characteristic)` pair.

### Always Close

```kotlin
override fun onUnbind(intent: Intent?): Boolean {
    bluetoothGatt?.close()
    bluetoothGatt = null
    return super.onUnbind(intent)
}
```

### Security Warning

BLE data is accessible to **ALL apps** on the user's device once paired. Implement app-layer encryption for sensitive data.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Full permission matrix (Android 12+ runtime permissions vs legacy API ≤30, `neverForLocation` assertion, `ACCESS_BACKGROUND_LOCATION`, `<uses-feature>` declaration and runtime capability check) | `references/permissions.md` |
| Full BluetoothLeService pattern (bound service, LocalBinder, BroadcastReceiver, intent actions, activity lifecycle), scanning details (ScanFilter, ScanSettings, ScanCallback, PendingIntent scanning), GATT connection management (autoConnect semantics, connection states, close lifecycle), data transfer (service discovery, memory-safe vs deprecated read/write/notify APIs, CCCD notification setup, broadcastUpdate with data parsing), background BLE (PendingIntent scan, CompanionDeviceManager, WorkManager, foreground service connectedDevice type, CompanionDeviceService, stay-connected patterns) | `references/service-pattern-and-background.md` |

</routing>

<reference_index>

**permissions.md** — Android 12+ (API 31+) manifest declarations (`BLUETOOTH_SCAN`/`BLUETOOTH_ADVERTISE`/`BLUETOOTH_CONNECT` as runtime permissions triggering one "Nearby devices" prompt, legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` capped with `maxSdkVersion="30"`), `neverForLocation` flag on `BLUETOOTH_SCAN` (trade-off: filters some beacons from scan results) plus dropping/capping `ACCESS_FINE_LOCATION`. Android ≤11 (API ≤30) declarations (`BLUETOOTH`, `BLUETOOTH_ADMIN` for discovery/settings, runtime `ACCESS_FINE_LOCATION` since scans can reveal location, `ACCESS_BACKGROUND_LOCATION` for foreground services on API 29/30). CompanionDeviceManager as a location-permission-free scanning path on API 26+. `<uses-feature android.hardware.bluetooth_le>` required vs optional, `PackageManager.hasSystemFeature(FEATURE_BLUETOOTH_LE)` runtime check.

**service-pattern-and-background.md** — Full BluetoothLeService bound-service pattern (Service with LocalBinder, DeviceControlActivity with ServiceConnection, bindService/unbindService lifecycle, initialize() for BluetoothAdapter setup, connect(address) with getRemoteDevice, BluetoothGattCallback declared in service, broadcastUpdate via Intent for ACTION_GATT_CONNECTED/DISCONNECTED/SERVICES_DISCOVERED/DATA_AVAILABLE, BroadcastReceiver in activity registered in onResume/unregistered in onPause, IntentFilter construction). Scanning (BluetoothLeScanner from BluetoothAdapter.bluetoothLeScanner — null if Bluetooth disabled, startScan(ScanCallback) basic scan, startScan(List<ScanFilter>, ScanSettings, ScanCallback) filtered scan, startScan with PendingIntent for background process-dead scanning, ScanCallback.onScanResult delivers ScanResult with BluetoothDevice, always time-limit scans, stop when target found). GATT connection (connectGatt(context, autoConnect, callback) — autoConnect false = direct connect fails if unavailable, true = auto-reconnect when peripheral in range, Android <10 queues connection requests serially, ≥10 batches them, BluetoothProfile.STATE_CONNECTED/STATE_DISCONNECTED in onConnectionStateChange, BluetoothAdapter.getRemoteLeDevice()/getBondedDevices() as alternate BluetoothDevice sources). Data transfer (discoverServices() immediately after STATE_CONNECTED, onServicesDiscovered with GATT_SUCCESS check, getServices() returns List<BluetoothGattService>, iterate services → characteristics by UUID; memory-safe API 33+ overloads — onCharacteristicRead(gatt,char,value,status), onCharacteristicChanged(gatt,char,value), onDescriptorRead(gatt,descriptor,status,value), gatt.writeCharacteristic(char,value,writeType), gatt.writeDescriptor(descriptor,value) — versus deprecated characteristic/descriptor.getValue()/setValue() + no-value callback and gatt.write*(obj)-only overloads, minSdk<33 dual-overload pattern; setCharacteristicNotification + write CCCD descriptor UUID 00002902-0000-1000-8000-00805f9b34fb with ENABLE_NOTIFICATION_VALUE; broadcastUpdate with characteristic data parsing — heart rate measurement example with FORMAT_UINT8/UINT16, hex formatting for generic characteristics). Background BLE (PendingIntent scan wakes process when matching device found, CompanionDeviceManager for companion pairing with presence detection via startObservingDevicePresence, CompanionDeviceService with REQUEST_COMPANION_RUN_IN_BACKGROUND or REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND, WorkManager with PeriodicWorkRequest/OneTimeWorkRequest for connection tasks, foreground service with connectedDevice type for long-lived connections — launch restrictions apply Android 12+, stay connected while switching apps via foreground service or CompanionDeviceService, stay connected for peripheral notifications via setCharacteristicNotification + keep connection alive).

</reference_index>
