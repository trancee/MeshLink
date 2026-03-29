# MeshLink macOS Sample

A SwiftUI reference app demonstrating the MeshLink BLE mesh messaging library on macOS.

## Screens

| Screen | Description |
|--------|-------------|
| **Chat** | Start/stop mesh, send messages, view event log |
| **Mesh** | Visualize network topology with signal strength |
| **Diagnostics** | Filterable diagnostic event log with severity color-coding |
| **Settings** | Config presets, MTU slider, health dashboard |

## Prerequisites

- Xcode 15+
- macOS 13+ (Ventura)
- Java 21+ (for building the XCFramework via Gradle)

## Setup

### 1. Build the XCFramework

From the repository root:

```bash
./gradlew :meshlink:assembleMeshLinkXCFramework
```

This generates `meshlink/build/XCFrameworks/release/MeshLink.xcframework`.

### 2. Open and Run

```bash
open meshlink-sample/macos/MeshLinkSample.xcodeproj
```

The project is pre-configured with:
- Local SPM dependency pointing to the repo root `Package.swift`
- MeshLink framework linked in the build phase
- Bluetooth sandbox entitlement
- Shared scheme (MeshLinkSample)

Select the **MeshLinkSample** scheme and run (⌘R).

## DemoTransport vs BleTransport

The sample uses a no-op `DemoTransport` by default. On macOS, CoreBluetooth
is available for real BLE communication — implement `BleTransport` using
`CBCentralManager` and `CBPeripheralManager` for actual mesh networking.

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Bluetooth doesn't work | Open **System Settings → Privacy & Security → Bluetooth** and allow MeshLinkSample |
| `No such module 'MeshLink'` | Run `./gradlew :meshlink:assembleMeshLinkXCFramework` — the XCFramework must be built before SPM can resolve it |
| Flow never emits | Check that `meshLink.start()` was called |
| Stale framework after code changes | Rebuild the XCFramework, then clean Xcode build folder (⇧⌘K) |
| XCFramework cache stale after rebuild | Delete `~/Library/Developer/Xcode/DerivedData/MeshLinkSample-*` and reopen project |
