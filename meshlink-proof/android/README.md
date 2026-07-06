# How to run the Android proof app

This guide shows you how to build the Android proof app and launch it with a
chosen mesh domain. The proof app itself stays on the normal MeshLink path;
transport choice belongs to the MeshLink library. Benchmark-only transport
experiments live in the separate `androidTest` harness.

Use it when you need:

- a second device for the MeshLink tutorial flow
- an Android proof peer for manual validation
- an Android proof fixture for passive-transport or benchmark work, with the proof-app launch path still owned by the library

## Support floor and crypto note

- Current app floor: Android API 26+
- Android BLE/GATT route discovery is supported on API 26+, while L2CAP
  client sockets are only available on API 34+.
- Route benchmarks now gate on real route readiness (`ROUTE_DISCOVERED` or
  `HOP_SESSION_ESTABLISHED`) instead of assuming a socket-capability check.
  The fast-route benchmark keeps the 50 ms ceiling on L2CAP-capable devices,
  while the GATT fallback benchmark uses its own 500 ms ceiling.
- X25519/XDH and ChaCha20-Poly1305 are only officially guaranteed by Android
  on later APIs.
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
| inspect retained throughput, latency, or cold-start posture | [Benchmark and validation baselines](../../meshlink-benchmark/README.md) |

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

If you are using this app as the receiving proof peer for
[Your first MeshLink exchange](../../docs/tutorials/your-first-meshlink-exchange.md),
keep the default launch settings and override only the `appId` so it
matches the tutorial host app. Transport is chosen by the MeshLink library.

For the Android direct-proof runner on this host, the stable pair that completed end-to-end was Spacewar (sender) + DN2103 (passive); Nokia X20 + DN2103 repeatedly failed to produce mutual discovery even though both devices were advertising and scanning until the screen stayed awake. The reference app now starts a foreground wake-lock mitigation during live-proof automation sessions to reduce that quick-doze risk on doze-sensitive OEM builds. The direct-proof runner still treats sender `proof.complete` as the hard success gate and accepts passive retained evidence without passive `proof.complete`. The proof-app test itself should verify that both sender and passive can initiate a message when the UI exposes a send control, and the runner force-stops both proof-app packages in cleanup after success or failure.

## 3. Override launch settings when you need a specific proof shape

The Android proof app reads these intent extras on launch:

| Extra | Type | Meaning |
|---|---|---|
| `meshlink.appId` | string | Mesh domain for discovery and trust isolation |
| `meshlink.powerMode` | string | `automatic`, `performance`, `balanced`, or `powersaver` |
| `meshlink.benchmarkPayloadBytes` | int | Payload size for benchmark-oriented auto-send |
| `meshlink.benchmarkColdStart` | bool | Enables the cold-start benchmark path |
| `meshlink.disableAutoSend` | bool | Suppresses the automatic benchmark send |
| `meshlink.forceInitiator` | bool | Forces the proof peer to take the initiator role |

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

Example: start the proof-only setup without automatic MeshLink
sends.

```bash
adb shell am start \
  -n ch.trancee.meshlink.proof.android/.MainActivity \
  --es meshlink.appId demo.meshlink \
  --ez meshlink.disableAutoSend true
```

Example: force the Huawei proof peer into the initiator role for balanced proof runs.

```bash
adb shell am start \
  -n ch.trancee.meshlink.proof.android/.MainActivity \
  --es meshlink.appId demo.meshlink \
  --ez meshlink.forceInitiator true
```

## 4. Transport is library-owned

The Android proof app does not expose a transport override. Use the default launch path for supported proof runs; the MeshLink library chooses transport and the logs report the observed transport when needed.

The benchmark-only `androidTest` fixtures can still exercise transport-specific scenarios for retained investigation, but that is a separate harness from the proof-app launch path.

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
`androidTest`. The default connected Android test task excludes those benchmark
classes so the normal attached-device test run stays fast.

Run the benchmark-only suite with:

```bash
./gradlew :meshlink-proof:android:connectedDebugAndroidTest -PmeshlinkProofRunBenchmarks=true
```

Use physical devices for meaningful transport and power evidence.

## 6. Recover a device whose advertise or scan retries are exhausted

Use this when a device's `proof.log` shows repeated

```
DIAG DISCOVERY_ADVERTISE_FAILED ... errorName=ADVERTISE_FAILED_TOO_MANY_ADVERTISERS willRetry=true
DIAG DISCOVERY_ADVERTISE_FAILED ... willRetry=false
```

or the scan-side equivalent (`DIAG DISCOVERY_SCAN_FAILED ... errorName=SCAN_FAILED_* willRetry=false`),
followed by `HOP_SESSION_FAILED` with `routeAvailable=false`. A device that has
advertised or scanned for a long time can hold stuck advertiser/scanner slots
in its Bluetooth stack even while `dumpsys bluetooth_manager` still reports
`enabled: true` / `state: ON`.

