---
name: core-bluetooth
description: Apple Core Bluetooth framework reference for BLE on iOS/macOS/tvOS/visionOS/watchOS. Central role (CBCentralManager scanning, connecting, state restoration; CBPeripheral service/characteristic discovery, read/write, notifications, L2CAP channels). Peripheral role (CBPeripheralManager advertising, service hosting, ATT request handling, L2CAP publishing). Service hierarchy (CBService, CBCharacteristic with properties/permissions, CBDescriptor, CBUUID). Info.plist keys, background modes, state management (.poweredOn gate). Programming guide topics (static vs dynamic values, background processing, state preservation/restoration, best practices for central and peripheral). Do not subclass any Core Bluetooth class. Use when implementing BLE scanning, GATT client/server, notifications, L2CAP, state restoration, background BLE, or any Core Bluetooth question.
---

<essential_principles>

**Core Bluetooth** — Apple's framework for BLE and BR/EDR Classic communication. Central role scans and connects; peripheral role advertises and hosts services.

Available: iOS 5+, macOS 10.10+, tvOS 9+, visionOS 1+, watchOS 4+ (advertising not available on watchOS/tvOS/visionOS).

**Never subclass** any Core Bluetooth class — results in undefined behavior.

### Info.plist Requirements

```xml
<!-- Required iOS 13+ — app crashes without it -->
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app uses Bluetooth to communicate with nearby devices.</string>

<!-- Background modes (if needed) -->
<key>UIBackgroundModes</key>
<array>
    <string>bluetooth-central</string>     <!-- scan/connect in background -->
    <string>bluetooth-peripheral</string>  <!-- advertise in background -->
</array>
```

### State Management — CRITICAL

```swift
// Must wait for .poweredOn before calling ANY method
func centralManagerDidUpdateState(_ central: CBCentralManager) {
    guard central.state == .poweredOn else { return }
    // Now safe to scan, connect, etc.
}
```

`CBManagerState`: `.unknown`, `.resetting`, `.unsupported`, `.unauthorized`, `.poweredOff`, `.poweredOn`

### Central Role — End-to-End

```swift
// 1. Create central manager
let central = CBCentralManager(delegate: self, queue: nil)

// 2. Scan (after .poweredOn)
central.scanForPeripherals(withServices: [serviceUUID])

// 3. Discover → connect
func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, ...) {
    self.peripheral = peripheral  // retain!
    central.connect(peripheral)
}

// 4. Connected → discover services
func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    peripheral.delegate = self
    peripheral.discoverServices([serviceUUID])
}

// 5. Services → characteristics
func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
    for service in peripheral.services ?? [] {
        peripheral.discoverCharacteristics([charUUID], for: service)
    }
}

// 6. Read/write/subscribe
func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, ...) {
    for char in service.characteristics ?? [] {
        peripheral.readValue(for: char)
        peripheral.setNotifyValue(true, for: char)  // subscribe
    }
}

// 7. Receive data
func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, ...) {
    let data = characteristic.value  // the data
}
```

### Peripheral Role — End-to-End

```swift
// 1. Create peripheral manager
let peripheralManager = CBPeripheralManager(delegate: self, queue: nil)

// 2. Build and add service (after .poweredOn)
let char = CBMutableCharacteristic(
    type: charUUID,
    properties: [.read, .write, .notify],
    value: nil,  // nil = dynamic value via delegate
    permissions: [.readable, .writeable]
)
let service = CBMutableService(type: serviceUUID, primary: true)
service.characteristics = [char]
peripheralManager.add(service)

// 3. Advertise
peripheralManager.startAdvertising([
    CBAdvertisementDataLocalNameKey: "MyDevice",
    CBAdvertisementDataServiceUUIDsKey: [serviceUUID]
])

// 4. Handle read requests
func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
    request.value = myData.subdata(in: request.offset..<myData.count)
    peripheral.respond(to: request, withResult: .success)
}

// 5. Handle write requests
func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
    for request in requests {
        // process request.value
    }
    peripheral.respond(to: requests[0], withResult: .success)
}

// 6. Send notifications
let didSend = peripheralManager.updateValue(data, for: char, onSubscribedCentrals: nil)
// if false → queue full, wait for peripheralManagerIsReadyToUpdateSubscribers
```

### Key Rules

