# Tutorial: Your First iOS Integration

> **Time:** ~15 minutes  
> **What you'll build:** An iOS app that discovers a nearby MeshLink peer and sends a message.  
> **Prerequisites:** Xcode 14.3+, a physical iOS device (BLE required), Swift knowledge.

---

## 1. Add the SPM package

In Xcode: File → Add Package Dependencies → enter the repo URL:

```
https://github.com/nicegram/MeshLink
```

Select the `MeshLink` library product. Alternatively, in `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/nicegram/MeshLink", from: "0.1.0")
]
```

## 2. Add Info.plist keys

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>MeshLink uses Bluetooth to discover and communicate with nearby peers.</string>
<key>UIBackgroundModes</key>
<array>
    <string>bluetooth-central</string>
    <string>bluetooth-peripheral</string>
</array>
```

## 3. Create a MeshLink instance

```swift
import MeshLink

class MeshController: ObservableObject {
    private var meshLink: MeshLink?

    func initialize() {
        let config = MeshLinkConfigCompanion.shared.smallPayloadLowLatency(
            appId: "com.example.myapp"
        )
        meshLink = MeshLink.companion.createIos(config: config)
    }
}
```

SKIE generates Swift-native async APIs automatically — `Flow` becomes `AsyncSequence`, suspend functions become `async`.

## 4. Start the mesh

```swift
func start() async {
    try? await meshLink?.start()
}
```

## 5. Observe peer discovery

```swift
func observePeers() async {
    guard let mesh = meshLink else { return }

    for await event in mesh.peers {
        switch onEnum(of: event) {
        case .found(let found):
            print("Discovered: \(found.id.toHex())")
        case .lost(let lost):
            print("Lost: \(lost.id.toHex())")
        case .stateChanged(let change):
            print("\(change.id.toHex()) → \(change.state)")
        }
    }
}
```

Note the `onEnum(of:)` pattern — SKIE generates this for exhaustive switching on Kotlin sealed classes.

## 6. Send a message

```swift
func send(to peerId: KotlinByteArray, text: String) async {
    guard let mesh = meshLink else { return }

    let data = text.utf8ToKotlinByteArray()
    try? await mesh.send(recipient: peerId, payload: data, priority: .normal)
}
```

## 7. Receive messages

```swift
func observeMessages() async {
    guard let mesh = meshLink else { return }

    for await message in mesh.messages {
        let text = message.payload.toSwiftString()
        print("From \(message.senderId.toHex()): \(text)")
    }
}
```

## 8. Stop cleanly

```swift
func stop() async {
    try? await meshLink?.stop(timeout: .zero)
}
```

## What you've accomplished

You now have an iOS app that:
- Advertises via CoreBluetooth (with State Preservation/Restoration for background operation)
- Discovers nearby MeshLink peers
- Sends E2E encrypted messages transparently
- Operates without internet, servers, or user accounts
- Survives app suspension via CoreBluetooth state restoration

## Next steps

- [Your First Android Integration](first-android-integration.md) — get cross-platform messaging working
- [How to Handle Key Rotation](../how-to/handle-key-rotation.md) — respond to TOFU key changes
- [Explanation: Why L2CAP-First](../explanation/why-l2cap-first.md) — understand the connection model
