# Android GATT Server — API and Types Reference

<gatt_server_api>
## BluetoothGattServer

Obtained via `BluetoothManager.openGattServer(context, callback)`. Proxy object controlling the GATT server via IPC.

### addService

```kotlin
val success: Boolean = gattServer.addService(service)
```

- Returns `true` if the request was initiated
- **Do NOT add another service before `onServiceAdded` callback fires**
- Once added, the service and its characteristics are visible to remote clients
- If services are already exposed, adding triggers a service update notification to all clients

### sendResponse — CRITICAL

```kotlin
gattServer.sendResponse(
    device,      // remote device
    requestId,   // from the callback
    status,      // BluetoothGatt.GATT_SUCCESS or error
    offset,      // offset into value for partial reads/writes
    value        // the data (nullable)
)
```

**Must be called** from these callbacks:
- `onCharacteristicReadRequest`
- `onCharacteristicWriteRequest` (when `responseNeeded == true`)
- `onDescriptorReadRequest`
- `onDescriptorWriteRequest` (when `responseNeeded == true`)
- `onExecuteWrite`

Failing to call `sendResponse` causes the remote client to time out.

### notifyCharacteristicChanged (API 33+)

```kotlin
val result: Int = gattServer.notifyCharacteristicChanged(
    device,           // target client
    characteristic,   // the changed characteristic
    confirm,          // true=indication (ack'd), false=notification (fire-and-forget)
    value             // the data to send (max 512 bytes)
)
// result: BluetoothStatusCodes.SUCCESS, ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION,
//         ERROR_PROFILE_SERVICE_NOT_BOUND, ERROR_UNKNOWN
```

- Must invoke for **every client** that has subscribed (written to CCCD)
- **Wait for `onNotificationSent`** before sending the next notification
- Max value length: 512 bytes (GATT max attribute length, BT Core Spec 6.1 Vol 3, Part F §3.2.9)
- Throws `IllegalArgumentException` if device, characteristic, value, or characteristic's service is null

**Deprecated overload** (pre-API 33): `notifyCharacteristicChanged(device, characteristic, confirm)` — reads value from characteristic object, not memory-safe.

### connect / cancelConnection

```kotlin
// Server-initiated connection
gattServer.connect(device, autoConnect = false)  // direct — first time
gattServer.connect(device, autoConnect = true)   // auto — subsequent

// Disconnect
gattServer.cancelConnection(device)
```

- `autoConnect = false`: actively connect now, fail if unavailable
- `autoConnect = true`: passively wait, connect when device is in range
- `onConnectionStateChange` fires on success/failure

### Service Management

```kotlin
gattServer.getServices()          // List<BluetoothGattService>
gattServer.getService(uuid)       // first match or null
gattServer.removeService(service) // returns true on success
gattServer.clearServices()        // removes all
```

### PHY Management

```kotlin
gattServer.readPhy(device)         // result in onPhyRead
gattServer.setPreferredPhy(device, txPhy, rxPhy, phyOptions)  // recommendation only
// PHY masks: PHY_LE_1M_MASK, PHY_LE_2M_MASK, PHY_LE_CODED_MASK, PHY_LE_HDT_MASK
// PHY options: PHY_OPTION_NO_PREFERRED, PHY_OPTION_S2, PHY_OPTION_S8
```

### Connected Devices

```kotlin
// Do NOT use gattServer.getConnectedDevices() — throws UnsupportedOperationException!
// Use BluetoothManager instead:
val devices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
```

### close

```kotlin
gattServer.close()  // call as early as possible when done
```
</gatt_server_api>

<callback_reference>
## BluetoothGattServerCallback — All 12 Callbacks

### Connection Events

```kotlin
override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
    // newState: BluetoothProfile.STATE_CONNECTED or STATE_DISCONNECTED
    // Track connected devices here
}
```

### Read Requests — MUST sendResponse