1. **Retain `CBPeripheral`** after discovery — framework does not retain it for you
2. **Wait for `.poweredOn`** before calling any manager methods
3. **`updateValue` returns `false`** when the transmit queue is full — stop sending and wait for `peripheralManagerIsReadyToUpdateSubscribers` callback
4. **Respond to EVERY read/write request** with `respond(to:withResult:)` — timeout if you don't
5. **Background**: scanning limited to service UUIDs only, no duplicate reporting, system coalesces events
6. **iOS 26+**: Live Activity keeps foreground-level BLE privileges in background

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| CBCentralManager full API (init, scan, connect, cancel, retrieve, state restoration, connection events, Feature), CBCentralManagerDelegate (all callbacks), CBPeripheral full API (discover services/characteristics/descriptors, read/write values, notify, write types, max write length, L2CAP, RSSI, state), CBPeripheralDelegate (all callbacks), CBPeripheralManager full API (init, add/remove services, advertise, respond, updateValue, L2CAP, connection latency), CBPeripheralManagerDelegate (all callbacks), service hierarchy construction (CBMutableService, CBMutableCharacteristic properties/permissions, CBMutableDescriptor), CBUUID, CBATTRequest, CBL2CAPChannel, CBAttributePermissions, CBCharacteristicProperties, CBCharacteristicWriteType, state restoration, background execution | `references/api-reference.md` |
| Conceptual overview (GATT data hierarchy, object mapping central vs peripheral side), central role step-by-step walkthrough (Objective-C code examples for init/scan/connect/discover/read/write/subscribe), peripheral role step-by-step (build service tree, static vs dynamic characteristic values, publish/advertise/respond to reads and writes/send notifications), iOS background processing (foreground-only behavior, peripheral connection options for alerts, bluetooth-central and bluetooth-peripheral background modes, behavioral differences foreground vs background, state preservation and restoration 4-step process with code), best practices central (minimize radio usage, stop scanning early, avoid AllowDuplicatesKey, discover only needed services/characteristics, subscribe vs poll, disconnect when done, three reconnection strategies: retrievePeripherals by identifier / retrieveConnectedPeripherals by service / scan again), best practices peripheral (advertising data limits 28 bytes + 10 scan response + overflow area, background advertising restrictions, let user control advertising, always support notifications, protect sensitive data with readEncryptionRequired/writeEncryptionRequired for automatic pairing) | `references/programming-guide.md` |

</routing>

<reference_index>

