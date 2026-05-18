# Proof iOS Integration

This app is the iOS reference host for the MeshLink quickstart.

## What the repo now contains

- a committed `ProofApp.xcodeproj` plus a matching `project.yml` XcodeGen spec
- a SwiftUI proof UI with Start / Stop / Send Hello controls, peer visibility, diagnostics, and inbound-message logging
- the Swift-installed `CryptoKit` bridge required by `IosCryptoProvider`
- a Swift-installed optional iOS BLE transport bridge used for future mixed Android/iOS GATT-notify bearer experiments from shared Kotlin code
- direct KMP framework integration through a pre-build Gradle script that runs `:meshlink:embedAndSignAppleFrameworkForXcode`
- the default MeshLink `deliveryRetryDeadline` behavior; the proof app leaves the default 15-second deadline unchanged
- a proof-only native GATT benchmark prototype activated with `MESHLINK_BENCHMARK_TRANSPORT=gatt` for iPhone -> Android fallback experiments
- a proof-only reverse GATT-notify benchmark prototype activated with `MESHLINK_BENCHMARK_TRANSPORT=gatt-notify`, where iPhone hosts a peripheral GATT service and streams the benchmark payload to an Android GATT client
- shared `POWER_MODE_CHANGED` diagnostics that expose `tier`, `advertisementIntervalMillis`, `connectionIntervalMillis`, `scanDutyCyclePercent`, `maxConnections`, `chunkBudgetBytes`, and `region`
- proof discovery now follows the shared MeshLink contract: fixed `4d455348` discovery UUID + one 128-bit payload UUID in a single advertisement with no scan response dependency

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

If Xcode has already cached a matching provisioning profile locally, the CLI
build can usually reuse that local signing state directly with just
`DEVELOPMENT_TEAM`; it does not necessarily need to talk to the developer-account
provisioning service on every run.

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

For retained headless physical benchmarks, prefer the repository runner:

```bash
DEVELOPMENT_TEAM=<your-team-id> \
  benchmarks/scripts/run_headless_meshlink_benchmark.py \
  --android-serial <android-serial> \
  --ios-device <your-device-udid> \
  --payload-bytes 65536
```

The runner now tries local cached signing assets first and only falls back to
`xcodebuild -allowProvisioningUpdates` if that local-signing path fails. If a
matching cached provisioning profile already exists, the runner can also infer
`DEVELOPMENT_TEAM` automatically and you may omit it from the command.

```bash
benchmarks/scripts/run_headless_meshlink_benchmark.py \
  --android-serial <android-serial> \
  --ios-device <your-device-udid> \
  --payload-bytes 65536
```

For retained series runs, the runner now also supports sequential repetition
with numbered run directories and a min/avg/max summary. By default, one
retained series now keeps the same app ID across all repeated runs so peer
identity, trust, and route state stay comparable within the series:

```bash
benchmarks/scripts/run_headless_meshlink_benchmark.py \
  --android-serial <android-serial> \
  --ios-device <your-device-udid> \
  --payload-bytes 65536 \
  --skip-ios-build \
  --skip-ios-install \
  --repeat 3 \
  --run-dir /tmp/ios_meshlink_headless_series
```

For conformance-oriented retained series, the runner can also fail on per-run
or average throughput thresholds:

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

If you explicitly want fresh per-run mesh identity churn for diagnostics,
add `--vary-app-id-per-run`.

The runner exists because a naive `devicectl --console` wrapper can appear to
hang after the scored benchmark line if the proof app stays alive but quiet.
The retained runner uses a hard timeout plus idle-aware console capture so the
logs always close cleanly, and the repeat mode reduces the amount of ad-hoc
shell scripting needed for retained Samsung / OPPO rerun series.

The runner now also validates the requested Android serial before any blocking
`adb logcat` step, so a stale / unplugged device ID fails fast with a clear
error instead of waiting forever on `- waiting for device -`.

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
- LOW-power diagnostics reported `scanDutyCyclePercent=5` and now require `connectionIntervalMillis=500`
- the 256-byte latency benchmark completed in `28 ms`
- a deeper mixed Android/iOS initiator-policy redesign now makes Android the
  deterministic L2CAP initiator for mixed-platform peers, so iPhone can host the
  inbound channel and request low connection latency on the current proof path
- fresh retained final-matrix evidence on that deterministic path reached
  `52.03 KB/s` to OPPO and `39.48 KB/s` to Samsung, with recipient-confirmed
  proof receipts on every retained run; this is still below the normative
  `>= 60 KB/s` target
