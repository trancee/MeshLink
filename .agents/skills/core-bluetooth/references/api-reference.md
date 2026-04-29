# Core Bluetooth — Full API Reference

<central_manager>
## CBCentralManager

Scans for, discovers, connects to, and manages peripherals.

### Initialization

```swift
// Simple
let central = CBCentralManager()

// With delegate and queue
let central = CBCentralManager(delegate: self, queue: .main)

// With options (state restoration, power alert)
let central = CBCentralManager(delegate: self, queue: nil, options: [
    CBCentralManagerOptionShowPowerAlertKey: true,
    CBCentralManagerOptionRestoreIdentifierKey: "myCentralManager"  // state restoration
])
```

### Scanning

```swift
// Scan for specific services (recommended — required in background)
central.scanForPeripherals(withServices: [CBUUID(string: "180D")])

// Scan with options
central.scanForPeripherals(withServices: nil, options: [
    CBCentralManagerScanOptionAllowDuplicatesKey: true  // default false, ignored in background
])

central.stopScan()
central.isScanning  // Bool
```

### Connecting

```swift
central.connect(peripheral, options: [
    CBConnectPeripheralOptionNotifyOnConnectionKey: true,
    CBConnectPeripheralOptionNotifyOnDisconnectionKey: true,
    CBConnectPeripheralOptionEnableAutoReconnect: true  // iOS 17+
])

central.cancelPeripheralConnection(peripheral)
```

### Retrieving Known Peripherals

```swift
// By UUID (previously discovered)
let peripherals = central.retrievePeripherals(withIdentifiers: [uuid])

// Already connected to system (by other apps or system)
let connected = central.retrieveConnectedPeripherals(withServices: [serviceUUID])
```

### Connection Events (iOS 13+)

```swift
central.registerForConnectionEvents(options: [
    .serviceUUIDs: [serviceUUID],
    .peripheralUUIDs: [peripheralUUID]
])
// Delivers via connectionEventDidOccur delegate method
```

### Feature Support

```swift
CBCentralManager.supports(.extendedScanAndConnect)  // Bool
```
</central_manager>

<central_delegate>
## CBCentralManagerDelegate

### Required

```swift
func centralManagerDidUpdateState(_ central: CBCentralManager) {
    switch central.state {
    case .poweredOn:    // ready — start scanning
    case .poweredOff:   // BT off — inform user
    case .unauthorized: // no permission
    case .unsupported:  // hardware doesn't support BLE
    case .resetting:    // temporary — wait
    case .unknown:      // wait
    @unknown default:   break
    }
}
```

### Discovery

```swift
func centralManager(_ central: CBCentralManager,
                     didDiscover peripheral: CBPeripheral,
                     advertisementData: [String: Any],
                     rssi RSSI: NSNumber) {
    // advertisementData keys: CBAdvertisementDataLocalNameKey, CBAdvertisementDataServiceUUIDsKey,
    //   CBAdvertisementDataManufacturerDataKey, CBAdvertisementDataTxPowerLevelKey,
    //   CBAdvertisementDataIsConnectable, CBAdvertisementDataServiceDataKey, etc.
    self.discoveredPeripheral = peripheral  // MUST retain!
    central.connect(peripheral)
}
```

### Connection

```swift
func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    peripheral.delegate = self
    peripheral.discoverServices([serviceUUID])
}

func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
    // handle failure
}

func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
    // error == nil means intentional disconnect
    // error != nil means unexpected disconnect — consider reconnecting
}
```

### State Restoration

```swift
func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
    // dict keys: CBCentralManagerRestoredStatePeripheralsKey, CBCentralManagerRestoredStateScanServicesKey,
    //            CBCentralManagerRestoredStateScanOptionsKey
    if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] {
        for peripheral in peripherals {
            peripheral.delegate = self
        }
    }
}
```

### Connection Events (iOS 13+)

```swift
func centralManager(_ central: CBCentralManager, connectionEventDidOccur event: CBConnectionEvent, for peripheral: CBPeripheral) {
    // event: .peerConnected, .peerDisconnected, .peerMissing
}
```
</central_delegate>

<peripheral_object>
## CBPeripheral (Remote Device)

Represents a discovered remote peripheral. Set `delegate` before calling any methods.

### Properties

```swift
peripheral.name           // String? — advertised name
peripheral.identifier     // UUID — stable identifier for this peripheral
peripheral.state          // CBPeripheralState: .disconnected, .connecting, .connected, .disconnecting
peripheral.services       // [CBService]? — discovered services (nil until discoverServices called)
peripheral.canSendWriteWithoutResponse  // Bool — flow control for .withoutResponse writes
```

### Service Discovery