Restart the device's Bluetooth stack:

```bash
adb -s <serial> shell cmd bluetooth_manager disable
adb -s <serial> shell cmd bluetooth_manager wait-for-state:STATE_OFF
adb -s <serial> shell cmd bluetooth_manager enable
adb -s <serial> shell cmd bluetooth_manager wait-for-state:STATE_ON
```

Then relaunch the proof app on that device (and its pair partner) and rerun
the scenario. This clears stuck advertiser/scanner slots without needing a
full device reboot.

### BLE_ON limbo state

`dumpsys bluetooth_manager` can report a third state beyond `ON`/`OFF`:
`BLE_ON`, a hybrid state where "classic" Bluetooth is disabled
(`enabled: false`) but a BLE-only client registration (scan/advertise/GATT)
keeps the stack partially alive. This was reproduced on a Nothing A063 right
after a proof-app run: issuing `cmd bluetooth_manager disable` while the proof
app still held a live BLE registration left the device stuck in `BLE_ON`
instead of transitioning fully to `OFF`, and `wait-for-state:STATE_OFF` fails
outright with exit status 255 in that case rather than waiting or timing out.

Manual recovery is simple - from `BLE_ON`, `cmd bluetooth_manager enable` +
`wait-for-state:STATE_ON` brings the device straight back to a clean `ON`
state, exactly as it would from `OFF`. `run_android_proof_fleet.py`'s
automatic recovery (below) is hardened against this: it force-stops the proof
app *before* issuing `disable` (releasing any BLE registration the app itself
holds), then polls `dumpsys bluetooth_manager` directly and accepts either
`OFF` or `BLE_ON` as a valid "disabled enough" outcome before re-enabling,
instead of relying on the brittle `wait-for-state` subcommand. See
[the BLE_ON limbo state finding](../../docs/explanation/reference-app-physical-integration-findings.md#3-the-bluetooth-stack-can-settle-into-a-ble_on-limbo-state-after-disable)
for the full investigation.

If you drive fleet runs through
`meshlink-proof/scripts/run_android_proof_fleet.py`, this recovery is
automatic: the script inspects each device's captured `proof.log` for the
exhausted-retry signature above, restarts the affected device's Bluetooth
stack, relaunches every pair that includes it, and recaptures logs before
finalizing the summary. The summary report gets a "Bluetooth stack recovery"
section listing which device(s) were restarted and why. Pass
`--no-auto-recover-bluetooth` to disable this and inspect the raw first-pass
failure instead.

Every run also persists the full `MeshLinkReferenceAutomation`-tagged logcat
per device to `logs/<serial>.logcat.log` (see the "Logcat evidence" section of
the summary). `proof.log` only contains diagnostics the proof app explicitly
writes; lower-level BLE/GATT/L2CAP transport detail - including receive-path
evidence needed to diagnose missing inbound deliveries - only ever reaches
logcat. Widen the capture window with `--logcat-tail-lines` if the evidence
you need scrolled out of the default 20000-line tail during a long
`--wait-seconds` run.

A noisy device (heavy non-MeshLink log traffic from other apps/system
services, observed on an OPPO CPH2359) can push every tagged line out of even
a generous tail window, producing a silent, misleading 0-line capture. To
guard against this, the capture step automatically retries once with a much
wider 100000-line tail whenever the requested window captures 0 matching
lines, so this can no longer go unnoticed. See
[the BLE scan-miss finding](../../docs/explanation/reference-app-physical-integration-findings.md#5-the-ble-scanner-can-silently-miss-a-specific-peers-advertisements-oemchipset-compatibility)
for the investigation this uncovered.

As its last step, every run force-stops the proof app on every device it
exercised. Without this, a device left running from a prior run keeps
scanning/advertising/holding GATT connections under the old run's app ID,
which can make a later, otherwise-isolated `--device` rerun look like a
protocol failure when it is really just interference from a still-running
earlier instance.

For the full list of diagnostic codes and severities, see the
[MeshLink SDK API reference](../../docs/reference/meshlink-sdk-api.md#diagnostics).

## 7. Keep evidence and release posture in the right docs

Use this guide to run the Android proof app.

This proof app is not the release-review campaign surface. If you are looking for the campaign's retained selection vocabulary or the Android-only fallback when mixed live proof is unsupported, use the reference-app release-review docs instead.

For retained evidence and release posture, use:

- [MeshLink documentation map](../../docs/README.md)
- [About proof validation surfaces](../../docs/explanation/about-proof-validation-surfaces.md)
- [How to unblock MeshLink permissions on Android and iOS](../../docs/how-to/unblock-meshlink-permissions.md)
- [Benchmark and validation baselines](../../meshlink-benchmark/README.md)
- [Release decision](../../specs/ble-mesh-sdk/release-decision.md)
