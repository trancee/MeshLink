# Android BLE — Service Pattern and Background Reference

<service_pattern>
## BluetoothLeService Bound-Service Pattern

The recommended architecture uses a bound `Service` to manage the BLE connection, with the Activity communicating via broadcasts.

### Service Structure

```kotlin
class BluetoothLeService : Service() {
    private val binder = LocalBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectionState = STATE_DISCONNECTED

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothLeService = this@BluetoothLeService
    }

    override fun onBind(intent: Intent): IBinder = binder

    fun initialize(): Boolean {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.")
            return false
        }
        return true
    }

    fun connect(address: String): Boolean {
        bluetoothAdapter?.let { adapter ->
            try {
                val device = adapter.getRemoteDevice(address)
                bluetoothGatt = device.connectGatt(this, false, bluetoothGattCallback)
                return true
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Device not found with provided address.")
                return false
            }
        } ?: run {
            Log.w(TAG, "BluetoothAdapter not initialized")
            return false
        }
    }

    private val bluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionState = STATE_CONNECTED
                broadcastUpdate(ACTION_GATT_CONNECTED)
                bluetoothGatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionState = STATE_DISCONNECTED
                broadcastUpdate(ACTION_GATT_DISCONNECTED)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic)
        }
    }

    private fun broadcastUpdate(action: String) {
        sendBroadcast(Intent(action))
    }

    private fun broadcastUpdate(action: String, characteristic: BluetoothGattCharacteristic) {
        val intent = Intent(action)
        // Parse characteristic data and attach as extras
        val data = characteristic.value
        if (data != null && data.isNotEmpty()) {
            intent.putExtra(EXTRA_DATA, data.joinToString(" ") { "%02X".format(it) })
        }
        sendBroadcast(intent)
    }

    fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
        bluetoothGatt?.readCharacteristic(characteristic)
    }

    fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic, enabled: Boolean
    ) {
        bluetoothGatt?.let { gatt ->
            gatt.setCharacteristicNotification(characteristic, enabled)
            // Write to CCCD descriptor to enable server-side notifications
            val descriptor = characteristic.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )
            descriptor.value = if (enabled)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    fun getSupportedGattServices(): List<BluetoothGattService>? = bluetoothGatt?.services

    override fun onUnbind(intent: Intent?): Boolean {
        bluetoothGatt?.close()
        bluetoothGatt = null
        return super.onUnbind(intent)
    }

    companion object {
        const val TAG = "BluetoothLeService"
        const val ACTION_GATT_CONNECTED = "com.example.bluetooth.le.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED = "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED"
        const val ACTION_GATT_SERVICES_DISCOVERED = "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED"
        const val ACTION_DATA_AVAILABLE = "com.example.bluetooth.le.ACTION_DATA_AVAILABLE"
        const val EXTRA_DATA = "com.example.bluetooth.le.EXTRA_DATA"
        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTED = 2
    }
}
```

### Activity Binding

```kotlin
class DeviceControlActivity : AppCompatActivity() {
    private var bluetoothService: BluetoothLeService? = null
    private var deviceAddress: String = ""

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            bluetoothService = (service as BluetoothLeService.LocalBinder).getService()
            bluetoothService?.let { svc ->
                if (!svc.initialize()) { finish(); return }
                svc.connect(deviceAddress)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bluetoothService = null
        }
    }

    private val gattUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothLeService.ACTION_GATT_CONNECTED -> { /* update UI */ }
                BluetoothLeService.ACTION_GATT_DISCONNECTED -> { /* update UI */ }
                BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED -> {
                    displayGattServices(bluetoothService?.getSupportedGattServices())
                }
                BluetoothLeService.ACTION_DATA_AVAILABLE -> {
                    val data = intent.getStringExtra(BluetoothLeService.EXTRA_DATA)
                    // display data
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, BluetoothLeService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(gattUpdateReceiver, IntentFilter().apply {
            addAction(BluetoothLeService.ACTION_GATT_CONNECTED)
            addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED)
            addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED)
            addAction(BluetoothLeService.ACTION_DATA_AVAILABLE)
        })
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(gattUpdateReceiver)
    }
}
```
</service_pattern>

<scanning>
## Scanning Details

### Basic Scan

```kotlin
val scanner = bluetoothAdapter.bluetoothLeScanner  // null if BT disabled!

private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        val device: BluetoothDevice = result.device
        // add to list, check name/address, etc.
    }
}

// Start with time limit
handler.postDelayed({ scanner.stopScan(scanCallback) }, 10_000L)
scanner.startScan(scanCallback)
```

### Filtered Scan

```kotlin
val filter = ScanFilter.Builder()
    .setServiceUuid(ParcelUuid(MY_SERVICE_UUID))
    // .setDeviceName("MyDevice")
    // .setDeviceAddress("AA:BB:CC:DD:EE:FF")
    .build()

val settings = ScanSettings.Builder()
    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)  // or BALANCED, LOW_POWER
    .build()

scanner.startScan(listOf(filter), settings, scanCallback)
```

### PendingIntent Scan (Background — Process May Be Dead)

