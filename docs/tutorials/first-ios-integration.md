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
        let config = MeshLinkConfig {
            $0.appId = "com.example.myapp"
        }
        meshLink = MeshLink.createIos(config: config)
    }
}
```

SKIE generates Swift-native async APIs automatically — `Flow` becomes `AsyncSequence`, suspend functions become `async`.

## 4. Start the mesh

```swift
func start() {
    meshLink?.start()
}
```

## 5. Observe peer discovery

```swift
func observePeers() async {
    guard let mesh = meshLink else { return }

    for await events in mesh.peerEvents {
        for event in events {
            switch onEnum(of: event) {
            case .found(let found):
                print("Discovered: \(found.peerId)")
            case .lost(let lost):
                print("Lost: \(lost.peerId)")
            case .stateChanged(let change):
                print("\(change.peerId) → \(change.state)")
            }
        }
    }
}
```

Note the `onEnum(of:)` pattern — SKIE generates this for exhaustive switching on Kotlin sealed classes.

## 6. Send a message

```swift
func send(to peer: PeerIdHex, text: String) async {
    guard let mesh = meshLink else { return }

    let data = KotlinByteArray(size: Int32(text.utf8.count))
    for (i, byte) in text.utf8.enumerated() {
        data.set(index: Int32(i), value: Int8(bitPattern: byte))
    }

    let result = try? await mesh.send(recipient: peer, data: data)
    // Handle result
}
```

## 7. Receive messages

```swift
func observeMessages() async {
    guard let mesh = meshLink else { return }

    for await message in mesh.incomingMessages {
        let bytes = message.data
        let text = String(bytes: (0..<bytes.size).map { UInt8(bitPattern: bytes.get(index: $0)) },
                         encoding: .utf8) ?? ""
        print("From \(message.sender): \(text)")
    }
}
```

## 8. Stop cleanly

```swift
func stop() {
    meshLink?.stop()  // immediate teardown
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