- a proof-only native GATT benchmark prototype is feasible on the same iPhone 15:
  Samsung reached `23.92 KB/s` with `setupMs=1994`, OPPO reached `21.96 KB/s`
  with `setupMs=1926`, and both runs negotiated `maxWriteWithoutResponse=512` /
  Android `mtu=517`; this is still well below the normative `>= 60 KB/s` target
- a later proof-only reverse GATT-notify prototype is the first retained future
  branch to exceed the normative iOS target on physical evidence: OPPO reached
  `73.65 KB/s` on the initial retained run (with one lower `43.13 KB/s` rerun
  and one unsubscribe failure), while Samsung reached `65.98 KB/s` and
  `68.52 KB/s` on two retained runs (with one lower `53.87 KB/s` rerun)
- the optional Swift-installed iOS transport bridge now unblocks the shared KMP
  runtime from calling `CBPeripheralManager.updateValue` for future bearer
  experiments, but the first real MeshLink product-path mixed-bearer reruns are
  still blocked by route expiry before the transfer finishes
- later Phase-22 remediations restored forward 64 KiB product-path completion
  on the Samsung reference peer, but recipient-confirmed proof closure still
  fails because the passive Android receipt path remains `NotSent(reason=
  UNREACHABLE)`, and OPPO still has a sender-side `TRANSFER_TIMED_OUT` rerun on
  the same branch
- the earlier stricter recipient-confirmed `ReceiptTimeout` matrix is now
  historical diagnostic evidence only
- the latest current-head inline mixed-bearer physical reruns now clear the
  normative target on both reference peers: Samsung headless reruns reached
  `61.96-70.64 KB/s` and OPPO headless reruns reached `77.48-80.10 KB/s`, all
  with passive proof receipts retained on attempt 1 on the latest rebuilt /
  install headless path
- fresh immediate retained repeat series now also stay above target on both
  reference peers after proof auto-send task de-duplication: Samsung now has a
  5-run retained series at `60.38-63.81 KB/s` (avg `62.41 KB/s`) and OPPO has a
  3-run retained series at `74.51-79.50 KB/s` (avg `77.20 KB/s`)
- the headless runner now keeps one stable app ID across a retained repeat
  series by default so peer identity and trust remain constant through the
  comparison set; under that corrected conformance posture Samsung retained two
  fresh 5-run series at `60.09-65.37 KB/s` and `62.44-66.81 KB/s`, while OPPO
  retained a fresh 3-run series at `82.05-85.11 KB/s`
- the decisive transport-side win was suspending discovery during large inline
  sends so the product path no longer paid concurrent scan / advertise churn
  while draining the GATT side bearer
- later proof-app fixes removed two benchmark-harness false negatives:
  rescheduling auto-send when a direct `ROUTE_DISCOVERED` arrives after a
  transient `Peer lost`, and cancelling stale per-peer auto-send tasks so one
  physical run cannot emit duplicate scored benchmark sends

Shared harness evidence now also covers the common US2 runtime:

- three-node routed delivery across a relay hop
- route reconvergence after topology change
- 64 KiB routed transfer over a 512-byte per-delivery virtual link budget
- bounded, jittered exponential-backoff no-route retry expiry with `UNREACHABLE`
- immediate retry when a route appears before `deliveryRetryDeadline` expires

Current release / future-branch framing:

- the active project stakeholder selected the explicit waiver /
  known-limitation path for the released baseline on `2026-05-14`
- that historical waiver still applies to the already-released baseline and its
  earlier retained evidence (`39.48-52.03 KB/s` on the deterministic
  mixed-platform path)
- the latest current-head future branch now has retained recipient-confirmed
  64 KiB evidence above the normative `>= 60 KB/s` target on both reference
  peers (`61.96-70.64 KB/s` Samsung, `77.48-80.10 KB/s` OPPO)
- when describing current iOS large-transfer behavior, distinguish the waived
  released baseline from the newer future branch that now clears `SC-004` on
  reference hardware
- the proof-only GATT prototypes remain non-normative and must not be cited as
  product-conformance fallback evidence

## Current physical proof flow

1. Launch the app on the iPhone.
2. Tap **Start MeshLink**.
3. Wait for a nearby Android or iOS proof peer to appear.
4. Tap **Send Hello**.
5. Confirm the diagnostics and inbound-message log match the quickstart flow.
6. For historical released-baseline MeshLink 64 KiB proof runs, keep the
   explicit waiver framing. For the latest current-head future branch, use the
   retained Samsung / OPPO headless evidence when discussing `SC-004`
   conformance.
7. If you explicitly launch `MESHLINK_BENCHMARK_TRANSPORT=gatt-notify`, treat
   the result as a future-branch proof experiment only. It is promising, but it
   is not yet the normative MeshLink product path.