```kotlin
val intent = PendingIntent.getBroadcast(context, 0,
    Intent(context, BleScanReceiver::class.java),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

scanner.startScan(filters, settings, intent)
// System delivers scan results via broadcast even if process is killed
```

### Rules

- **BluetoothLeScanner is null** if Bluetooth is disabled on the device
- **Always time-limit scans** — never scan indefinitely
- **Stop as soon as you find the target** — scanning is battery-intensive
- **Cannot scan BLE and Classic simultaneously** — pick one
- Don't schedule periodic scans — use PendingIntent or CompanionDeviceManager instead
</scanning>

<connection_management>
## GATT Connection Management

### autoConnect Parameter

| Value | Behavior | Use When |
|-------|----------|----------|
| `false` (direct) | Connects immediately, fails if device unavailable | Device known to be nearby (just scanned) |
| `true` (auto) | Connects when device comes in range, auto-reconnects on disconnect | Background connection to bonded device |

### Connection States

```kotlin
override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
    when (newState) {
        BluetoothProfile.STATE_CONNECTED -> {
            // Immediately discover services
            gatt.discoverServices()
        }
        BluetoothProfile.STATE_DISCONNECTED -> {
            // Clean up, maybe reconnect
        }
    }
}
```

### Platform Behavior

- **Android < 10**: Only one connection request at a time, subsequent requests queued
- **Android ≥ 10**: Connection requests batched for execution

### Always Close

Call `gatt.close()` when the connection is no longer needed. Best practice: close in `onUnbind()` of the service. Failing to close drains battery.
</connection_management>

<data_transfer>
## Data Transfer

### Service Discovery

Call `discoverServices()` immediately after `STATE_CONNECTED`. Results arrive in `onServicesDiscovered()`.

```kotlin
// After discovery, enumerate services and characteristics
val services: List<BluetoothGattService> = gatt.services
for (service in services) {
    val uuid = service.uuid
    for (char in service.characteristics) {
        // char.uuid, char.properties (READ, WRITE, NOTIFY, etc.)
    }
}
```

### Read a Characteristic (Async)

```kotlin
gatt.readCharacteristic(characteristic)
// Result arrives in onCharacteristicRead callback
```

### Write a Characteristic

```kotlin
characteristic.value = byteArrayOf(0x01, 0x02)
gatt.writeCharacteristic(characteristic)
// Result arrives in onCharacteristicWrite callback
```

### Enable Notifications (Two Steps)

1. **Client-side**: `gatt.setCharacteristicNotification(characteristic, true)`
2. **Server-side**: Write to the CCCD (Client Characteristic Configuration Descriptor)

```kotlin
val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
val descriptor = characteristic.getDescriptor(CCCD_UUID)
descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
gatt.writeDescriptor(descriptor)
```

Both steps are required. Without the CCCD write, the peripheral won't send notifications.

Data arrives in `onCharacteristicChanged()`.

### Parsing Characteristic Data

```kotlin
// Heart Rate Measurement example
val flag = characteristic.properties
val format = if (flag and 0x01 != 0)
    BluetoothGattCharacteristic.FORMAT_UINT16
else
    BluetoothGattCharacteristic.FORMAT_UINT8
val heartRate = characteristic.getIntValue(format, 1)

// Generic hex dump
val hex = characteristic.value?.joinToString(" ") { "%02X".format(it) }
```
</data_transfer>

<background_ble>
## Background BLE

### Decision Tree

```
Need to FIND a device in background?
  ├─ Device is advertising → PendingIntent scan (wakes process)
  └─ Companion device → CompanionDeviceManager.startObservingDevicePresence()

Need to CONNECT in background?
  ├─ Short task → WorkManager (OneTimeWorkRequest / PeriodicWorkRequest)
  ├─ Long task → Foreground service (connectedDevice type)
  └─ Companion device → CompanionDeviceService

Need to STAY CONNECTED?
  ├─ While switching apps → Foreground service (connectedDevice type)
  └─ Listening to notifications → CompanionDeviceService + REQUEST_COMPANION_RUN_IN_BACKGROUND
```

### PendingIntent Scan

Wakes your process when a matching device is found, even if the process was killed. The peripheral must be advertising.

### CompanionDeviceManager

Use for companion device pairing and presence detection. Limitations: limited filtering, no random MAC address support.

```kotlin
// Keep companion app awake when device in range
companionDeviceManager.startObservingDevicePresence(
    ObservingDevicePresenceRequest.Builder()
        .setDeviceId(associationId)
        .build()
)
```

### Foreground Service

Use `connectedDevice` foreground service type for long-lived connections.

**Restrictions:**
- Android 12+: Foreground service launch restrictions from background
- Must declare `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />`

### CompanionDeviceService

For apps that need to run in background indefinitely with a companion device:

```xml
<uses-permission android:name="android.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND" />
<!-- OR for starting foreground services from background: -->
<uses-permission android:name="android.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND" />
```

### autoConnect for Reconnection

Use `connectGatt(context, true, callback)` with `autoConnect = true` — the system automatically reconnects when the peripheral comes back in range after a disconnection.
</background_ble>