**api-reference.md** — CBCentralManager (init() convenience, init(delegate:queue:) convenience, init(delegate:queue:options:) designated with CBCentralManagerOptionShowPowerAlertKey/RestoreIdentifierKey, scanForPeripherals(withServices:options:) with CBCentralManagerScanOptionAllowDuplicatesKey/SolicitedServiceUUIDsKey, stopScan(), isScanning, connect(_:options:) with CBConnectPeripheralOptionNotifyOnConnectionKey/DisconnectionKey/NotificationKey/EnableAutoReconnect/RequiresANCS, cancelPeripheralConnection, retrieveConnectedPeripherals(withServices:) returns already-connected peripherals matching services, retrievePeripherals(withIdentifiers:) by UUID, registerForConnectionEvents(options:) with CBConnectionEventMatchingOption serviceUUIDs/peripheralUUIDs, supports(_:) for CBCentralManager.Feature, delegate property). CBCentralManagerDelegate (centralManagerDidUpdateState required — check .poweredOn, centralManager:didDiscover:advertisementData:RSSI delivers CBPeripheral/ad data dict/RSSI number, didConnect/didFailToConnect with optional Error, didDisconnectPeripheral with optional Error, willRestoreState with dict containing CBCentralManagerRestoredStatePeripheralsKey/ScanServicesKey/ScanOptionsKey, connectionEventDidOccur with CBConnectionEvent peerConnected/peerDisconnected/peerMissing and CBPeripheral). CBPeripheral (name, identifier UUID, delegate, services list, state CBPeripheralState .disconnected/.connecting/.connected/.disconnecting, canSendWriteWithoutResponse, discoverServices optional [CBUUID] — nil discovers all, discoverIncludedServices for CBService, discoverCharacteristics optional [CBUUID] for CBService, discoverDescriptors for CBCharacteristic, readValue for CBCharacteristic, readValue for CBDescriptor, writeValue Data for CBCharacteristic with type CBCharacteristicWriteType .withResponse/.withoutResponse, writeValue Data for CBDescriptor, setNotifyValue Bool for CBCharacteristic, maximumWriteValueLength(for:) returns Int for write type, readRSSI, openL2CAPChannel CBL2CAPPSM). CBPeripheralDelegate (didDiscoverServices, didDiscoverIncludedServicesFor, didDiscoverCharacteristicsFor, didDiscoverDescriptorsFor, didUpdateValueFor characteristic with optional Error, didUpdateValueFor descriptor, didWriteValueFor characteristic, didWriteValueFor descriptor, didUpdateNotificationStateFor, didReadRSSI NSNumber, isReadyToSendWriteWithoutResponse, didModifyServices with invalidated [CBService], didOpenL2CAPChannel with optional Error, peripheralDidUpdateName). CBPeripheralManager (init() convenience, init(delegate:queue:) convenience, init(delegate:queue:options:) with CBPeripheralManagerOptionShowPowerAlertKey/RestoreIdentifierKey, delegate, add CBMutableService, remove CBMutableService, removeAllServices, startAdvertising dict with CBAdvertisementDataLocalNameKey/CBAdvertisementDataServiceUUIDsKey — only these two keys honored for BLE, stopAdvertising, isAdvertising, respond(to:CBATTRequest withResult:CBATTError.Code) — .success for success, updateValue Data for CBMutableCharacteristic onSubscribedCentrals optional [CBCentral] — nil sends to all — returns false when queue full, publishL2CAPChannel(withEncryption:Bool), unpublishL2CAPChannel CBL2CAPPSM, setDesiredConnectionLatency CBPeripheralManagerConnectionLatency .low/.medium/.high for CBCentral). CBPeripheralManagerDelegate (peripheralManagerDidUpdateState required, didAddService with optional Error, didStartAdvertising with optional Error, didReceiveRead CBATTRequest — must call respond(to:withResult:), didReceiveWrite [CBATTRequest] — respond to first request only — value of first determines response for all, centralDidSubscribeToCharacteristic with CBCentral and CBCharacteristic, centralDidUnsubscribeFromCharacteristic, peripheralManagerIsReadyToUpdateSubscribers — resume sending after updateValue returned false, willRestoreState with CBPeripheralManagerRestoredStateServicesKey/AdvertisementDataKey, didPublishL2CAPChannel with CBL2CAPPSM, didUnpublishL2CAPChannel, didOpenL2CAPChannel with CBL2CAPChannel). Service hierarchy (CBService: uuid CBUUID, isPrimary Bool, includedServices optional, characteristics optional; CBMutableService: init(type:primary:), characteristics settable, includedServices settable; CBCharacteristic: uuid, value optional Data, properties CBCharacteristicProperties bitmask .broadcast/.read/.writeWithoutResponse/.write/.notify/.indicate/.authenticatedSignedWrites/.extendedProperties/.notifyEncryptionRequired/.indicateEncryptionRequired, descriptors optional, isNotifying Bool, service parent; CBMutableCharacteristic: init(type:properties:value:permissions:) — value must be nil for dynamic characteristics that use delegate callbacks — non-nil means static cached value, permissions CBAttributePermissions bitmask .readable/.writeable/.readEncryptionRequired/.writeEncryptionRequired, subscribedCentrals; CBDescriptor: uuid, value; CBMutableDescriptor: init(type:value:)). CBUUID (init(string:) from "180D" 16-bit or full 128-bit UUID string, init(data:), predefined service/characteristic UUIDs). CBATTRequest (central CBCentral, characteristic CBCharacteristic, offset Int, value optional Data — set for read responses). CBL2CAPChannel (peer CBPeer, inputStream InputStream, outputStream OutputStream, psm CBL2CAPPSM). CBPeer (identifier UUID). CBCentral (identifier UUID, maximumUpdateValueLength Int). State restoration (CBCentralManagerOptionRestoreIdentifierKey/CBPeripheralManagerOptionRestoreIdentifierKey in init options, willRestoreState callback receives dict with previously-scanned services, peripherals, and advertising data so app can resume after system terminates it in background). Background execution (bluetooth-central mode: scan limited to service UUIDs, no duplicate reporting, system coalesces, wakes app on discover/connect/disconnect; bluetooth-peripheral mode: advertise in background — advertising limited, services still accessible but disabled when suspended without background mode; iOS 26+ Live Activity: keeps foreground BLE privileges while backgrounded if CBManager instantiated and Live Activity started).


