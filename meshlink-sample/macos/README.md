# MeshLink macOS Sample

A SwiftUI reference app demonstrating the MeshLink BLE mesh messaging library on macOS.

## Screens

| Screen | Description |
|--------|-------------|
| **Chat** | Start/stop mesh, send messages, view event log |
| **Mesh** | Visualize network topology with signal strength |
| **Settings** | Config presets, MTU slider, health dashboard |

## Prerequisites

- Xcode 15+
- macOS 11+

## Setup

### 1. Build the XCFramework

```bash
./gradlew :meshlink:assembleMeshLinkXCFramework
```

The framework is generated at:
```
meshlink/build/XCFrameworks/release/MeshLink.xcframework
```

### 2. Open in Xcode

Create a new macOS App project in Xcode, then:

1. Add the MeshLink SPM dependency (File → Add Package Dependencies)
2. Point to the local repository root
3. Copy the Swift files from `meshlink-sample/macos/MeshLinkSample/` into your project

### 3. Run

Select a macOS target and run (⌘R).

> **Note:** The sample uses a no-op `DemoTransport`. On macOS, CoreBluetooth
> is available for real BLE communication — implement `BleTransport` using
> `CBCentralManager` and `CBPeripheralManager` for actual mesh networking.