```kotlin
override fun onCharacteristicReadRequest(
    device: BluetoothDevice, requestId: Int, offset: Int,
    characteristic: BluetoothGattCharacteristic
) {
    val value = getCharacteristicValue(characteristic.uuid)
    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
        value.copyOfRange(offset, value.size))
}

override fun onDescriptorReadRequest(
    device: BluetoothDevice, requestId: Int, offset: Int,
    descriptor: BluetoothGattDescriptor
) {
    val value = getDescriptorValue(device, descriptor)
    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
}
```

### Write Requests — MUST sendResponse if responseNeeded

```kotlin
override fun onCharacteristicWriteRequest(
    device: BluetoothDevice, requestId: Int,
    characteristic: BluetoothGattCharacteristic,
    preparedWrite: Boolean, responseNeeded: Boolean,
    offset: Int, value: ByteArray
) {
    // Process the value
    storeCharacteristicValue(characteristic.uuid, value)

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
    // Check if client is enabling/disabling notifications
    if (descriptor.uuid == CCCD_UUID) {
        when {
            value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ->
                subscribedDevices.add(device)
            value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) ->
                subscribedDevices.add(device)
            value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) ->
                subscribedDevices.remove(device)
        }
    }
    if (responseNeeded) {
        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
    }
}
```

### Prepared Writes (Long Writes)

```kotlin
override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
    // execute=true: commit all queued writes
    // execute=false: cancel all queued writes
    if (execute) {
        commitPreparedWrites(device)
    } else {
        cancelPreparedWrites(device)
    }
    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
}
```

### Service Registration

```kotlin
override fun onServiceAdded(status: Int, service: BluetoothGattService) {
    if (status == BluetoothGatt.GATT_SUCCESS) {
        // Safe to add the next service now
    }
}
```

### Notification Flow Control

```kotlin
override fun onNotificationSent(device: BluetoothDevice, status: Int) {
    if (status == BluetoothGatt.GATT_SUCCESS) {
        // Now safe to send the next notification to this device
        sendNextPendingNotification(device)
    }
}
```

### MTU Negotiation

```kotlin
override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
    // Client negotiated new MTU
    // Effective payload = mtu - 3 (ATT header overhead)
    deviceMtuMap[device] = mtu
}
```

### PHY Events

```kotlin
override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
    // PHY_LE_1M, PHY_LE_2M, PHY_LE_CODED, PHY_LE_HDT
}

override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
    // Triggered by setPreferredPhy or remote device PHY change
}
```

### Subrate (API 36.1+)

```kotlin
override fun onSubrateChange(device: BluetoothDevice, subrateMode: Int, status: Int) {
    // SUBRATE_MODE_OFF, SUBRATE_MODE_LOW, SUBRATE_MODE_BALANCED,
    // SUBRATE_MODE_HIGH, SUBRATE_MODE_SYSTEM_UPDATE, SUBRATE_MODE_NOT_UPDATED
}
```
</callback_reference>

<service_construction>
## Building the Service Hierarchy

### BluetoothGattService

```kotlin
// Primary service (standalone, discoverable)
val service = BluetoothGattService(MY_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

// Secondary service (included by a primary — rarely used directly)
val secondary = BluetoothGattService(OTHER_UUID, BluetoothGattService.SERVICE_TYPE_SECONDARY)
service.addService(secondary)  // include secondary in primary
```

- `getInstanceId()` distinguishes multiple services with the same UUID (e.g. multiple battery services)

### BluetoothGattCharacteristic

```kotlin
val characteristic = BluetoothGattCharacteristic(
    CHAR_UUID,
    // Properties — what operations the characteristic supports
    BluetoothGattCharacteristic.PROPERTY_READ or
        BluetoothGattCharacteristic.PROPERTY_WRITE or
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
    // Permissions — security requirements
    BluetoothGattCharacteristic.PERMISSION_READ or
        BluetoothGattCharacteristic.PERMISSION_WRITE
)
service.addCharacteristic(characteristic)
```

