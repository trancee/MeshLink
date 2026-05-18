# How to use MeshLink from Swift

This guide shows you how to consume MeshLink from Swift, using the names and calling conventions exposed by the generated Apple framework.

This guide assumes:

- your Xcode app already integrates the MeshLink framework
- you are calling MeshLink from Swift, not from shared Kotlin code
- you want practical naming and usage examples for the generated Swift surface

For the exact Kotlin-side public contract, use the [MeshLink SDK API reference](../reference/meshlink-sdk-api.md).

## 1. Install the required iOS bridge during app startup

Before creating a MeshLink runtime, install the required crypto bridge.

```swift
import MeshLink
import SwiftUI

@main
struct ChatApp: App {
    init() {
        installMeshLinkCrypto()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```

Your `installMeshLinkCrypto()` wrapper should call `IosCryptoBridge.shared.install(...)` with CryptoKit-backed callbacks.

If you need the iPhone-hosted GATT-notify bearer, install `IosBleTransportBridge.shared.install(...)` or `installData(...)` during the same startup path.

## 2. Build config values with the Swift-visible names

The Kotlin DSL appears in Swift through the generated `MeshLinkConfigKt` entry point.

```swift
import MeshLink

func makeMeshLinkConfig() -> MeshLinkConfig {
    MeshLinkConfigKt.meshLinkConfig { builder in
        builder.appId = "com.example.chat.ios"
        builder.regulatoryRegion = RegulatoryRegion.default_
        builder.powerMode = PowerMode.Automatic.shared
    }
}
```

Two naming details matter here:

- Kotlin `object` values appear as `.shared` in Swift
- Kotlin enum entries like `DEFAULT` appear with Swift-safe names such as `default_`

## 3. Create the runtime from Swift

Use `MeshLink.shared.create(config:)` on iOS.

```swift
import MeshLink

final class MeshLinkService {
    let api: MeshLinkApi

    init() {
        api = MeshLink.shared.create(config: makeMeshLinkConfig())
    }
}
```

On iOS, you do not pass a platform context.

## 4. Start and stop with completion handlers

Suspend functions are exposed here as completion-handler APIs.

```swift
api.start { result, error in
    if let result {
        print("mesh.start() -> \(result)")
    }
    if let error {
        print("mesh.start() failed: \(error.localizedDescription)")
    }
}

api.stop { result, error in
    if let result {
        print("mesh.stop() -> \(result)")
    }
    if let error {
        print("mesh.stop() failed: \(error.localizedDescription)")
    }
}
```

Use the same pattern for `pause`, `resume`, `send`, and `forgetPeer`.

## 5. Collect flows from Swift

`StateFlow` and `Flow` values are collected with a collector object that conforms to `Kotlinx_coroutines_coreFlowCollector`.

```swift
import MeshLink
import Foundation

final class FlowCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onValue: (Any?) -> Void

    init(onValue: @escaping (Any?) -> Void) {
        self.onValue = onValue
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        onValue(value)
        completionHandler(nil)
    }
}
```

Now bind the public streams:

```swift
let stateCollector = FlowCollector { value in
    print("state = \(String(describing: value))")
}

let peerCollector = FlowCollector { value in
    print("peer event = \(String(describing: value))")
}

let diagnosticCollector = FlowCollector { value in
    print("diagnostic = \(String(describing: value))")
}

let messageCollector = FlowCollector { value in
    print("message = \(String(describing: value))")
}

api.state.collect(collector: stateCollector) { error in
    if let error {
        print("state flow ended: \(error.localizedDescription)")
    }
}

api.peerEvents.collect(collector: peerCollector) { error in
    if let error {
        print("peer flow ended: \(error.localizedDescription)")
    }
}

api.diagnosticEvents.collect(collector: diagnosticCollector) { error in
    if let error {
        print("diagnostic flow ended: \(error.localizedDescription)")
    }
}

api.messages.collect(collector: messageCollector) { error in
    if let error {
        print("message flow ended: \(error.localizedDescription)")
    }
}
```

## 6. Send bytes from Swift

The payload argument is a `KotlinByteArray`, so convert Swift `Data` or `String` into that form before sending.

```swift
import Foundation
import MeshLink

extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let kotlinBytes = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            kotlinBytes.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return kotlinBytes
    }
}

extension String {
    func toKotlinByteArray() -> KotlinByteArray {
        Data(utf8).toKotlinByteArray()
    }
}
```

Send with the generated completion-handler API:

```swift
func sendHello(to peerId: PeerId) {
    api.send(
        peerId: peerId,
        payload: "hello mesh from Swift".toKotlinByteArray(),
        priority: DeliveryPriority.normal
    ) { result, error in
        if let result {
            print("mesh.send() -> \(result)")
        }
        if let error {
            print("mesh.send() failed: \(error.localizedDescription)")
        }
    }
}
```

## 7. Handle specific event types in Swift

The generated framework exposes nested Kotlin sealed-class variants such as `PeerEvent.Found`.

```swift
let peerCollector = FlowCollector { value in
    if let found = value as? PeerEvent.Found {
        print("Peer found: \(found.peerId.value)")
    } else if let changed = value as? PeerEvent.StateChanged {
        print("Peer state changed: \(changed.peerId.value) -> \(changed.state)")
    } else if let lost = value as? PeerEvent.Lost {
        print("Peer lost: \(lost.peerId.value)")
    }
}
```

Use the same pattern for diagnostics and inbound messages.

## 8. Feed battery state if you use automatic power mode

`updateBattery(level:isCharging:)` is synchronous on the Swift side.

```swift
api.updateBattery(level: 0.42, isCharging: false)
```

Do this only if your app actually owns battery observation and wants MeshLink to react to it.

## 9. Keep the Swift surface at the app edge

A stable integration pattern is:

- install native bridges in app startup
- create one long-lived `MeshLinkApi`
- translate the generated Swift surface into app-owned view models or services
- keep your product logic working with your own app models, not raw framework objects everywhere

That keeps the generated Swift names from leaking through your entire app.

## Related docs

- [MeshLink SDK API reference](../reference/meshlink-sdk-api.md)
- [Generated public API symbol tables](../reference/generated-public-api.md)
- [How to integrate MeshLink into a host app](integrate-meshlink-into-a-host-app.md)
- [About integrating MeshLink well](../explanation/about-integrating-meshlink.md)