```swift
peripheral.discoverServices([serviceUUID])       // nil discovers ALL services (slow, not recommended)
peripheral.discoverIncludedServices(nil, for: service)
```

### Characteristic Discovery

```swift
peripheral.discoverCharacteristics([charUUID], for: service)  // nil discovers all
peripheral.discoverDescriptors(for: characteristic)
```

### Reading Values

```swift
peripheral.readValue(for: characteristic)   // result in didUpdateValueFor
peripheral.readValue(for: descriptor)       // result in didUpdateValueFor descriptor
```

### Writing Values

```swift
// With response (acknowledged — didWriteValueFor fires)
peripheral.writeValue(data, for: characteristic, type: .withResponse)

// Without response (fire-and-forget — faster, check canSendWriteWithoutResponse)
peripheral.writeValue(data, for: characteristic, type: .withoutResponse)

// Descriptor
peripheral.writeValue(data, for: descriptor)

// Max write size
let maxLen = peripheral.maximumWriteValueLength(for: .withResponse)
```

### Notifications

```swift
peripheral.setNotifyValue(true, for: characteristic)   // subscribe
peripheral.setNotifyValue(false, for: characteristic)  // unsubscribe
// Data arrives via didUpdateValueFor — same as readValue
```

### L2CAP Channels

```swift
peripheral.openL2CAPChannel(psm)
// Result in didOpenL2CAPChannel delegate method
```

### RSSI

```swift
peripheral.readRSSI()  // result in didReadRSSI delegate method
```
</peripheral_object>

<peripheral_delegate>
## CBPeripheralDelegate

### Service/Characteristic/Descriptor Discovery

```swift
func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
    for service in peripheral.services ?? [] {
        peripheral.discoverCharacteristics(nil, for: service)
    }
}

func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
    for char in service.characteristics ?? [] {
        if char.properties.contains(.read) { peripheral.readValue(for: char) }
        if char.properties.contains(.notify) { peripheral.setNotifyValue(true, for: char) }
    }
}

func peripheral(_ peripheral: CBPeripheral, didDiscoverDescriptorsFor characteristic: CBCharacteristic, error: Error?) { }
```

### Data

```swift
func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
    guard let data = characteristic.value else { return }
    // Process data — fires for both readValue and notifications
}

func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
    // Only fires for .withResponse writes
}

func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
    // characteristic.isNotifying tells current state
}
```

### Flow Control

```swift
func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
    // canSendWriteWithoutResponse is now true — resume sending
}
```

### L2CAP

```swift
func peripheral(_ peripheral: CBPeripheral, didOpen channel: CBL2CAPChannel?, error: Error?) {
    guard let channel = channel else { return }
    // channel.inputStream, channel.outputStream, channel.psm
}
```

### Other

```swift
func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) { }
func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
    // Services changed on remote device — re-discover
    peripheral.discoverServices(nil)
}
func peripheralDidUpdateName(_ peripheral: CBPeripheral) { }
```
</peripheral_delegate>

<peripheral_manager>
## CBPeripheralManager (Local Peripheral Role)

Manages and advertises services hosted by this app. Not available for advertising on watchOS/tvOS/visionOS.

### Initialization

```swift
let peripheralManager = CBPeripheralManager(delegate: self, queue: nil, options: [
    CBPeripheralManagerOptionRestoreIdentifierKey: "myPeripheral"  // state restoration
])
```

### Adding Services

```swift
let char = CBMutableCharacteristic(
    type: charUUID,
    properties: [.read, .write, .notify],
    value: nil,            // nil = dynamic (delegate callbacks)
    permissions: [.readable, .writeable]
)
// value: non-nil = STATIC cached value (no delegate callbacks, no writes accepted)

let service = CBMutableService(type: serviceUUID, primary: true)
service.characteristics = [char]
peripheralManager.add(service)       // wait for didAddService callback
peripheralManager.remove(service)
peripheralManager.removeAllServices()
```

### Advertising

```swift
peripheralManager.startAdvertising([
    CBAdvertisementDataLocalNameKey: "MyDevice",
    CBAdvertisementDataServiceUUIDsKey: [serviceUUID]
])
// Only these two keys are honored for BLE advertising

peripheralManager.stopAdvertising()
peripheralManager.isAdvertising  // Bool
```

### Read/Write Requests

```swift
// Read
func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
    guard request.offset <= myData.count else {
        peripheral.respond(to: request, withResult: .invalidOffset)
        return
    }
    request.value = myData.subdata(in: request.offset..<myData.count)
    peripheral.respond(to: request, withResult: .success)
}

// Write — receives array, respond to first only
func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
    for request in requests {
        guard let value = request.value else { continue }
        processWrite(value, for: request.characteristic, offset: request.offset)
    }
    peripheral.respond(to: requests[0], withResult: .success)
}
```

