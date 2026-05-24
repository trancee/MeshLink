# How to use MeshLink from Swift

This guide shows you how to consume MeshLink from Swift through the SKIE-enhanced
Apple framework surface generated from `:meshlink`.

This guide assumes:

- you are calling MeshLink from Swift, not from shared Kotlin code
- your Xcode app links the generated `MeshLink` framework
- SKIE is enabled on the `:meshlink` module

If your Xcode app does not yet link the generated framework, start with [How to add MeshLink to your app](add-meshlink-to-your-app.md).

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

Make sure the app has a Bluetooth usage description and that the first-run Bluetooth prompt is handled before you debug discovery or delivery. If you need that checklist, use [How to unblock MeshLink permissions on Android and iOS](unblock-meshlink-permissions.md).

## 2. Build config values with the direct Swift-visible function

With SKIE enabled, the Kotlin config DSL appears as a global Swift function instead of through `MeshLinkConfigKt`.

```swift
import MeshLink

func makeMeshLinkConfig() -> MeshLinkConfig {
    meshLinkConfig { builder in
        builder.appId = "com.example.chat.ios"
    }
}
```

Two practical notes:

- keep this example minimal unless you need non-default region or power settings
- enum case names now come from SKIE-generated Swift enums, so do not rely on older bridge spellings such as `default_`

## 3. Create the runtime from Swift

Because the framework module is also named `MeshLink`, the generated Kotlin
singleton object currently appears in Swift as `MeshLink_`.

Hide that generated name behind one small app-local wrapper:

```swift
import MeshLink

func createMeshLinkRuntime(config: MeshLinkConfig) -> MeshLinkApi {
    MeshLink_.shared.create(config: config)
}

final class MeshLinkService {
    let api: MeshLinkApi

    init() {
        api = createMeshLinkRuntime(config: makeMeshLinkConfig())
    }
}
```

On iOS, you do not pass a platform context.

## 4. Start and stop with Swift async APIs

With SKIE enabled, suspend functions surface as Swift `async` APIs.

```swift
func startMesh(api: MeshLinkApi) async throws {
    let result = try await api.start()

    switch onEnum(of: result) {
    case .started:
        print("mesh.start() -> Started")
    case .alreadyRunning:
        print("mesh.start() -> AlreadyRunning")
    case .invalidState(let invalidState):
        print("mesh.start() -> InvalidState(\(invalidState.currentState))")
    }
}

func stopMesh(api: MeshLinkApi) async throws {
    let result = try await api.stop()
    print("mesh.stop() -> \(result)")
}
```

Use the same pattern for `pause`, `resume`, `send`, and `forgetPeer`.

Repeated lifecycle calls do not throw for expected idempotent cases. They return the matching `Already*` or `InvalidState` result variant instead.

## 5. Collect state, peer, diagnostic, and message streams with `for await`

`StateFlow` and `Flow` values surface as `AsyncSequence` values.

```swift
func observeState(api: MeshLinkApi) {
    Task {
        for await state in api.state {
            print("state = \(state)")
        }
    }
}
```

Use separate tasks for peer events, diagnostics, and inbound messages:

```swift
func bindFlows(api: MeshLinkApi) {
    Task {
        for await event in api.peerEvents {
            switch onEnum(of: event) {
            case .found(let found):
                print("Peer found: \(found.peerId.value)")
            case .stateChanged(let changed):
                print("Peer state changed: \(changed.peerId.value) -> \(changed.state)")
            case .lost(let lost):
                print("Peer lost: \(lost.peerId.value)")
            }
        }
    }

    Task {
        for await diagnostic in api.diagnosticEvents {
            print("diagnostic = \(diagnostic)")
        }
    }

    Task {
        for await message in api.messages {
            print("message = \(message)")
        }
    }
}
```

When the value is an `InboundMessage`, the framework also exposes `receivedAtEpochMillis`, which you can reuse for arrival ordering, logging, or UI timestamps.

## 6. Send bytes from Swift

SKIE does not remove the Kotlin `ByteArray` bridge requirement, so payloads still need a `KotlinByteArray` conversion helper.

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

Send with Swift async and match on the sealed result through `onEnum(of:)`:

```swift
func sendHello(api: MeshLinkApi, peerId: PeerId) async throws {
    let result = try await api.send(
        peerId: peerId,
        payload: "hello mesh from Swift".toKotlinByteArray(),
        priority: .normal
    )

    switch onEnum(of: result) {
    case .sent:
        print("mesh.send() -> Sent")
    case .notSent(let notSent):
        print("mesh.send() -> NotSent(\(notSent.reason))")
    }
}
```

Default arguments are still not enabled for Swift, so pass every argument explicitly unless maintainers deliberately turn SKIE default-argument interop on later.

## 7. Handle sealed results and events with `onEnum(of:)`

SKIE keeps the original Kotlin sealed classes available, but it also generates Swift enums for exhaustive switching through `onEnum(of:)`.

Use that pattern for results and events:

```swift
let peerEventEnum = onEnum(of: somePeerEvent)
let startResultEnum = onEnum(of: someStartResult)
```

That gives Swift exhaustive switching without depending on the older nested Kotlin class names everywhere.

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

## 10. What SKIE does not change by default

The current MeshLink setup uses SKIE's stable default features only.

That means:

- suspend functions are `async`
- `Flow` and `StateFlow` are `AsyncSequence`
- global Kotlin functions lose the older `FileKt`/`*Kt` entry-point names
- preview features such as SwiftUI observing and Combine bridges stay off
- default-argument interop stays off unless maintainers opt in selectively later

## Related docs

- [How to unblock MeshLink permissions on Android and iOS](unblock-meshlink-permissions.md)
- [MeshLink SDK API reference](../reference/meshlink-sdk-api.md)
- [Generated public API symbol tables](../reference/generated-public-api.md)
- [How to integrate MeshLink into a host app](integrate-meshlink-into-a-host-app.md)
- [How to structure a robust MeshLink integration](structure-a-robust-meshlink-integration.md)
- [About integrating MeshLink well](../explanation/about-integrating-meshlink.md)
