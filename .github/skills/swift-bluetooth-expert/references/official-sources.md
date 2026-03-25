# Official sources for `swift-bluetooth-expert`

Use this file as a quick map to the official documentation that should anchor answers.

## CoreBluetooth framework

- `https://developer.apple.com/documentation/corebluetooth/`
  - Use as the API reference entry point for all CoreBluetooth classes, protocols, and types.
  - Key classes: `CBCentralManager`, `CBPeripheral`, `CBPeripheralManager`, `CBService`, `CBCharacteristic`, `CBUUID`.

- `https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/AboutCoreBluetooth/Introduction.html`
  - Use as the conceptual programming guide. Covers the full lifecycle: central/peripheral roles, data structures, background processing, best practices.
  - Note: The code examples use Objective-C, but the concepts and API patterns map directly to Swift.

## Central role

- `https://developer.apple.com/documentation/corebluetooth/cbcentralmanager`
  - Use for scanning, discovering, connecting, and managing peripherals.
  - Important: `connect(_:options:)` does not time out. Implement your own timeout logic.
  - Connection options: `CBConnectPeripheralOptionNotifyOnConnectionKey`, `CBConnectPeripheralOptionNotifyOnDisconnectionKey`, `CBConnectPeripheralOptionNotifyOnNotificationKey` for foreground-only apps that want system alerts.

- `https://developer.apple.com/documentation/corebluetooth/cbcentralmanagerdelegate`
  - Use for delegate callbacks during scanning, connection, disconnection, and state changes.
  - `centralManagerDidUpdateState(_:)` is required and always the first callback after init.

- `https://developer.apple.com/documentation/corebluetooth/cbperipheral`
  - Use for service/characteristic discovery, reading, writing, and subscribing after connection.
  - Important: You must keep a strong reference to `CBPeripheral` objects. CoreBluetooth does not retain them.

- `https://developer.apple.com/documentation/corebluetooth/cbperipheraldelegate`
  - Use for all peripheral interaction callbacks: service discovery, characteristic discovery, value updates, write confirmations.

## Peripheral role

- `https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager`
  - Use for publishing services, advertising, and responding to central requests.
  - Advertising keys: Only `CBAdvertisementDataLocalNameKey` and `CBAdvertisementDataServiceUUIDsKey` are supported.
  - Foreground advertising data limit: 28 bytes (plus 10 bytes for local name in scan response).

- `https://developer.apple.com/documentation/corebluetooth/cbperipheralmanagerdelegate`
  - Use for peripheral manager state changes, service publishing, advertising callbacks, read/write/subscription handling.

## Data model

- `https://developer.apple.com/documentation/corebluetooth/cbservice`
  - Represents a remote peripheral's service. Contains `characteristics` array after discovery.

- `https://developer.apple.com/documentation/corebluetooth/cbcharacteristic`
  - Represents a characteristic of a remote service. Value is `Data?`. Properties indicate read/write/notify support.

- `https://developer.apple.com/documentation/corebluetooth/cbuuid`
  - Bluetooth UUIDs. Use `CBUUID(string:)` with 16-bit (`"180D"`) or 128-bit UUID strings.

- `https://developer.apple.com/documentation/corebluetooth/cbmutableservice`
  - Mutable version for creating local services (peripheral role).

- `https://developer.apple.com/documentation/corebluetooth/cbmutablecharacteristic`
  - Mutable version for creating local characteristics. Set properties, value, and permissions at init.

## Sample code

- `https://developer.apple.com/documentation/corebluetooth/transferring-data-between-bluetooth-low-energy-devices`
  - Apple's official sample: central and peripheral roles, data transfer via characteristic updates, flow control, RSSI-based proximity check.
  - Shows the complete central lifecycle: scan → discover → connect → subscribe → receive data.
  - Shows the complete peripheral lifecycle: setup service → advertise → send data via characteristic updates.

## Background execution

