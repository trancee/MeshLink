# MeshLink iOS Sample

A SwiftUI iOS app demonstrating [MeshLink](../) — a Kotlin Multiplatform BLE
mesh messaging library.

This sample mirrors the Android sample (`sample-android/`) and shows how to:

- Initialize MeshLink with a `BleTransport`
- Start and stop the mesh
- Send unicast messages to peers
- Observe peer discovery, mesh health, and delivery status via Kotlin `Flow`

---

## Prerequisites

| Tool        | Version   |
|-------------|-----------|
| Xcode       | 15.0+     |
| macOS       | Ventura+  |
| iOS target  | 15.0+     |
| JDK         | 21        |
| Gradle      | (wrapper) |

---

## 1. Build the XCFramework

From the **repository root**, run:

```bash
./gradlew :meshlink:assembleMeshLinkCoreXCFramework
```

This produces:

```
meshlink/build/XCFrameworks/release/MeshLinkCore.xcframework
```

The XCFramework contains slices for:
- `ios-arm64` (device)
- `ios-arm64_x86_64-simulator` (simulator)

---

## 2. Create the Xcode Project

Since we don't commit `.xcodeproj` files, create one in Xcode:

1. **File → New → Project → iOS → App**
2. Product Name: `MeshLinkSample`
3. Interface: **SwiftUI**
4. Language: **Swift**
5. Save it inside `sample-ios/`
6. Delete the generated `ContentView.swift` and `MeshLinkSampleApp.swift`
7. Add the existing Swift files from `MeshLinkSample/` to the project

---

## 3. Add the MeshLinkCore Package

1. In Xcode: **File → Add Package Dependencies…**
2. Click **Add Local…** and select the **repository root** (`MeshLink/`)
3. Xcode reads `Package.swift` and offers the `MeshLinkCore` library
4. Add it to the `MeshLinkSample` target

> **Alternative:** Drag `MeshLinkCore.xcframework` directly into your project
> and link it under **Frameworks, Libraries, and Embedded Content**.

---

## 4. Configure the Project

### Deployment Target

Set the minimum deployment target to **iOS 15.0** in the project settings.

### Info.plist

The provided `Info.plist` includes:

| Key                                  | Purpose                              |
|--------------------------------------|--------------------------------------|
| `NSBluetoothAlwaysUsageDescription`  | Required for CoreBluetooth scanning  |
| `NSBluetoothPeripheralUsageDescription` | Required for BLE advertising      |
| `UIBackgroundModes`                  | `bluetooth-central`, `bluetooth-peripheral` |

### Signing

Select your development team under **Signing & Capabilities**.

---

## 5. Build & Run

- **Simulator:** The app will launch and the UI will work, but BLE is
  unavailable on the simulator. Use the demo transport for UI testing.
- **Device:** Pair your iPhone, select it as the run destination, and build.
  The demo transport is a no-op — replace it with a real `IosBleTransport`
  backed by `CBCentralManager` / `CBPeripheralManager` to test actual mesh
  communication.

---

## Architecture

```
MeshLinkSample/
├── MeshLinkSampleApp.swift      App entry point (@main)
├── ContentView.swift            SwiftUI view (health, messaging, logs)
├── MeshLinkViewModel.swift      ObservableObject wrapping MeshLink
└── Info.plist                   BLE usage descriptions
```

### ViewModel ↔ MeshLink Mapping

| Android (Kotlin)                | iOS (Swift)                                |
|---------------------------------|--------------------------------------------|
| `MeshLinkViewModel : ViewModel` | `MeshLinkViewModel : ObservableObject`    |
| `StateFlow<T>`                  | `@Published var`                           |
| `viewModelScope.launch { }`    | `Task.detached { }`                        |
| `flow.collect { }`             | `SwiftFlowCollector` (or SKIE async seq)   |
| `DemoTransport : BleTransport` | `DemoTransport : BleTransport`             |

---

## Kotlin/Native Swift Interop

### Type Mappings

| Kotlin Type              | Swift Type                            |
|--------------------------|---------------------------------------|
| `ByteArray`              | `KotlinByteArray`                     |
| `Flow<T>`                | `Kotlinx_coroutines_coreFlow`         |
| `Result<T>`              | Object with `.isSuccess()` method     |
| `PeerEvent.Discovered`   | `PeerEvent.Discovered` (nested class) |
| `MeshHealthSnapshot`     | `MeshHealthSnapshot`                  |

### Flow Collection

Kotlin `Flow` requires a collector bridge in Swift. Two options:

1. **SKIE plugin** (recommended) — Add the
   [SKIE](https://skie.touchlab.co) Gradle plugin to get native Swift
   `AsyncSequence` support:
   ```swift
   for await message in meshLink.messages {
       // handle message
   }
   ```

2. **Manual FlowCollector** — Implement `Kotlinx_coroutines_coreFlowCollector`
   as shown in `MeshLinkViewModel.swift`.

---

## Troubleshooting

| Issue                              | Solution                                    |
|------------------------------------|---------------------------------------------|
| `No such module 'MeshLinkCore'`    | Build the XCFramework first (step 1)        |
| BLE not working on simulator       | Expected — use a physical device             |
| Crash on `KotlinByteArray`         | Ensure correct `Int8` ↔ `UInt8` conversion  |
| Flow never emits                   | Check that `meshLink.start()` was called    |
| Permission dialog not appearing    | Verify `Info.plist` keys are present         |
