# How to build and run the iOS proof app

This guide shows you how to build the iOS proof app, sign it for a simulator or
physical iPhone, and use the headless runner for retained physical benchmarks.

Use it when you need:

- the iOS side of the first-exchange tutorial
- a physical iPhone proof peer
- retained iPhone/Android benchmark evidence

## Choose the right validation surface first

| If you need to... | Use... |
|---|---|
| evaluate the supported product-like Android and iOS experience | [the reference app guide](../../docs/how-to/evaluate-meshlink-with-the-reference-app.md) |
| run an iPhone physical proof peer or transport fixture | this guide |
| inspect retained throughput, latency, or cold-start posture | [Benchmark and validation baselines](../../benchmarks/README.md) |

## Prerequisites

You need:

- Xcode
- a local Apple development team for physical-device signing
- an attached iPhone if you want device builds or headless physical runs
- an Android peer if you want cross-platform physical proof or benchmark runs

Install `xcodegen` only if you need to regenerate the committed Xcode project.

## 1. Regenerate the Xcode project only when the project spec changes

The committed `ProofApp.xcodeproj` is ready to open directly. Regenerate it only
if `project.yml` changes:

```bash
cd meshlink-proof/ios
xcodegen --spec project.yml
```

## 2. Build for the simulator

```bash
xcodebuild \
  -project meshlink-proof/ios/ProofApp.xcodeproj \
  -scheme ProofApp \
  -destination 'generic/platform=iOS Simulator' \
  build
```

On Apple Silicon, the committed project excludes the legacy `x86_64` simulator
slice and builds the `arm64` simulator target only. That matches the current
MeshLink framework export shape used by the proof app.

## 3. Build for a physical iPhone

Pass your development team at build time rather than storing it in the repo:

```bash
xcodebuild \
  -project meshlink-proof/ios/ProofApp.xcodeproj \
  -scheme ProofApp \
  -destination 'id=<your-device-udid>' \
  DEVELOPMENT_TEAM=<your-team-id> \
  build
```

If local cached signing assets are not enough, rerun with
`-allowProvisioningUpdates`.

On the first physical iPhone launch, allow Bluetooth access if iOS prompts for
it. If permissions are the blocker, use
[How to unblock MeshLink permissions on Android and iOS](../../docs/how-to/unblock-meshlink-permissions.md).

## 4. Install and launch on a physical iPhone

```bash
xcrun devicectl device install app \
  --device <your-device-udid> \
  ~/Library/Developer/Xcode/DerivedData/ProofApp-*/Build/Products/Debug-iphoneos/ProofApp.app

xcrun devicectl device process launch \
  --device <your-device-udid> \
  ch.trancee.meshlink.proof.ios
```

## 5. Set launch environment when you need a specific proof shape

The iOS proof app reads environment variables on startup.

| Variable | Meaning |
|---|---|
| `MESHLINK_APP_ID` | Mesh domain for discovery and trust isolation |
| `MESHLINK_POWER_MODE` | `automatic`, `performance`, `balanced`, or `powersaver` |
| `MESHLINK_BENCHMARK_PAYLOAD_BYTES` | Payload size for benchmark-oriented auto-send |
| `MESHLINK_BENCHMARK_BATTERY_LEVEL` | Battery level to feed into `updateBattery()` |
| `MESHLINK_BENCHMARK_IS_CHARGING` | Charging state to feed into `updateBattery()` |
| `MESHLINK_BENCHMARK_COLD_START` | Enables the cold-start benchmark path |
| `MESHLINK_DISABLE_AUTO_SEND` | Suppresses the automatic benchmark send |
| `MESHLINK_TRANSPORT_TELEMETRY` | Enables extra transport telemetry for diagnostic runs |
| `MESHLINK_BENCHMARK_TRANSPORT` | `meshlink`, `gatt`, or `gatt-notify` |

Use Xcode scheme environment variables for manual runs. For retained physical
benchmark work, prefer the headless runner so those values are applied
consistently.

### Choose the transport mode deliberately

The iPhone proof app can start in three transport modes.
Use them for different claims.

| Value | Meaning | Use it when you need to... | Important boundary |
|---|---|---|---|
| `meshlink` | normal MeshLink runtime | run the tutorial peer, manual product-path proof, or diagnostic sanity checks | closest to supported runtime behavior |
| `gatt` | iPhone-side GATT prototype | run the retained GATT benchmark-oriented prototype flow | requires `MESHLINK_BENCHMARK_PAYLOAD_BYTES`; passive `MESHLINK_DISABLE_AUTO_SEND=true` mode is not implemented |
| `gatt-notify` | iPhone-side GATT-notify prototype | run the retained notify-side benchmark-oriented prototype flow | requires `MESHLINK_BENCHMARK_PAYLOAD_BYTES`; passive `MESHLINK_DISABLE_AUTO_SEND=true` mode is not implemented |

If you are validating supported user-visible behavior, stay in `meshlink` mode
or move to the reference app. Use `gatt` and `gatt-notify` only when you are
explicitly doing retained transport investigation or benchmark-oriented work.

### Contributor note

The iOS proof host still presents one SwiftUI-facing view model, but launch
parsing, benchmark-only mode switching, transport-log capture, and benchmark
payload or receipt framing now live behind narrower proof-harness helpers.
That keeps proof-only behavior local without turning one view model into the
single home for every concern.

## 6. Prefer the headless runner for retained physical benchmarks

For retained cross-platform runs, use the repository runner instead of a manual
`devicectl --console` wrapper:

```bash
DEVELOPMENT_TEAM=<your-team-id> \
  benchmarks/scripts/run_headless_meshlink_benchmark.py \
  --android-serial <android-serial> \
  --ios-device <your-device-udid> \
  --payload-bytes 65536
```

The runner:

- prefers local cached signing assets before falling back to provisioning
- closes console capture cleanly instead of hanging on quiet proof apps
- validates the requested Android serial before blocking logcat steps
- keeps one stable app ID across repeat series by default
- supports per-run and average throughput thresholds for conformance-oriented
  retained series

Example retained repeat series:

```bash
benchmarks/scripts/run_headless_meshlink_benchmark.py \
  --android-serial <android-serial> \
  --ios-device <your-device-udid> \
  --payload-bytes 65536 \
  --skip-ios-build \
  --skip-ios-install \
  --repeat 5 \
  --require-run-min-kbps 60 \
  --require-average-kbps 60 \
  --run-dir /tmp/ios_meshlink_headless_conformance
```

## 7. Keep evidence and release posture in the right docs

Use this guide to build and run the iOS proof app.

For retained evidence and release posture, use:

- [MeshLink documentation map](../../docs/README.md)
- [About proof validation surfaces](../../docs/explanation/about-proof-validation-surfaces.md)
- [How to unblock MeshLink permissions on Android and iOS](../../docs/how-to/unblock-meshlink-permissions.md)
- [Benchmark and validation baselines](../../benchmarks/README.md)
- [Release decision](../../specs/001-ble-mesh-sdk/release-decision.md)
