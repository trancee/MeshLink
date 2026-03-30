# MeshLink iOS Sample

A SwiftUI iOS app demonstrating [MeshLink](../) — a Kotlin Multiplatform BLE
mesh messaging library.

This sample mirrors the Android sample (`meshlink-sample/android/`) and shows how to:

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

## Setup

### 1. Build the XCFramework

From the **repository root**:

```bash
./gradlew :meshlink:assembleMeshLinkXCFramework
```

This generates `meshlink/build/XCFrameworks/release/MeshLink.xcframework` with slices for `ios-arm64` (device) and `ios-arm64_x86_64-simulator`.

### 2. Open and Run

```bash
open meshlink-sample/ios/MeshLinkSample.xcodeproj
```

The project is pre-configured with:
- Local SPM dependency pointing to the repo root `Package.swift`
- MeshLink framework linked in the build phase
- `Info.plist` with Bluetooth usage descriptions
- Shared scheme (MeshLinkSample)

Select the **MeshLinkSample** scheme, choose a device or simulator, and run (⌘R).

### Signing

Select your development team under **Signing & Capabilities**.

---

## Build & Run Notes

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
├── ContentView.swift            SwiftUI tab view (Chat, Mesh, Diagnostics, Settings)
├── MeshLinkViewModel.swift      ObservableObject wrapping MeshLink
├── MeshVisualizerView.swift     Mesh topology visualizer
├── DiagnosticsView.swift        Filterable diagnostic event log
├── SettingsView.swift           Config presets and health dashboard
└── Info.plist                   BLE usage descriptions
```

### ViewModel ↔ MeshLink Mapping

| Android (Kotlin)                | iOS (Swift)                                |
|---------------------------------|--------------------------------------------|
| `MeshLinkViewModel : ViewModel` | `MeshLinkViewModel : ObservableObject`     |
| `StateFlow<T>`                  | `@Published var`                           |
| `viewModelScope.launch { }`     | `Task.detached { }`                        |
| `flow.collect { }`              | `SwiftFlowCollector` (or SKIE async seq)   |
| `DemoTransport : BleTransport`  | `DemoTransport : BleTransport`             |

---

## Kotlin/Native Swift Interop

### Type Mappings

| Kotlin Type              | Swift Type                            |
|--------------------------|---------------------------------------|
| `ByteArray`              | `KotlinByteArray`                     |
| `Flow<T>`                | `Kotlinx_coroutines_coreFlow`         |
| `Result<T>`              | Object with `.isSuccess()` method     |
| `PeerEvent.Found`        | `PeerEvent.Found` (nested class)      |
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
| `No such module 'MeshLink'`    | Run `./gradlew :meshlink:assembleMeshLinkXCFramework` — the XCFramework must be built before SPM can resolve it |
| BLE not working on simulator       | Expected — use a physical device            |
| Crash on `KotlinByteArray`         | Ensure correct `Int8` ↔ `UInt8` conversion  |
| Flow never emits                   | Check that `meshLink.start()` was called    |
| Permission dialog not appearing    | Verify `Info.plist` keys are present        |
| Stale framework after code changes | Rebuild the XCFramework, then clean Xcode build folder (⇧⌘K) |
| XCFramework cache stale after rebuild | Delete `~/Library/Developer/Xcode/DerivedData/MeshLinkSample-*` and reopen project |
