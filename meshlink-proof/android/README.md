# How to run the Android proof app

This guide shows you how to build the Android proof app, launch it with a
chosen mesh domain, and switch between the normal MeshLink path and the
proof-only benchmark transports.

Use it when you need:

- a second device for the MeshLink tutorial flow
- an Android proof peer for manual validation
- an Android proof fixture for passive transport or benchmark work

## Choose the right validation surface first

| If you need to... | Use... |
|---|---|
| evaluate the supported product-like Android and iOS experience | [the reference app guide](../../docs/how-to/evaluate-meshlink-with-the-reference-app.md) |
| run an Android physical proof peer or proof fixture | this guide |
| inspect retained throughput, latency, or cold-start posture | [Benchmark and validation baselines](../../benchmarks/README.md) |

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

If the proof app still sees no peers, use
[How to unblock MeshLink permissions on Android and iOS](../../docs/how-to/unblock-meshlink-permissions.md)
before you debug transport behavior.

## 2. Launch the proof app

```bash
adb shell am start \
  -n ch.trancee.meshlink.proof.android/.MainActivity
```

The default launch uses:

- `appId = demo.meshlink`
- `powerMode = automatic`
- `benchmarkTransport = meshlink`

If you are using this app as the receiving proof peer for
[Your first MeshLink exchange](../../docs/tutorials/your-first-meshlink-exchange.md),
keep `benchmarkTransport` at `meshlink` and override only the `appId` so it
matches the tutorial host app.

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

Example: start the Android proof app as the receiving proof peer for the SDK tutorial.

```bash
adb shell am start \
  -n ch.trancee.meshlink.proof.android/.MainActivity \
  --es meshlink.appId com.example.meshlink.tutorial
```

Example: start the proof-only GATT-notify setup without automatic MeshLink
sends.

```bash
adb shell am start \
  -n ch.trancee.meshlink.proof.android/.MainActivity \
  --es meshlink.appId demo.meshlink \
  --es meshlink.benchmarkTransport gatt-notify \
  --ez meshlink.disableAutoSend true
```

## 4. Choose the transport mode deliberately

The Android proof app can start in three transport modes.
Use them for different claims.

| Value | Meaning | Use it when you need to... | Important boundary |
|---|---|---|---|
| `meshlink` | normal MeshLink runtime | run the tutorial proof peer, manual product-path proof, or diagnostic sanity checks | closest to supported runtime behavior |
| `gatt` | older Android GATT prototype | keep the Android device in the passive GATT-server fixture role for retained investigation work | relaunch with `meshlink.disableAutoSend=true`; proof-only and non-normative |
| `gatt-notify` | Android-side GATT-notify prototype | run notify-side transport investigation against the matching iPhone proof setup | proof-only and non-normative |

In normal `meshlink` mode, the proof app gives you:

- Start / Stop controls
- peer visibility
- diagnostic log output
- inbound-message logging
- a Send Hello action

If you are validating supported user-visible behavior, stay in `meshlink` mode
or move to the reference app. Use `gatt` and `gatt-notify` only when you are
explicitly doing retained transport investigation or benchmark-oriented work.

### Contributor note

The Android proof host now keeps the activity thin on purpose.
Launch parsing, permission rules, benchmark framing, and runtime ownership live
behind narrower proof-harness helpers so proof-only behavior does not accrete in
one giant activity file.

## 5. Run Android-side instrumented proof benchmarks

The Android proof app also ships instrumented benchmark coverage under
`androidTest`.

Run the attached-device suite with:

```bash
./gradlew :meshlink-proof:android:meshlink-proof-android-app:connectedDebugAndroidTest
```

Use physical devices for meaningful transport and power evidence.

## 6. Keep evidence and release posture in the right docs

Use this guide to run the Android proof app.

For retained evidence and release posture, use:

- [MeshLink documentation map](../../docs/README.md)
- [About proof validation surfaces](../../docs/explanation/about-proof-validation-surfaces.md)
- [How to unblock MeshLink permissions on Android and iOS](../../docs/how-to/unblock-meshlink-permissions.md)
- [Benchmark and validation baselines](../../benchmarks/README.md)
- [Release decision](../../specs/001-ble-mesh-sdk/release-decision.md)