**programming-guide.md** — Core Bluetooth Programming Guide from Apple Documentation Archive. Conceptual overview (GATT data hierarchy: peripheral contains services which contain characteristics which contain descriptors; central-side immutable CBService/CBCharacteristic vs peripheral-side CBMutableService/CBMutableCharacteristic). Central role step-by-step in Objective-C (initWithDelegate:queue:options: creates CBCentralManager, centralManagerDidUpdateState: required callback must check poweredOn, scanForPeripheralsWithServices:options: with nil discovers all or pass CBUUID array, centralManager:didDiscoverPeripheral:advertisementData:RSSI: must retain peripheral in property, connectPeripheral:options:, centralManager:didConnectPeripheral: sets peripheral.delegate then calls discoverServices: with specific UUIDs, peripheral:didDiscoverServices: iterates services and calls discoverCharacteristics:forService: with specific UUIDs, peripheral:didDiscoverCharacteristicsForService: then readValueForCharacteristic: or setNotifyValue:YES, peripheral:didUpdateValueForCharacteristic:error: delivers data for both reads and notifications, writeValue:forCharacteristic:type: with CBCharacteristicWriteWithResponse or CBCharacteristicWriteWithoutResponse, check characteristic.properties before operating). Peripheral role step-by-step (initWithDelegate:queue:options: creates CBPeripheralManager, peripheralManagerDidUpdateState: required, CBUUID UUIDWithString: for 16-bit SIG or 128-bit custom UUIDs, generate with uuidgen, CBMutableCharacteristic initWithType:properties:value:permissions: where value:nil means DYNAMIC with delegate callbacks and value:non-nil means STATIC cached permanently — critical distinction, CBMutableService initWithType:primary: with characteristics array, addService: then wait for peripheralManager:didAddService:error:, startAdvertising: with CBAdvertisementDataLocalNameKey and CBAdvertisementDataServiceUUIDsKey only two keys honored, peripheralManager:didReceiveReadRequest: must check offset and respond, peripheralManager:didReceiveWriteRequests: receives array and respondToRequest:withResult: to FIRST request only, updateValue:forCharacteristic:onSubscribedCentrals: returns BOOL false when queue full then wait for peripheralManagerIsReadyToUpdateSubscribers:). Background processing (foreground-only apps: central cannot scan, peripheral advertising disabled, events queued until foreground; CBConnectPeripheralOptionNotifyOnConnectionKey/DisconnectionKey/NotificationKey for system alerts while suspended; UIBackgroundModes bluetooth-central enables scan/connect/events in background, bluetooth-peripheral enables advertise/respond; background limitations: AllowDuplicatesKey ignored, scan requires service UUIDs, discovery events batched, advertising reduced to overflow area with no local name; state preservation and restoration iOS 7+: step 1 provide CBCentralManagerOptionRestoreIdentifierKey or CBPeripheralManagerOptionRestoreIdentifierKey in init options using same identifier every time, step 2 reinstantiate managers in application:didFinishLaunchingWithOptions: using UIApplicationLaunchOptionsBluetoothCentralsKey/BluetoothPeripheralsKey, step 3 implement centralManager:willRestoreState: or peripheralManager:willRestoreState: to reassign delegates and process restored peripherals/services using CBCentralManagerRestoredStatePeripheralsKey/ScanServicesKey/ScanOptionsKey or CBPeripheralManagerRestoredStateServicesKey/AdvertisementDataKey, step 4 update centralManagerDidUpdateState: to avoid duplicating work already restored). Best practices central (stop scanning immediately after finding target, never AllowDuplicatesKey unless per-packet RSSI needed, discover only needed services and characteristics via specific CBUUID arrays, subscribe setNotifyValue:YES instead of polling readValueForCharacteristic: for changing data, disconnect cancelPeripheralConnection: when done, three reconnection strategies in order: retrievePeripheralsWithIdentifiers: by saved UUID for direct connect without scanning, retrieveConnectedPeripheralsWithServices: for system-connected peripherals, scan as last resort). Best practices peripheral (advertising limited to CBAdvertisementDataLocalNameKey + CBAdvertisementDataServiceUUIDsKey, foreground budget 28 bytes initial + 10 bytes scan response for local name + 2 bytes header per type, overflow area for UUIDs that don't fit — only found by iOS explicit scan, background: no local name advertised all UUIDs in overflow, stop advertising once connected, provide UI for user-controlled advertising, always add CBCharacteristicPropertyNotify to characteristics that change, protect sensitive data with CBAttributePermissionsReadEncryptionRequired/WriteEncryptionRequired which triggers automatic pairing).

</reference_index>