- Background execution modes and best practices are covered in the Core Bluetooth Programming Guide:
  `https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html`
  - Two background modes: `bluetooth-central` and `bluetooth-peripheral` in `UIBackgroundModes`.
  - Central in background: `CBCentralManagerScanOptionAllowDuplicatesKey` is ignored. Scan intervals increase. Discoveries are coalesced.
  - Peripheral in background: `CBAdvertisementDataLocalNameKey` is ignored. Service UUIDs go to overflow area. Advertising frequency decreases.
  - 10-second processing window when woken in background.

- State preservation and restoration:
  `https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html#//apple_ref/doc/uid/TP40013257-CH7-SW10`
  - Use `CBCentralManagerOptionRestoreIdentifierKey` / `CBPeripheralManagerOptionRestoreIdentifierKey`.
  - System preserves: scanned services, connecting/connected peripherals, subscribed characteristics.
  - On relaunch: check `UIApplication.LaunchOptionsKey.bluetoothCentrals` / `.bluetoothPeripherals`, then implement `willRestoreState` delegate.

## Best practices

- `https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/BestPracticesForInteractingWithARemotePeripheralDevice/BestPracticesForInteractingWithARemotePeripheralDevice.html`
  - Minimize radio usage: scan only when needed, use specific service UUIDs, stop scanning after finding the target.
  - `CBCentralManagerScanOptionAllowDuplicatesKey` should only be used when truly necessary (e.g., RSSI-based proximity).
  - Discover only the services and characteristics you need.
  - Subscribe to characteristic values instead of polling.
  - Disconnect when done.
  - Reconnection order: `retrievePeripherals(withIdentifiers:)` → `retrieveConnectedPeripherals(withServices:)` → scan.

- `https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/BestPracticesForSettingUpYourIOSDeviceAsAPeripheral/BestPracticesForSettingUpYourIOSDeviceAsAPeripheral.html`
  - Advertising limits: 28 bytes foreground, local name + service UUIDs only.
  - Stop advertising when not needed.
  - Configure characteristics with `.notify` property to allow subscriptions.
  - Use encryption-required properties/permissions for sensitive data (triggers pairing).

## Info.plist

- `https://developer.apple.com/documentation/BundleResources/Information-Property-List/NSBluetoothAlwaysUsageDescription`
  - Required on iOS 13+. App crashes without it.
- `https://developer.apple.com/documentation/BundleResources/Information-Property-List/NSBluetoothPeripheralUsageDescription`
  - Legacy key for iOS 12 and earlier.

## L2CAP channels

- `https://developer.apple.com/documentation/corebluetooth/cbl2capchannel`
  - L2CAP Connection-Oriented Channel for stream-based data transfer. Provides `InputStream`/`OutputStream`.
- `https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/publishl2capchannel(withencryption:)`
  - Peripheral side: publish a channel. System assigns the PSM via delegate callback.
- `https://developer.apple.com/documentation/corebluetooth/cbperipheral/openl2capchannel(_:)`
  - Central side: open a channel using the PSM learned from a GATT characteristic or other discovery mechanism.

## Bluetooth SIG assigned numbers

- `https://www.bluetooth.com/specifications/assigned-numbers/`
  - Use when the user needs to look up standard 16-bit UUIDs for GATT services and characteristics.
  - Common services: `0x180D` (Heart Rate), `0x180A` (Device Information), `0x180F` (Battery Service), `0x1800` (Generic Access), `0x1801` (Generic Attribute).
  - Common characteristics: `0x2A37` (Heart Rate Measurement), `0x2A29` (Manufacturer Name String), `0x2A19` (Battery Level).

## How to apply these sources

- Start with the CoreBluetooth API reference for class/method questions.
- Layer in the Programming Guide for conceptual understanding, background behavior, and best practices.
- Use the Apple sample code as a reference for complete central/peripheral lifecycle implementation.
- If a recommendation depends on a specific iOS version, say so explicitly.
- When the user is debugging BLE issues, include exact doc links that match the topic.

## High-value factual anchors

Use these when the user needs a precise, doc-grounded answer rather than a general explanation:

- **Peripheral retention**
  - `CBCentralManager` does not retain discovered `CBPeripheral` objects. If the app does not keep a strong reference, the peripheral is deallocated and the connection silently fails with no error callback.
  - This is the single most common CoreBluetooth bug.