### Notifications (updateValue)

```swift
let sent = peripheralManager.updateValue(data, for: mutableChar, onSubscribedCentrals: nil)
// nil = send to all subscribed centrals
// returns false if transmit queue is full — STOP sending

func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
    // Queue has space again — resume sending
}
```

### Subscription Tracking

```swift
func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
    // central.maximumUpdateValueLength — max bytes per update for this central
}

func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) { }
```

### L2CAP Channels

```swift
peripheralManager.publishL2CAPChannel(withEncryption: true)
// didPublishL2CAPChannel callback delivers the PSM

peripheralManager.unpublishL2CAPChannel(psm)
```

### Connection Latency

```swift
peripheralManager.setDesiredConnectionLatency(.low, for: central)
// .low (high throughput), .medium, .high (low power)
```
</peripheral_manager>

<service_types>
## Service Hierarchy Types

### CBMutableCharacteristic — Properties

| Property | Meaning |
|----------|---------|
| `.broadcast` | Can be broadcast in advertisement |
| `.read` | Readable |
| `.writeWithoutResponse` | Writable without response |
| `.write` | Writable with response |
| `.notify` | Supports notifications (no ack) |
| `.indicate` | Supports indications (with ack) |
| `.authenticatedSignedWrites` | Signed writes |
| `.extendedProperties` | Extended properties descriptor present |
| `.notifyEncryptionRequired` | Notify requires encryption |
| `.indicateEncryptionRequired` | Indicate requires encryption |

### CBAttributePermissions

| Permission | Meaning |
|------------|---------|
| `.readable` | Open read |
| `.writeable` | Open write |
| `.readEncryptionRequired` | Read requires encryption (pairing) |
| `.writeEncryptionRequired` | Write requires encryption (pairing) |

### CBCharacteristicWriteType

| Type | Meaning |
|------|---------|
| `.withResponse` | Acknowledged write — didWriteValueFor fires |
| `.withoutResponse` | Fire-and-forget — faster, check `canSendWriteWithoutResponse` |

### CBATTError.Code (common)

| Code | Meaning |
|------|---------|
| `.success` | No error |
| `.invalidHandle` | Bad attribute handle |
| `.readNotPermitted` | Read not allowed |
| `.writeNotPermitted` | Write not allowed |
| `.invalidOffset` | Offset past end of value |
| `.insufficientEncryption` | Link not encrypted |
| `.insufficientAuthentication` | Not authenticated |

### CBUUID

```swift
let uuid16 = CBUUID(string: "180D")              // Heart Rate service
let uuid128 = CBUUID(string: "E20A39F4-73F5-4BC4-A12F-17D1AD07A961")
let uuidFromData = CBUUID(data: someData)
```

### CBL2CAPChannel

```swift
channel.peer           // CBPeer
channel.inputStream    // InputStream — read from remote
channel.outputStream   // OutputStream — write to remote
channel.psm            // CBL2CAPPSM (UInt16)
// Open/close streams via Stream.open()/close() with RunLoop scheduling
```

### CBATTRequest

```swift
request.central        // CBCentral — who sent the request
request.characteristic  // CBCharacteristic — which characteristic
request.offset         // Int — byte offset for long values
request.value          // Data? — set for read response, read for write request
```

### CBCentral

```swift
central.identifier                // UUID
central.maximumUpdateValueLength  // Int — max notification payload for this central
```
</service_types>

<background_and_restoration>
## Background Execution and State Restoration

### Background Modes

| Mode | Key | Capabilities |
|------|-----|--------------|
| Central | `bluetooth-central` | Scan (service UUIDs only), connect, receive disconnect/discover events |
| Peripheral | `bluetooth-peripheral` | Advertise (limited), respond to requests, send notifications |

**Background limitations:**
- `CBCentralManagerScanOptionAllowDuplicatesKey` ignored
- Scanning requires explicit service UUIDs (no nil)
- System coalesces and batches events
- Advertising data reduced to service UUIDs only

### State Restoration

Allows system to relaunch your terminated app and restore BLE state.

```swift
// 1. Init with restore identifier
let central = CBCentralManager(delegate: self, queue: nil, options: [
    CBCentralManagerOptionRestoreIdentifierKey: "myCentral"
])

// 2. Handle restoration
func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
    // Restore peripheral references, reassign delegates
}
```

System restores: connected peripherals, services being scanned for, pending connections.

### iOS 26+ Live Activity

If your app has an instantiated `CBManager` and starts a Live Activity, it retains foreground-level BLE privileges while backgrounded (e.g., scan without service UUIDs, allow duplicates).
</background_and_restoration>
