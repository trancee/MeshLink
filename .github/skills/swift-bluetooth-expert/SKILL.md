---
name: swift-bluetooth-expert
description: 'Swift CoreBluetooth/BLE on iOS/iPadOS: scanning, connecting, GATT read/write/notify, advertising, background execution, state restoration, multi-peripheral auto-reconnect, transmit-queue backpressure, ATT request handling, L2CAP channels, MTU negotiation, CBError/CBATTError recovery, SwiftUI @Observable/@MainActor, async-await/nonisolated delegates, protocol-based BLE mocking. Trigger on CBCentralManager, CBPeripheral, CBPeripheralManager, CBL2CAPChannel, CBUUID, AllowDuplicatesKey, advertisement limits, updateValue, canSendWriteWithoutResponse, maximumWriteValueLength, RSSI, willRestoreState, didReceiveWriteRequests, background modes, NSBluetoothAlwaysUsageDescription, or BLE testing. Not for Android Bluetooth, Web Bluetooth, classic BR/EDR, or non-Bluetooth networking.'
---

# Swift Bluetooth Expert

Help the user build robust, idiomatic Swift apps that communicate with Bluetooth Low Energy devices using Apple's CoreBluetooth framework on iOS/iPadOS. Give direct guidance, working code examples in modern Swift, and doc-backed recommendations rather than abstract theory.

## Start by locating the real problem

CoreBluetooth questions blend several layers. Identify which layer is driving the task before answering:

- Central role (scanning, discovering, connecting, reading/writing characteristics)
- Peripheral role (advertising, building services, responding to requests)
- Data model (services, characteristics, descriptors, UUIDs)
- Background execution and state preservation
- SwiftUI integration and architecture
- Modern Swift concurrency (wrapping delegate APIs with async/await or actors)
- Info.plist configuration and permissions
- Testing and simulation

If version or environment details matter, ask early. The most useful missing details are usually:

- iOS deployment target and Xcode version
- Whether the app needs background BLE (and which mode)
- Central vs peripheral role (or both)
- The specific BLE device or service UUIDs involved
- The error message or unexpected behavior

## Working style

- Prefer a direct answer first, then show code.
- Keep examples minimal but functional. Use Swift's modern features: `@Observable`, `async/await`, actors, and SwiftUI where appropriate.
- All CoreBluetooth code is delegate-based at the API level. When the user's codebase uses modern patterns, show how to bridge delegates into async streams or Combine publishers. When they're using classic delegate patterns, work within that style.
- Explain *why* patterns matter: battery life, state machine correctness, background constraints, or thread safety.
- Separate CoreBluetooth API guidance from SwiftUI integration and architecture choices. A BLE manager class should work regardless of UI framework.
- If advice depends on an iOS version, say so explicitly.

## Domain guidance

### Central role

The central role is the most common starting point. A `CBCentralManager` scans for peripherals, connects, discovers services and characteristics, and reads/writes/subscribes to values.

Key patterns to get right:

