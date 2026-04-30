---
name: android-gatt-server
description: Android GATT Server role reference for hosting BLE services on Android. Covers BluetoothGattServer (openGattServer, addService, sendResponse required for all requests, notifyCharacteristicChanged, connect/cancelConnection, PHY management, cleanup). BluetoothGattServerCallback (connection state, read/write requests, descriptor requests, executeWrite, onNotificationSent sequencing, MTU changes). Service hierarchy (BluetoothGattService primary/secondary, BluetoothGattCharacteristic with properties/permissions, BluetoothGattDescriptor including CCCD for notifications/indications). Use when implementing a BLE peripheral/GATT server on Android, hosting custom services, handling read/write requests, sending notifications/indications, or any BluetoothGattServer question.
---

<essential_principles>

**Android GATT Server** — host BLE services on an Android device so remote clients can read, write, and subscribe. Your Android device acts as the **peripheral** (GATT server).

### GATT Hierarchy

```
BluetoothGattService (UUID, PRIMARY or SECONDARY)
 └── BluetoothGattCharacteristic (UUID, properties, permissions)
      └── BluetoothGattDescriptor (UUID, permissions)
           └── CCCD (00002902) — enables notifications/indications
```

### End-to-End Setup

```kotlin
// 1. Open GATT server
val manager = getSystemService(BluetoothManager::class.java)
val gattServer = manager.openGattServer(context, gattServerCallback)

// 2. Build service hierarchy
val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

val characteristic = BluetoothGattCharacteristic(
    CHAR_UUID,
    BluetoothGattCharacteristic.PROPERTY_READ or
        BluetoothGattCharacteristic.PROPERTY_WRITE or
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
    BluetoothGattCharacteristic.PERMISSION_READ or
        BluetoothGattCharacteristic.PERMISSION_WRITE
)

// Add CCCD so clients can subscribe to notifications
val cccd = BluetoothGattDescriptor(
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
)
characteristic.addDescriptor(cccd)
service.addCharacteristic(characteristic)

// 3. Register service — wait for onServiceAdded before adding more!
gattServer.addService(service)

// 4. When done:
gattServer.close()
```

### The Callback — Core of All Server Logic

```kotlin
val gattServerCallback = object : BluetoothGattServerCallback() {
    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        // Track connected clients
    }

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice, requestId: Int, offset: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        // MUST call sendResponse!
        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, myValue)
    }

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice, requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean, responseNeeded: Boolean,
        offset: Int, value: ByteArray
    ) {
        // Process the written value
        if (responseNeeded) {
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice, requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean, responseNeeded: Boolean,
        offset: Int, value: ByteArray
    ) {
        // Client enabling/disabling notifications via CCCD
        if (responseNeeded) {
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
        // Safe to send next notification now
    }
}
```

### Sending Notifications

```kotlin
// API 33+ (memory-safe — pass value explicitly)
gattServer.notifyCharacteristicChanged(device, characteristic, false, myValue)
//                                                             ^^^^^ false=notification, true=indication
```

**Must wait for `onNotificationSent`** before sending another notification. Max value: 512 bytes.

### Critical Rules

1. **`sendResponse()` is REQUIRED** for every `onCharacteristicReadRequest`, `onCharacteristicWriteRequest` (if `responseNeeded`), `onDescriptorReadRequest`, `onDescriptorWriteRequest` (if `responseNeeded`), and `onExecuteWrite`
2. **Wait for `onServiceAdded`** before calling `addService()` again
3. **Wait for `onNotificationSent`** before sending the next notification
4. **`close()` the server** as early as possible when done
5. **Notification vs Indication**: `confirm=false` → notification (fire-and-forget), `confirm=true` → indication (acknowledged by client)
6. Use `BluetoothManager.getConnectedDevices(BluetoothProfile.GATT)` for connected device list — NOT `gattServer.getConnectedDevices()`

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| BluetoothGattServer methods (addService, sendResponse, notifyCharacteristicChanged, connect/cancelConnection, readPhy/setPreferredPhy, service management, close), BluetoothGattServerCallback methods (all 12 callbacks with parameters and semantics), service/characteristic/descriptor construction (properties, permissions, format types, write types, CCCD setup, descriptor values), PHY management | `references/server-api-and-types.md` |

</routing>

