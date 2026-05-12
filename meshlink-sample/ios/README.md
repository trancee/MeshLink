# Proof iOS Integration

This app is the iOS reference host for the MeshLink quickstart.

## What the repo now contains

- a committed `ProofApp.xcodeproj` plus a matching `project.yml` XcodeGen spec
- a SwiftUI proof UI with Start / Stop / Send Hello controls, peer visibility, diagnostics, and inbound-message logging
- the Swift-installed `CryptoKit` bridge required by `IosCryptoProvider`
- direct KMP framework integration through a pre-build Gradle script that runs `:meshlink:embedAndSignAppleFrameworkForXcode`
- the default MeshLink `deliveryRetryDeadline` behavior; the proof app leaves the default 15-second deadline unchanged
- a proof-only native GATT benchmark prototype activated with `MESHLINK_BENCHMARK_TRANSPORT=gatt` for iPhone -> Android fallback experiments
- shared `POWER_MODE_CHANGED` diagnostics that expose `tier`, `advertisementIntervalMillis`, `scanDutyCyclePercent`, `maxConnections`, `chunkBudgetBytes`, and `region`

## Build and run

### Regenerate the Xcode project (optional)

The committed Xcode project is ready to open directly. If the project spec changes,
regenerate it with:

```bash
cd meshlink-sample/ios
xcodegen --spec project.yml
```

### Build for the simulator

```bash
xcodebuild \
  -project meshlink-sample/ios/ProofApp.xcodeproj \
  -scheme ProofApp \
  -destination 'id=6C7DD73A-EC9C-46F9-B0B9-DD136F748621' \
  build
```

### Build for a physical iPhone

You need a local Apple development team for device signing. Either:

- open `ProofApp.xcodeproj` in Xcode and select a team under Signing & Capabilities, or
- pass `DEVELOPMENT_TEAM=<your-team-id>` to `xcodebuild`

Example CLI build:

```bash
xcodebuild \
  -project meshlink-sample/ios/ProofApp.xcodeproj \
  -scheme ProofApp \
  -destination 'id=<your-device-udid>' \
  -allowProvisioningUpdates \
  DEVELOPMENT_TEAM=<your-team-id> \
  build
```

### Install and launch on a physical iPhone

```bash
xcrun devicectl device install app \
  --device <your-device-udid> \
  ~/Library/Developer/Xcode/DerivedData/ProofApp-*/Build/Products/Debug-iphoneos/ProofApp.app

xcrun devicectl device process launch \
  --device <your-device-udid> \
  ch.trancee.meshlink.proof.ios
```

The physical launch path now works on the attached iPhone 15 once a local
development team is supplied at build time.

## Current validation status

Verified in this repository state:

- `./gradlew :meshlink:compileKotlinIosSimulatorArm64`
- `./gradlew :meshlink:iosSimulatorArm64Test --tests '*IosL2capFrameBufferTest'`
- `xcodebuild -project meshlink-sample/ios/ProofApp.xcodeproj -scheme ProofApp -destination 'id=6C7DD73A-EC9C-46F9-B0B9-DD136F748621' test`
- `xcodebuild -project meshlink-sample/ios/ProofApp.xcodeproj -scheme ProofApp -destination 'id=00008120-00011DEE0105A01E' DEVELOPMENT_TEAM=<local-team-id> build`
- `xcrun devicectl device install app --device <device-udid> <built-app-path>`
- `xcrun devicectl device process launch --device <device-udid> ch.trancee.meshlink.proof.ios`

Physical proof findings on iPhone 15:

- direct L2CAP proof runs now work without requiring OS pairing
- cold start reached `mesh.start()` in `18 ms`
- LOW-power diagnostics reported `scanDutyCyclePercent=5`
- the 256-byte latency benchmark completed in `28 ms`
- fresh queued-writer follow-ups still keep 64 KiB delivery in the ~20 KB/s class:
  a clean OPPO rerun reached `20.05 KB/s`, a telemetry-enabled Samsung rerun reached
  `19.62 KB/s` with full `65536`-byte receipt, and a separate passive Samsung rerun
  failed `NotSent(reason=UNREACHABLE)` after repeated
  `transport.handshake.message1.send` failures
- a proof-only native GATT benchmark prototype is feasible on the same iPhone 15:
  Samsung reached `23.92 KB/s` with `setupMs=1994`, OPPO reached `21.96 KB/s`
  with `setupMs=1926`, and both runs negotiated `maxWriteWithoutResponse=512` /
  Android `mtu=517`; this is still well below the normative `>= 60 KB/s` target
- the stricter recipient-confirmed MeshLink rerun currently fails in all four
  fresh Samsung/OPPO × 256 B/64 KiB cells: each iPhone run ended
  `ReceiptTimeout` after ~21.3-21.4 s, and each passive Android proof app
  retained a matching `BENCHMARK receipt send(...) -> NotSent(reason=UNREACHABLE)`
  line for the same token

Shared harness evidence now also covers the common US2 runtime:

- three-node routed delivery across a relay hop
- route reconvergence after topology change
- 64 KiB routed transfer over a 512-byte per-delivery virtual link budget
- bounded, jittered exponential-backoff no-route retry expiry with `UNREACHABLE`
- immediate retry when a route appears before `deliveryRetryDeadline` expires

Current blocker:

- large-payload iPhone throughput remains below the `>= 60 KB/s` target and the
  stricter recipient-confirmed MeshLink benchmark still fails even at 256 B,
  even though delivery, pairing-free interop, cold start, power, latency, and a
  proof-only GATT fallback prototype now work; the Samsung reference path still
  shows variable stability across fresh reruns

## Current physical proof flow

1. Launch the app on the iPhone.
2. Tap **Start MeshLink**.
3. Wait for a nearby Android or iOS proof peer to appear.
4. Tap **Send Hello**.
5. Confirm the diagnostics and inbound-message log match the quickstart flow.
6. For 64 KiB proof runs, treat throughput as the remaining open benchmark
   blocker rather than a launch or pairing problem.
