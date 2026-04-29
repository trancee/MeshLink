---
name: android-ble
description: Complete reference for Android Bluetooth Low Energy (BLE) development from the official Android developer documentation. Covers the full BLE workflow from scanning to data transfer. Concepts (GATT/ATT protocol stack, services/characteristics/descriptors identified by UUID, central vs peripheral roles, GATT client vs GATT server roles, profiles defined by Bluetooth SIG). Permissions (BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE on Android 12+, ACCESS_FINE_LOCATION for scan results with physical addresses on older versions, neverForLocation flag to avoid location permission when not needed). Scanning (BluetoothLeScanner.startScan with ScanCallback or PendingIntent for background, ScanFilter to narrow results by service UUID/device name/MAC, ScanSettings for scan mode LOW_POWER/BALANCED/LOW_LATENCY, always set time limit and stop when device found, cannot scan BLE and Classic simultaneously). GATT connection (BluetoothDevice.connectGatt with autoConnect boolean — false for direct connect that fails if unavailable, true for auto-reconnect when peripheral comes in range, returns BluetoothGatt instance, BluetoothGattCallback for onConnectionStateChange/onServicesDiscovered/onCharacteristicRead/onCharacteristicChanged, always call close() when done). Bound service pattern (BluetoothLeService extends Service with LocalBinder, DeviceControlActivity binds to service, service manages BluetoothGatt lifecycle, broadcasts connection state via Intent actions). Service discovery (discoverServices() after STATE_CONNECTED, onServicesDiscovered callback, getServices() returns List<BluetoothGattService>, each service has characteristics with UUIDs). Data transfer (readCharacteristic async with onCharacteristicRead callback, writeCharacteristic, setCharacteristicNotification + write CCCD descriptor to enable server-side notifications, onCharacteristicChanged for push updates). Background BLE (BluetoothLeScanner.startScan with PendingIntent to wake process, CompanionDeviceManager for companion device pairing and presence detection, WorkManager for connection tasks, foreground service with connectedDevice type for long-lived connections, CompanionDeviceService with REQUEST_COMPANION_RUN_IN_BACKGROUND permission, autoConnect=true for auto-reconnect). Security caution (BLE data accessible to ALL apps on user's device once paired — implement app-layer security for sensitive data). Use when implementing BLE scanning, connecting to GATT servers, reading/writing characteristics, enabling notifications, running BLE in background, choosing between direct connect and auto-connect, structuring a BluetoothLeService, or any Android BLE question.
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
    override fun onCharacteristicRead(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
        // async read result
    }
    override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        // notification/indication received
    }
}
```

### Enable Notifications

```kotlin
gatt.setCharacteristicNotification(characteristic, true)
// Must also write to the CCCD descriptor:
val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
gatt.writeDescriptor(descriptor)
```

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
| Full BluetoothLeService pattern (bound service, LocalBinder, BroadcastReceiver, intent actions, activity lifecycle), scanning details (ScanFilter, ScanSettings, ScanCallback, PendingIntent scanning), GATT connection management (autoConnect semantics, connection states, close lifecycle), data transfer (service discovery, read/write characteristics, notification setup with CCCD, broadcastUpdate with data parsing), background BLE (PendingIntent scan, CompanionDeviceManager, WorkManager, foreground service connectedDevice type, CompanionDeviceService, stay-connected patterns) | `references/service-pattern-and-background.md` |

</routing>

<reference_index>

**service-pattern-and-background.md** — Full BluetoothLeService bound-service pattern (Service with LocalBinder, DeviceControlActivity with ServiceConnection, bindService/unbindService lifecycle, initialize() for BluetoothAdapter setup, connect(address) with getRemoteDevice, BluetoothGattCallback declared in service, broadcastUpdate via Intent for ACTION_GATT_CONNECTED/DISCONNECTED/SERVICES_DISCOVERED/DATA_AVAILABLE, BroadcastReceiver in activity registered in onResume/unregistered in onPause, IntentFilter construction). Scanning (BluetoothLeScanner from BluetoothAdapter.bluetoothLeScanner — null if Bluetooth disabled, startScan(ScanCallback) basic scan, startScan(List<ScanFilter>, ScanSettings, ScanCallback) filtered scan, startScan with PendingIntent for background process-dead scanning, ScanCallback.onScanResult delivers ScanResult with BluetoothDevice, always time-limit scans, stop when target found). GATT connection (connectGatt(context, autoConnect, callback) — autoConnect false = direct connect fails if unavailable, true = auto-reconnect when peripheral in range, Android <10 queues connection requests serially, ≥10 batches them, BluetoothProfile.STATE_CONNECTED/STATE_DISCONNECTED in onConnectionStateChange). Data transfer (discoverServices() immediately after STATE_CONNECTED, onServicesDiscovered with GATT_SUCCESS check, getServices() returns List<BluetoothGattService>, iterate services → characteristics by UUID, readCharacteristic async → onCharacteristicRead callback, writeCharacteristic, setCharacteristicNotification + write CCCD descriptor UUID 00002902-0000-1000-8000-00805f9b34fb with ENABLE_NOTIFICATION_VALUE, onCharacteristicChanged for push data, broadcastUpdate with characteristic data parsing — heart rate measurement example with FORMAT_UINT8/UINT16, hex formatting for generic characteristics). Background BLE (PendingIntent scan wakes process when matching device found, CompanionDeviceManager for companion pairing with presence detection via startObservingDevicePresence, CompanionDeviceService with REQUEST_COMPANION_RUN_IN_BACKGROUND or REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND, WorkManager with PeriodicWorkRequest/OneTimeWorkRequest for connection tasks, foreground service with connectedDevice type for long-lived connections — launch restrictions apply Android 12+, stay connected while switching apps via foreground service or CompanionDeviceService, stay connected for peripheral notifications via setCharacteristicNotification + keep connection alive).

</reference_index>
