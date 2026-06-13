# How to run the Android proof app

This guide shows you how to build the Android proof app, launch it with a
chosen mesh domain, and switch between the normal MeshLink path and the
proof-only benchmark transports.

Use it when you need:

- a second device for the MeshLink tutorial flow
- an Android proof peer for manual validation
- an Android proof fixture for passive transport or benchmark work

## Support floor and crypto note

- Current app floor: Android API 26+
- Android BLE/L2CAP is supported on API 26+, but X25519/XDH and
  ChaCha20-Poly1305 are only officially guaranteed by Android on later APIs.
- MeshLink now has an in-repo fallback for X25519/XDH,
  ChaCha20-Poly1305, and Ed25519 on Android when the runtime probe cannot rely
  on platform support.
- Attached Android 9 / SDK 28 hardware has already validated that fallback
  selection path; retained API 26 runtime proof still needs a bootable emulator
  environment or attached API 26-class hardware.

## Choose the right validation surface first

| If you need to... | Use... |
|---|---|
| evaluate the supported product-like Android and iOS experience | [the reference app guide](../../docs/how-to/evaluate-meshlink-with-the-reference-app.md) |
| run an Android physical proof peer or proof fixture | this guide |
| inspect retained throughput, latency, or cold-start posture | [Benchmark and validation baselines](../../benchmarks/README.md) |

## Prerequisites

You need:

- an Android device running API 26 or newer
- `adb` on your path
- Bluetooth enabled on the device
- this repository checked out locally

## 1. Install a debug build

```bash
./gradlew :meshlink-proof:android:installDebug
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

For the Android direct-proof runner on this host, the stable pair that completed end-to-end was Spacewar (sender) + DN2103 (passive); Nokia X20 + DN2103 repeatedly failed to produce mutual discovery even though both devices were advertising and scanning until the screen stayed awake. The reference app now starts a foreground wake-lock mitigation during live-proof automation sessions to reduce that quick-doze risk on doze-sensitive OEM builds. The direct-proof runner now requires sender `proof.complete` but accepts passive retained evidence without passive `proof.complete`, so the passive role can pass on retained history/export evidence even when the completion log is absent.

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

When using the reference app for Android direct proof, keep in mind that the
live-proof automation path now starts a foreground wake lock plus a foreground
service to keep doze-sensitive devices awake while the session is running.
That makes the reference app more reliable on aggressive OEM builds, but it is
still best to keep the device on a charger or otherwise avoid battery-saver
policies during long capture windows.

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
./gradlew :meshlink-proof:android:connectedDebugAndroidTest
```

Use physical devices for meaningful transport and power evidence.

## 6. Keep evidence and release posture in the right docs

Use this guide to run the Android proof app.

This proof app is not the release-review campaign surface. If you are looking for the campaign's retained selection vocabulary or the Android-only fallback when mixed live proof is unsupported, use the reference-app release-review docs instead.

For retained evidence and release posture, use:

- [MeshLink documentation map](../../docs/README.md)
- [About proof validation surfaces](../../docs/explanation/about-proof-validation-surfaces.md)
- [How to unblock MeshLink permissions on Android and iOS](../../docs/how-to/unblock-meshlink-permissions.md)
- [Benchmark and validation baselines](../../benchmarks/README.md)
- [Release decision](../../specs/ble-mesh-sdk/release-decision.md)