- **Connection timeout**
  - `CBCentralManager.connect(_:options:)` does not time out. The system will keep trying to connect indefinitely until `cancelPeripheralConnection(_:)` is called.
  - Apps must implement their own timeout logic if they need one.

- **Advertising data limits**
  - Peripheral advertising data is limited to 28 bytes in the foreground (plus 10 bytes for local name in scan response).
  - Only two dictionary keys are supported: `CBAdvertisementDataLocalNameKey` and `CBAdvertisementDataServiceUUIDsKey`.
  - In the background, local name is ignored and all service UUIDs go to overflow.

- **Background scanning behavior**
  - `CBCentralManagerScanOptionAllowDuplicatesKey` is ignored in the background.
  - Multiple advertising packets from the same peripheral are coalesced into a single discovery event.
  - Scan intervals increase in the background.

- **State restoration identifier**
  - State preservation requires a restoration identifier string passed at manager initialization.
  - Only managers with restoration identifiers have their state preserved across app termination.
  - The identifier must be the same string each time the manager is recreated.

- **Info.plist crash**
  - Apps linked on iOS 13+ crash at runtime if `NSBluetoothAlwaysUsageDescription` is missing from Info.plist.
  - There is no graceful fallback — the crash happens immediately when CoreBluetooth APIs are accessed.

- **respond(to:withResult:) rule**
  - `peripheralManager(_:didReceiveReadRequest:)` and `peripheralManager(_:didReceiveWriteRequests:)` both require exactly one call to `respond(to:withResult:)`.
  - For write requests (which come as an array), pass the first request object to `respond(to:withResult:)`.

- **iOS 26+ Live Activity background scanning**
  - In iOS 26+, if an app has an instantiated `CBManager` and starts a Live Activity, it gets foreground-equivalent BLE privileges while backgrounded.
  - This means scanning without service UUIDs and scanning with duplicates are both allowed in the background.

- **Do not subclass**
  - Apple explicitly states: do not subclass any CoreBluetooth classes. Overriding them results in undefined behavior.
  - Use delegation and composition instead.

- **iPad apps on macOS**
  - Core Bluetooth background execution modes are not supported in iPad apps running on macOS.

- **L2CAP channels**
  - CoreBluetooth supports L2CAP Connection-Oriented Channels for stream-based data transfer, bypassing GATT overhead.
  - Available since iOS 11. Use `publishL2CAPChannel(withEncryption:)` (peripheral) and `openL2CAPChannel(_:)` (central).
  - The PSM value is assigned by the system, not chosen by the developer.

- **Write-without-response flow control**
  - `CBPeripheral.canSendWriteWithoutResponse` indicates whether the write buffer has space.
  - `peripheralIsReady(toSendWriteWithoutResponse:)` fires once when buffer space becomes available after being full.
  - Without this flow control, write-without-response data is silently dropped when the buffer is full.

- **MTU negotiation**
  - The default BLE ATT MTU is 23 bytes (20 bytes usable payload after 3-byte ATT header).
  - iOS auto-negotiates a larger MTU with capable peripherals (often 185+ bytes on modern devices).
  - Call `peripheral.maximumWriteValueLength(for: .withoutResponse)` to get the actual negotiated payload size.

- **Simulator limitation**
  - CoreBluetooth does not work in the iOS Simulator. All BLE testing requires physical iOS devices.
  - Use protocol-based abstractions and mock objects for unit testing without hardware.

- **CBError.peerRemovedPairingInformation**
  - When a peripheral removes its bonding info, iOS reports this error on the next connection attempt.
  - The only fix is to have the user forget the device in iOS Settings > Bluetooth, then re-pair.
  - This is a common support issue with consumer BLE devices that get factory-reset.

- **Strict concurrency pattern**
  - With Swift 6 strict concurrency, mark BLE manager classes `@MainActor` and init `CBCentralManager` with `queue: .main`.
  - Delegate methods require `nonisolated` keyword. Use `MainActor.assumeIsolated { }` inside (safe because `queue: .main` guarantees main-thread execution).
  - Avoid `Task { @MainActor in }` for state updates — it introduces async hops that break ordering guarantees.