### Property Flags (what the characteristic can do)

| Constant | Value | Meaning |
|----------|-------|---------|
| `PROPERTY_BROADCAST` | 0x01 | Broadcastable |
| `PROPERTY_READ` | 0x02 | Readable |
| `PROPERTY_WRITE_NO_RESPONSE` | 0x04 | Writable without response |
| `PROPERTY_WRITE` | 0x08 | Writable with response |
| `PROPERTY_NOTIFY` | 0x10 | Supports notifications (no ack) |
| `PROPERTY_INDICATE` | 0x20 | Supports indications (with ack) |
| `PROPERTY_SIGNED_WRITE` | 0x40 | Signed write |
| `PROPERTY_EXTENDED_PROPS` | 0x80 | Extended properties |

### Permission Flags (security requirements)

| Constant | Value | Security |
|----------|-------|----------|
| `PERMISSION_READ` | 0x01 | Open read |
| `PERMISSION_READ_ENCRYPTED` | 0x02 | Encrypted read |
| `PERMISSION_READ_ENCRYPTED_MITM` | 0x04 | Encrypted + MITM-protected read |
| `PERMISSION_WRITE` | 0x10 | Open write |
| `PERMISSION_WRITE_ENCRYPTED` | 0x20 | Encrypted write |
| `PERMISSION_WRITE_ENCRYPTED_MITM` | 0x40 | Encrypted + MITM-protected write |
| `PERMISSION_WRITE_SIGNED` | 0x80 | Signed write |
| `PERMISSION_WRITE_SIGNED_MITM` | 0x100 | Signed + MITM-protected write |

### Write Types

| Constant | Value | Meaning |
|----------|-------|---------|
| `WRITE_TYPE_DEFAULT` | 2 | Write with acknowledgement |
| `WRITE_TYPE_NO_RESPONSE` | 1 | Write without response (faster) |
| `WRITE_TYPE_SIGNED` | 4 | Write with authentication signature |

### Value Format Types (for parsing typed values)

| Constant | Value | Size |
|----------|-------|------|
| `FORMAT_UINT8` | 0x11 | 1 byte unsigned |
| `FORMAT_UINT16` | 0x12 | 2 bytes unsigned |
| `FORMAT_UINT32` | 0x14 | 4 bytes unsigned |
| `FORMAT_SINT8` | 0x21 | 1 byte signed |
| `FORMAT_SINT16` | 0x22 | 2 bytes signed |
| `FORMAT_SINT32` | 0x24 | 4 bytes signed |
| `FORMAT_SFLOAT` | 0x32 | 2 bytes float |
| `FORMAT_FLOAT` | 0x34 | 4 bytes float |

### BluetoothGattDescriptor

```kotlin
// CCCD — Client Characteristic Configuration Descriptor
// Required for characteristics with PROPERTY_NOTIFY or PROPERTY_INDICATE
val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

val cccd = BluetoothGattDescriptor(
    CCCD_UUID,
    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
)
characteristic.addDescriptor(cccd)
```

### CCCD Static Values

```kotlin
BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE   // client wants notifications
BluetoothGattDescriptor.ENABLE_INDICATION_VALUE      // client wants indications
BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE   // client unsubscribing
```

Compare these in `onDescriptorWriteRequest` using `contentEquals()` to track subscriptions.

### Complete Service Example

```kotlin
fun createCustomService(): BluetoothGattService {
    val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

    // Read-only characteristic
    val readChar = BluetoothGattCharacteristic(
        READ_CHAR_UUID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
    )

    // Write characteristic with notification
    val writeNotifyChar = BluetoothGattCharacteristic(
        WRITE_NOTIFY_CHAR_UUID,
        BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    )
    writeNotifyChar.addDescriptor(BluetoothGattDescriptor(
        CCCD_UUID,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
    ))

    service.addCharacteristic(readChar)
    service.addCharacteristic(writeNotifyChar)
    return service
}
```
</service_construction>