- **Initialization**: Create `CBCentralManager` with a delegate. The first callback is always `centralManagerDidUpdateState(_:)` — scanning must wait until `.poweredOn`.
- **Scanning**: Call `scanForPeripherals(withServices:options:)` with specific service UUIDs when possible. Passing `nil` discovers everything but wastes battery.
- **Retain the peripheral**: Store a strong reference to discovered `CBPeripheral` objects. CoreBluetooth does not retain them — if you don't, they get deallocated and the connection silently fails.
- **Connection**: `connect(_:options:)` does not time out. The system retries indefinitely until `cancelPeripheralConnection(_:)`. Implement your own timeout.
- **Service/characteristic discovery**: After connecting, call `discoverServices(_:)`, then `discoverCharacteristics(_:for:)`. Pass specific UUIDs to avoid unnecessary radio usage.
- **Reading values**: `readValue(for:)` triggers `peripheral(_:didUpdateValueFor:error:)`. Parse `characteristic.value` (it's `Data?`).
- **Writing values**: Use `.withResponse` when you need confirmation, `.withoutResponse` for streaming data. Check `peripheral(_:didWriteValueFor:error:)` for `.withResponse` writes.
- **Write-without-response flow control**: Check `peripheral.canSendWriteWithoutResponse` before writing. If `false`, wait for `peripheralIsReady(toSendWriteWithoutResponse:)`. Without this, writes are silently dropped when the buffer is full.
- **MTU awareness**: Call `peripheral.maximumWriteValueLength(for:)` to learn the maximum payload size. The default ATT MTU is 23 bytes (20 usable); iOS auto-negotiates a larger MTU with capable peripherals, often 185+ bytes. Chunk data accordingly.
- **Subscriptions**: `setNotifyValue(true, for:)` enables notifications. Value updates arrive through the same `didUpdateValueFor` delegate method as reads.
- **Disconnection**: Always call `cancelPeripheralConnection(_:)` when done. Clean up subscriptions first with `setNotifyValue(false, for:)`.

### Peripheral role

Setting up a local device as a BLE peripheral to advertise services and respond to connected centrals.

Key patterns:

- **Initialization**: Create `CBPeripheralManager` with a delegate. Wait for `.poweredOn` before adding services.
- **Building the service tree**: Create `CBMutableCharacteristic` (with properties, value, permissions) → add to `CBMutableService` → call `add(_:)` on the peripheral manager.
- **Characteristic value**: If you set a non-nil value at init time, it's cached and served directly. Set `nil` if you want dynamic values delivered via delegate callbacks.
- **Advertising**: `startAdvertising(_:)` accepts only `CBAdvertisementDataLocalNameKey` and `CBAdvertisementDataServiceUUIDsKey`. Advertising data is limited to 28 bytes (plus 10 bytes for local name in scan response).
- **Responding to requests**: Implement `peripheralManager(_:didReceiveReadRequest:)` and `peripheralManager(_:didReceiveWriteRequests:)`. Always call `respond(to:withResult:)` exactly once per callback.
- **Sending notifications**: Use `updateValue(_:for:onSubscribedCentrals:)`. If it returns `false`, the transmit queue is full — wait for `peripheralManagerIsReady(toUpdateSubscribers:)`.

### UUIDs

- Standard Bluetooth SIG services/characteristics use 16-bit UUIDs (e.g., `"180D"` for Heart Rate). CoreBluetooth expands these to 128-bit internally.
- Custom services need full 128-bit UUIDs. Generate them with `uuidgen` in Terminal.
- Create `CBUUID` objects with `CBUUID(string:)`.
- Common predefined UUIDs: `"180D"` (Heart Rate), `"180A"` (Device Information), `"180F"` (Battery), `"2A37"` (Heart Rate Measurement), `"2A29"` (Manufacturer Name).

### Background execution

Background BLE on iOS has strict constraints that fundamentally change scanning and advertising behavior.

- **Background modes**: Add `bluetooth-central` and/or `bluetooth-peripheral` to `UIBackgroundModes` in Info.plist.
- **Central in background**: Scanning still works but `CBCentralManagerScanOptionAllowDuplicatesKey` is ignored (discoveries are coalesced). Scan intervals increase. The system wakes your app for delegate callbacks.
- **Peripheral in background**: `CBAdvertisementDataLocalNameKey` is ignored. Service UUIDs go to the "overflow" area, discoverable only by devices explicitly scanning for those UUIDs. Advertising frequency decreases.
- **10-second rule**: When woken in the background, complete your work quickly. The system may throttle or kill apps that take too long.
- **iOS 26+**: If your app has a `CBManager` and starts a Live Activity, it gets foreground-equivalent scanning privileges while backgrounded (no duplicate coalescing, no scan interval increase).

### State preservation and restoration

For long-running BLE tasks that survive app termination (e.g., a door lock that reconnects when the user comes home):

- **Opt in**: Pass `CBCentralManagerOptionRestoreIdentifierKey` (or `CBPeripheralManagerOptionRestoreIdentifierKey`) when creating the manager.
- **Handle relaunch**: In `application(_:didFinishLaunchingWithOptions:)`, check `UIApplication.LaunchOptionsKey.bluetoothCentrals` / `.bluetoothPeripherals` for restoration identifiers.
- **Restore state**: Implement `centralManager(_:willRestoreState:)` or `peripheralManager(_:willRestoreState:)`. The dictionary contains preserved peripherals, services, and scan info.
- The system preserves: services being scanned for, connected/connecting peripherals, subscribed characteristics.

### Info.plist requirements

- **Required**: `NSBluetoothAlwaysUsageDescription` — your app crashes without this on iOS 13+.
- **Legacy**: `NSBluetoothPeripheralUsageDescription` for iOS 12 and earlier.
- **Background modes**: `UIBackgroundModes` with `bluetooth-central` and/or `bluetooth-peripheral` if you need background BLE.

### SwiftUI integration

CoreBluetooth is delegate-based, so it needs a bridge layer. The recommended architecture:

- Create a `BluetoothManager` class (or actor) that owns the `CBCentralManager`/`CBPeripheralManager` and implements the delegate protocols.
- Mark it `@Observable` (iOS 17+) or `ObservableObject` (iOS 14+) so SwiftUI views can react to state changes.
- Expose BLE state as published properties: connection state, discovered peripherals, characteristic values.
- Inject it via `.environment()` or `@Environment` for clean access across the view hierarchy.
- Keep UI logic out of the BLE manager. Views should read state and call methods like `scan()`, `connect(to:)`, `disconnect()`.

### Modern Swift concurrency

The CoreBluetooth API is callback/delegate-based, but modern Swift patterns can make it cleaner:

- **AsyncStream**: Wrap delegate callbacks (like discovered peripherals or characteristic value updates) in `AsyncStream` so callers can `for await` over them.
- **Checked continuations**: Use `withCheckedThrowingContinuation` for one-shot operations like connect, service discovery, or a single read.
- **Actors**: Consider an actor-based BLE manager for thread safety, though note that CoreBluetooth requires delegate calls on a specific dispatch queue — use `nonisolated` methods for the delegate conformance and relay state into the actor.
- **Combine bridge**: For codebases already using Combine, `PassthroughSubject` / `CurrentValueSubject` make natural bridges for characteristic value streams.

Be pragmatic about concurrency wrappers. They add value for complex multi-step flows (scan → connect → discover → subscribe), but for simple use cases the delegate pattern is fine and easier to debug.

**@MainActor + nonisolated pattern (Swift 6 strict concurrency)**:

The recommended pattern for SwiftUI apps is to annotate the BLE manager class with `@MainActor` and create the `CBCentralManager` with `queue: .main`. This makes all state mutations safe for SwiftUI observation. Delegate protocol conformance requires `nonisolated` since CoreBluetooth calls delegates from its dispatch queue:

```swift
@MainActor
final class BLEManager: NSObject, @Observable {
    var peripherals: [CBPeripheral] = []
    private var centralManager: CBCentralManager!

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: .main)
    }
}

extension BLEManager: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            // Safe: we set queue: .main, so this IS the main actor
            // Update state here
        }
    }
}
```

The `MainActor.assumeIsolated` call is safe because we specified `queue: .main` at init, so all delegate callbacks are already on the main thread. This avoids `Task { @MainActor in }` which introduces async hops and ordering issues.

For high-throughput apps where delegate callbacks on the main queue would cause UI jank, create the manager on a dedicated serial `DispatchQueue` instead and dispatch state updates to `@MainActor` explicitly.

### Reconnection strategies

Three ways to reconnect, in order of preference:

1. `retrievePeripherals(withIdentifiers:)` — reconnect to a previously known device by saved UUID. Fastest.
2. `retrieveConnectedPeripherals(withServices:)` — find a device already connected to the system (possibly by another app).
3. `scanForPeripherals(withServices:)` — full scan. Slowest, uses the most battery.

Always save the peripheral's `identifier` (a `UUID`) to `UserDefaults` or similar storage after first discovery so you can use option 1 on next launch.

For multi-peripheral scenarios, use a tiered approach: try tier 1 first with a short timeout (~5s), escalate to tier 2, then tier 3. This avoids wasting time on one strategy when another would succeed faster.

When multiple peripherals disconnect simultaneously (e.g., user walks out of range), use exponential backoff with jitter to stagger reconnection attempts and avoid thundering-herd collisions on the BLE radio.

### L2CAP channels

For high-throughput data transfer that exceeds what GATT characteristics can handle efficiently, CoreBluetooth supports L2CAP Connection-Oriented Channels (CoC):

- **When to use**: Firmware updates, file transfers, audio streaming, or any scenario where you need stream-oriented data transfer rather than attribute-level reads/writes. L2CAP avoids the GATT overhead (attribute headers, queuing per characteristic) and gives you a raw byte stream.
- **Peripheral setup**: Call `peripheralManager.publishL2CAPChannel(withEncryption:)`. The system assigns a PSM (Protocol/Service Multiplexer) via `peripheralManager(_:didPublishL2CAPChannel:error:)`. Advertise this PSM value to centrals (e.g., in a GATT characteristic).
- **Central setup**: After discovering the PSM, call `peripheral.openL2CAPChannel(_:)`. Handle the channel in `peripheral(_:didOpen:error:)`.
- **Data transfer**: The `CBL2CAPChannel` provides input/output `InputStream`/`OutputStream` objects. Use standard stream delegate patterns or wrap in async sequences.
- **Encryption**: Pass `true` to `publishL2CAPChannel(withEncryption:)` to require an encrypted link (triggers pairing if needed).

### Error handling

CoreBluetooth communicates errors through two domains:

- **CBError** (framework-level): Connection failures, invalid parameters, peer disconnects. Common cases:
  - `.connectionFailed` — generic connection failure
  - `.peerRemovedPairingInformation` — the peripheral removed its bond; the user needs to forget the device in Settings and re-pair
  - `.connectionLimitReached` — too many concurrent connections (device-dependent, typically 7–10)
  - `.encryptionTimedOut` — pairing/encryption negotiation timed out

- **CBATTError** (GATT-level): Errors from read/write/notify operations. Common cases:
  - `.invalidHandle`, `.readNotPermitted`, `.writeNotPermitted` — check characteristic properties before attempting operations
  - `.insufficientEncryption`, `.insufficientAuthentication` — the characteristic requires a bonded/encrypted connection; trigger pairing first
  - `.attributeNotFound` — service/characteristic UUID mismatch between your code and the peripheral firmware

Recovery patterns:
- For `.peerRemovedPairingInformation`: prompt the user to forget the device in iOS Settings, then re-scan and re-pair.
- For transient connection failures: retry with exponential backoff (1s → 2s → 4s, cap at 30s, add ±20% jitter).
- For `.connectionLimitReached`: queue connections and connect to the next device only after disconnecting from another.
- Always check the `error` parameter in every delegate callback — never assume success.

### Testing CoreBluetooth

CoreBluetooth does not work in the iOS Simulator. All BLE testing requires physical devices. Plan your testing strategy around this constraint:

- **Protocol-based abstraction**: Define protocols like `BluetoothScanning` and `BluetoothPeripheral` that mirror the CoreBluetooth APIs you use. Your BLE manager depends on these protocols, and in tests you inject mock implementations. This is the most effective way to unit-test BLE logic without hardware.
- **State machine testing**: If your BLE manager uses an explicit state machine (and it should for multi-peripheral scenarios), test the state transitions independently of CoreBluetooth. Feed mock events and verify state changes.
- **Two-device testing**: For peripheral-role apps, use a second iOS device running a central-role test harness (or vice versa). Apple's "Core Bluetooth Transfer" sample project is a useful starting point.
- **nRF Connect / LightBlue**: Third-party BLE scanner apps are invaluable for debugging. They let you inspect advertising data, read/write characteristics, and verify your peripheral's GATT structure.
- **Background testing**: Test background BLE by sending the app to background and verifying delegate callbacks still fire. Use Xcode's "Simulate Background Fetch" or just press Home and wait.

### Best practices

- **Minimize radio usage**: Scan only when needed. Stop scanning after finding your device. Use specific service UUIDs.
- **Discover only what you need**: Pass specific UUID arrays to `discoverServices(_:)` and `discoverCharacteristics(_:for:)`.
- **Subscribe over polling**: Use notifications (`setNotifyValue(true, for:)`) for values that change often instead of repeated `readValue(for:)`.
- **Disconnect when done**: Cancel subscriptions and then the connection when you have the data you need.
- **Handle state transitions**: Always check `centralManagerDidUpdateState(_:)` / `peripheralManagerDidUpdateState(_:)`. BLE can be turned off, unauthorized, or unsupported at any time.
- **Advertising limits**: Only `CBAdvertisementDataLocalNameKey` and `CBAdvertisementDataServiceUUIDsKey` are supported. Total foreground advertising data is limited to 28 bytes.

## Response pattern

Use this shape when it helps:

1. Direct diagnosis or recommendation
2. Working Swift code
3. Why this pattern matters (battery, correctness, background constraints, thread safety)
4. iOS version or deployment target notes
5. Relevant official documentation links

Do not force a rigid template for small requests, but keep answers scannable.

## When reviewing or refactoring BLE code

- Check for missing strong references to `CBPeripheral` (the #1 silent failure).
- Check for scanning without service UUIDs (battery drain).
- Check for missing `centralManagerDidUpdateState` / state checks before scanning.
- Check that delegates are set on peripherals after connection.
- Look for blocking the main thread with synchronous BLE operations.
- Verify proper cleanup: unsubscribe before disconnect, stop scanning when not needed.
- For background apps: verify Info.plist background modes and state restoration setup.
- For SwiftUI apps: ensure BLE manager is properly injected and uses @Observable or ObservableObject.
- For Swift 6 strict concurrency: verify `@MainActor` annotation on the manager class, `nonisolated` on delegate methods, and `MainActor.assumeIsolated` (not `Task { @MainActor in }`) when using `queue: .main`.
- Check error handling: every delegate callback with an `error` parameter should handle the error case.
- For write-without-response: check for `canSendWriteWithoutResponse` / `peripheralIsReady(toSendWriteWithoutResponse:)` flow control.
- Check that data writes respect `maximumWriteValueLength(for:)` and chunk if needed.

## Examples

**Example 1**

User: "How do I scan for a BLE heart rate monitor and read the heart rate value in Swift?"

Good response shape: CBCentralManager setup, scanning with heart rate service UUID, connection, service/characteristic discovery, subscribing to heart rate measurement characteristic, parsing the heart rate data format, and links to CoreBluetooth docs.

**Example 2**

User: "I'm building a SwiftUI app that connects to a BLE device. How should I architect the Bluetooth layer?"

Good response shape: @Observable BluetoothManager class, delegate bridging, published state for UI, environment injection pattern, separation of BLE logic from views, and example SwiftUI view consuming the state.

**Example 3**

User: "My BLE peripheral connection keeps silently failing. I discover it fine but connect never completes."

Good response shape: Diagnose the most likely cause (not retaining `CBPeripheral`), explain why CoreBluetooth doesn't retain discovered peripherals, show the fix (strong property reference), and mention the connection-doesn't-timeout behavior.

**Example 4**

User: "I need my app to reconnect to a BLE door lock automatically when the user comes home, even if the app was terminated."

Good response shape: Background execution mode setup, state preservation and restoration with restoration identifiers, pending connection request that survives termination, app relaunch handling, and the full lifecycle from `willRestoreState` to reconnection.

**Example 5**

User: "How do I wrap CoreBluetooth's delegate callbacks in async/await?"

Good response shape: AsyncStream for continuous events (discovered peripherals, notifications), withCheckedContinuation for one-shot operations (connect, read), the @MainActor + nonisolated pattern for strict concurrency, and practical advice on when wrappers add value vs. unnecessary complexity.

**Example 6**

User: "How do I test my BLE manager without physical devices?"

Good response shape: Explain that CoreBluetooth doesn't work in the Simulator, show a protocol-based abstraction (e.g., `BluetoothScanning` protocol), demonstrate a mock implementation for unit tests, and mention real-device tools (nRF Connect, LightBlue) for integration testing.

## Reference file

Read `references/official-sources.md` when you need the curated official doc map or want source links to include in the answer.