<reference_index>

**server-api-and-types.md** — BluetoothGattServer full API (openGattServer via BluetoothManager, addService returns true if request initiated and triggers onServiceAdded — do not add another before callback, sendResponse with device/requestId/status/offset/value required for all read/write request callbacks, notifyCharacteristicChanged API 33+ with explicit byte[] value returns BluetoothStatusCodes and deprecated boolean overload without byte[] marked not memory safe, connect with autoConnect boolean for server-initiated connections — first connection direct false subsequent auto true, cancelConnection disconnects or cancels in-progress, clearServices removes all, removeService removes one, getService by UUID returns first if multiples, getServices returns hosted list, readPhy triggers onPhyRead, setPreferredPhy with txPhy/rxPhy/phyOptions bitmasks is recommendation only, close releases resources). BluetoothGattServerCallback all 12 callbacks (onConnectionStateChange device/status/newState STATE_CONNECTED/DISCONNECTED, onCharacteristicReadRequest device/requestId/offset/characteristic — MUST sendResponse, onCharacteristicWriteRequest device/requestId/characteristic/preparedWrite/responseNeeded/offset/value — MUST sendResponse if responseNeeded, onDescriptorReadRequest device/requestId/offset/descriptor — MUST sendResponse, onDescriptorWriteRequest device/requestId/descriptor/preparedWrite/responseNeeded/offset/value — MUST sendResponse if responseNeeded, onExecuteWrite device/requestId/execute boolean for prepared write commit or cancel — MUST sendResponse, onServiceAdded status/service — GATT_SUCCESS confirms, onNotificationSent device/status — wait before sending next, onMtuChanged device/mtu — client negotiated new MTU, onPhyRead device/txPhy/rxPhy/status, onPhyUpdate device/txPhy/rxPhy/status, onSubrateChange device/subrateMode/status with SUBRATE_MODE values). BluetoothGattService (SERVICE_TYPE_PRIMARY=0 SERVICE_TYPE_SECONDARY=1, constructor UUID+serviceType, addCharacteristic, addService for included services, getCharacteristic by UUID returns first match, getCharacteristics list, getIncludedServices list, getInstanceId distinguishes multiple same-UUID services, getType, getUuid). BluetoothGattCharacteristic (constructor UUID+properties+permissions, properties bitmask PROPERTY_BROADCAST=0x01 PROPERTY_READ=0x02 PROPERTY_WRITE_NO_RESPONSE=0x04 PROPERTY_WRITE=0x08 PROPERTY_NOTIFY=0x10 PROPERTY_INDICATE=0x20 PROPERTY_SIGNED_WRITE=0x40 PROPERTY_EXTENDED_PROPS=0x80, permissions bitmask PERMISSION_READ=0x01 PERMISSION_READ_ENCRYPTED=0x02 PERMISSION_READ_ENCRYPTED_MITM=0x04 PERMISSION_WRITE=0x10 PERMISSION_WRITE_ENCRYPTED=0x20 PERMISSION_WRITE_ENCRYPTED_MITM=0x40 PERMISSION_WRITE_SIGNED=0x80 PERMISSION_WRITE_SIGNED_MITM=0x100, write types WRITE_TYPE_DEFAULT=2 acknowledged WRITE_TYPE_NO_RESPONSE=1 WRITE_TYPE_SIGNED=4 with auth signature, format types FORMAT_UINT8=0x11 FORMAT_UINT16=0x12 FORMAT_UINT32=0x14 FORMAT_SINT8=0x21 FORMAT_SINT16=0x22 FORMAT_SINT32=0x24 FORMAT_SFLOAT=0x32 FORMAT_FLOAT=0x34, addDescriptor, getDescriptor by UUID, getDescriptors list, getInstanceId, getUuid, getProperties, getPermissions, getService returns owning service, getValue/setValue deprecated API 33 — pass values directly). BluetoothGattDescriptor (constructor UUID+permissions, same permission constants as characteristic, CCCD UUID 00002902-0000-1000-8000-00805f9b34fb, static fields ENABLE_NOTIFICATION_VALUE ENABLE_INDICATION_VALUE DISABLE_NOTIFICATION_VALUE as byte arrays, getCharacteristic returns owning characteristic, getUuid, getPermissions, getValue/setValue deprecated API 33).

</reference_index>
