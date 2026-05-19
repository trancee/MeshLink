# How to run the Android proof app

This guide shows you how to build the Android proof app, launch it with a
chosen mesh domain, and switch between the normal MeshLink path and the
proof-only benchmark transports.

Use it when you need:

- a second device for the MeshLink tutorial flow
- an Android proof peer for manual validation
- an Android-side benchmark host or passive proof fixture

## Prerequisites

You need:

- an Android device running API 29 or newer
- `adb` on your path
- Bluetooth enabled on the device
- this repository checked out locally

## 1. Install a debug build

```bash
./gradlew :meshlink-proof:android:meshlink-proof-android-app:installDebug
```

The debug install task passes `-g`, so the package is installed with runtime
permissions granted where the platform allows it.

If the proof app still sees no peers, follow [How to unblock MeshLink permissions on Android and iOS](../../docs/how-to/unblock-meshlink-permissions.md) before you debug transport behavior.

## 2. Launch the proof app

```bash
adb shell am start \
  -n ch.trancee.meshlink.proof.android/.MainActivity
```

The default launch uses:

- `appId = demo.meshlink`
- `powerMode = automatic`
- `benchmarkTransport = meshlink`

## 3. Override launch settings when you need a specific proof shape

The Android proof app reads these intent extras on launch:

| Extra | Type | Meaning |
|---|---|---|
| `meshlink.appId` | string | Mesh domain for discovery and trust isolation |
| `meshlink.powerMode` | string | `automatic`, `performance`, `balanced`, or `powersaver` |
| `meshlink.benchmarkPayloadBytes` | int | Payload size for benchmark-oriented auto-send |
| `meshlink.benchmarkBatteryLevel` | float | Battery level to feed into `updateBattery()` |
| `meshlink.benchmarkIsCharging` | bool | Charging state to feed into `updateBattery()` |
| `meshlink.benchmarkColdStart` | bool | Enables the cold-start benchmark path |
| `meshlink.disableAutoSend` | bool | Suppresses the automatic benchmark send |
| `meshlink.benchmarkTransport` | string | `meshlink`, `gatt`, or `gatt-notify` |

Example: start the app in a dedicated proof mesh domain with performance mode.

```bash
adb shell am start \
  -n ch.trancee.meshlink.proof.android/.MainActivity \
  --es meshlink.appId demo.meshlink.android \
  --es meshlink.powerMode performance
```

Example: start the passive proof-only GATT-notify fixture without automatic
MeshLink sends.

```bash
adb shell am start \
  -n ch.trancee.meshlink.proof.android/.MainActivity \
  --es meshlink.appId demo.meshlink \
  --es meshlink.benchmarkTransport gatt-notify \
  --ez meshlink.disableAutoSend true
```

## 4. Use the proof app in normal MeshLink mode

In the normal `meshlink` transport mode, the proof app gives you:

- Start / Stop controls
- peer visibility
- diagnostic log output
- inbound-message logging
- a Send Hello action

That is the right mode for:

- the first-exchange tutorial
- manual host-app validation
- current product-path proof work

## 5. Use the proof-only benchmark transports deliberately

The Android proof app also exposes two non-normative proof transports:

- `gatt` — the older passive Android GATT server prototype
- `gatt-notify` — the passive Android GATT client used for iPhone-hosted
  notify-side experiments

These modes are retained for investigation and benchmark work only. They are
not application-level transport choices and they are not the canonical product
integration path.

## 6. Run Android-side instrumented proof benchmarks

The Android proof app also ships instrumented benchmark coverage under
`androidTest`.

Run the attached-device suite with:

```bash
./gradlew :meshlink-proof:android:meshlink-proof-android-app:connectedDebugAndroidTest
```

Use physical devices for meaningful transport and power evidence.

## 7. Keep evidence and release posture in the right docs

Use this guide to run the Android proof app.

Use these documents for the rest:

- [MeshLink documentation map](../../docs/README.md)
- [How to unblock MeshLink permissions on Android and iOS](../../docs/how-to/unblock-meshlink-permissions.md)
- [Benchmark and validation baselines](../../benchmarks/README.md)
- [Release decision](../../specs/001-ble-mesh-sdk/release-decision.md)
