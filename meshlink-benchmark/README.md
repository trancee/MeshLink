# MeshLink benchmark and validation baselines

Use this page for the current benchmark posture.

The normative targets still live in:

- `specs/ble-mesh-sdk/spec.md` — success criteria
- `specs/ble-mesh-sdk/plan.md` — mirrored performance goals

A failing result in this directory is still useful retained evidence, but it
does not relax the underlying requirement.

If you need the full historical experiment trail, older matrices, and detailed
investigation notes, use [benchmark history](history.md).

## Normative acceptance thresholds (mirror only)

| Criterion | Normative threshold | Canonical source |
|---|---:|---|
| 1-hop 256 B message latency | <= 50 ms p95 after connection establishment | Spec `SC-004`, Plan `Performance Goals` |
| 1-hop 64 KiB Android throughput | >= 80 KB/s | Spec `SC-004`, Plan `Performance Goals` |
| 1-hop 64 KiB iOS throughput | >= 60 KB/s | Spec `SC-004`, Plan `Performance Goals` |
| 8-peer steady-state heap | <= 8 MiB | Spec `SC-005`, Plan `Performance Goals` |
| LOW-mode scan duty | <= 5% | Spec `SC-006`, Plan `Performance Goals` |
| LOW-mode 1-hop 256 B delivery | <= 5 s after peer discovery and connection establishment | Spec `SC-006`, Plan `Performance Goals` |
| Cold start to first advertisement | < 500 ms | Plan `Performance Goals` |
| 10-node route convergence | < 3 s | Spec `SC-002`, Plan `Performance Goals` |
| JVM wire encode/decode | < 1 us/op | Plan `Performance Goals` |

Rows not listed above are retained as regression-tracked evidence only.

## Latest retained evidence

### JVM evidence (2026-06-13)

| Metric | Latest result | Interpretation |
|---|---:|---|
| Convergence, 10-node topology change | 0.344 ms/op | Meets the route-convergence target |
| Wire encode message | 0.269 us/op | Meets the codec target |
| Wire decode message | 0.083 us/op | Meets the codec target |
| Wire encode transfer chunk | 0.210 us/op | Meets the codec target |
| Wire decode transfer chunk | 0.083 us/op | Meets the codec target |
| X25519 keypair, JCA/JVM provider | 89.649 us/op | Baseline retained result for the platform-backed provider. |
| X25519 keypair, pure fallback provider | 282.161 us/op | About 3.1x slower than the JCA baseline; acceptable compatibility-path evidence, not a preferred fast path. |
| X25519 agreement, JCA/JVM provider | 92.409 us/op | Baseline retained result for the platform-backed provider. |
| X25519 agreement, pure fallback provider | 277.250 us/op | About 3.0x slower than the JCA baseline; acceptable compatibility-path evidence, not a preferred fast path. |
| 8-peer steady-state memory budget | 3,993,216 retained bytes | Meets the memory target |

### Physical mobile evidence

| Surface | Latest retained result | Interpretation |
|---|---|---|
| Android cold start | Physical benchmark pass (< 500 ms) | Meets the cold-start target |
| Android LOW-power scan duty | 5% | Meets the LOW-mode duty target |
| Android LOW-power 256 B delivery | 411 ms (Samsung -> OPPO) | Meets the LOW-mode delivery target |
| Android 256 B latency | Physical benchmark pass (<= 50 ms) | Meets the latency target |
| Android 64 KiB throughput | 8.5-10.4 KB/s (Samsung XCover4 <-> OPPO Reno8, GATT fallback bearer) | Below the Android throughput target on this reference pairing. Samsung Galaxy XCover 4 (SDK 28 / Android 9) cannot host an L2CAP server socket (`L2CAP server socket unavailable: runtime capability probe returned false`; that API needs API 29+), so every transfer to/from it falls back to the much slower `GATT_ONLY`/`GATT_FALLBACK` bearer instead of L2CAP CoC. This is an SDK/hardware ceiling on this specific reference device, not a code regression. The previously retained `1306.12 KB/s` figure does not appear anywhere in the current run ledger and was almost certainly captured on an L2CAP-capable pairing; it is corrected here to a representative GATT-fallback measurement. See [benchmark history](history.md) for the full investigation. |
| Android receipt reliability, Samsung <-> OPPO fleet series | 0/6 -> 4/6 -> 6/10 confirmed after 3 committed fixes | Two real code bugs fixed (scan-result log flooding, redundant power-tier restarts triggering Android's BLE scan throttle); a residual device-specific BLE scan wedge on one test unit is mitigated by a self-healing scan watchdog but not fully eliminated by app code alone -- see [benchmark history](history.md) for the full root-cause trail |
| iOS cold start | 18 ms (iPhone 15) | Meets the cold-start target |
| iOS LOW-power scan duty | 5% (iPhone 15) | Meets the LOW-mode duty target |
| iOS LOW-power 256 B delivery | 239 ms (iPhone 15 -> OPPO) | Meets the LOW-mode delivery target |
| iOS 256 B latency | 28 ms (iPhone 15 -> Samsung) | Meets the latency target |
| iOS 64 KiB throughput | 61.96-70.64 KB/s on Samsung, 77.48-80.10 KB/s on OPPO | Current-head retained evidence clears the iOS throughput target on both reference peers |

## Full fleet comparison (2026-07-09, all attached devices, fresh build)

Captured via `run_fleet_meshlink_benchmark.py --mode crypto --execute` and
`--mode transport --payload-bytes 65536 --execute` against every device
attached to the host at run time (14 distinct Android phones, ring-paired for
transport). Raw ledgers: `fleet-results/crypto-ledger.jsonl` and
`fleet-results/ledger.jsonl` (gitignored evidence); the per-device history is
also committed into `scripts/device-test-matrix.catalog.json` and rendered in
[`docs/reference/device-test-matrix.md`](../docs/reference/device-test-matrix.md).

**Build hygiene note**: an earlier same-day pass on this fleet was discarded
and re-run after discovering every device was running a stale proof-app build
(up to 2 days old), missing 5 real GATT reliability fixes landed
2026-07-07/09 (`e2398b42`, `1b60bf66`, `10b2a5e9`, `5bc03b59`, `6e153537`).
The stale build's symptoms (repeated GATT write timeouts, session left in a
bad state, endless reconnect loops) were a subset of exactly what those
commits fixed. The numbers below were captured only after uninstalling the
old app and installing a fresh `assembleDebug` build (`adb install -g -r`,
auto-granting runtime Bluetooth/location permissions since a full
uninstall+reinstall resets them and a headless `adb shell am start` can't
dismiss the permission dialog) on every device. **Rule going forward:**
always build fresh and uninstall-then-reinstall before any physical-device
benchmark, proof-app, or reference-app run -- see `AGENTS.md`.

### Crypto primitives across the fleet (single-device timing, no pairing)

| Device | SDK | Provider | X25519 KeyGen (us) | X25519 Agree (us) | Ed25519 KeyGen (us) | Ed25519 Sign (us) | Ed25519 Verify (us) | ChaCha20 Seal (us) | ChaCha20 Open (us) |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|
| Samsung Galaxy Z Flip4 | 36 | Ed25519FallbackCryptoProvider | 61.8 | 100.0 | 611.4 | 509.9 | 1476.7 | 8.5 | 8.2 |
| Huawei Nova 9 | 31 | Ed25519FallbackCryptoProvider | 64.3 | 100.1 | 514.7 | 483.0 | 1496.6 | 51.6 | 51.7 |
| Nothing Phone (2) | 36 | JcaCryptoProvider | 71.1 | 104.5 | 54.0 | 96.5 | 82.4 | 29.3 | 29.7 |
| Gigaset GX6 | 33 | Ed25519FallbackCryptoProvider | 71.0 | 145.0 | 937.1 | 817.3 | 2087.5 | 31.1 | 27.6 |
| Nothing Phone (1) | 35 | JcaCryptoProvider | 77.4 | 158.1 | 131.4 | 144.1 | 122.2 | 54.7 | 54.5 |
| Motorola Edge 30 Fusion | 34 | Ed25519FallbackCryptoProvider | 74.5 | 154.3 | 976.7 | 834.0 | 2213.6 | 30.3 | 27.1 |
| OPPO Reno8 5G | 34 | JcaCryptoProvider | 86.6 | 147.1 | 120.2 | 134.3 | 120.0 | 58.0 | 60.1 |
| OPPO A57s | 33 | Ed25519FallbackCryptoProvider | 277.3 | 420.6 | 5313.7 | 4110.6 | 9425.5 | 272.7 | 367.5 |
| Oppo A53s | 31 | Ed25519FallbackCryptoProvider | 279.6 | 600.0 | 3202.3 | 2758.3 | 6990.6 | 207.3 | 205.3 |
| Realme C55 | 35 | JcaCryptoProvider | 313.1 | 731.8 | 542.6 | 599.1 | 632.5 | 215.5 | 207.7 |
| Xiaomi Pocophone F1 | 29 | AndroidFallbackCryptoProvider | 1351.0 | 1304.4 | 636.6 | 667.3 | 2310.3 | 103.8 | 50.1 |
| OnePlus 6 | 30 | AndroidFallbackCryptoProvider | 1407.9 | 1401.4 | 703.4 | 725.3 | 2478.0 | 151.7 | 35.8 |
| Xiaomi Mi Note 3 | 28 | AndroidFallbackCryptoProvider | 2710.7 | 2677.6 | 1380.3 | 1286.7 | 4715.9 | 141.6 | 43.9 |
| Samsung Galaxy XCover 4 | 28 | AndroidFallbackCryptoProvider | 15769.2 | 15687.8 | 11884.3 | 11905.4 | 46177.4 | 290.5 | 112.9 |

Table is sorted fastest-to-slowest by X25519 key generation. Consistent with
the earlier pass: devices on `JcaCryptoProvider` (hardware/platform-backed
X25519, newer SDKs) or a fast `Ed25519FallbackCryptoProvider` are roughly
1-2 orders of magnitude faster than devices on the pure-Kotlin
`AndroidFallbackCryptoProvider` (older SDKs, typically API < 32). Samsung
Galaxy XCover 4 (SDK 28) remains the slowest device in the fleet for
asymmetric-key operations by a wide margin; ChaCha20-Poly1305 stays within a
much narrower band (~3-45x) across the whole fleet. These figures are
essentially unchanged from the earlier (stale-build) pass, as expected --
the GATT reliability bugs fixed by the July 7-9 commits only affect the BLE
transport path, not on-device crypto primitive timing.

### Transport (64 KiB payload, ring-paired, real BLE traffic, fresh build)

| Sender | Receiver | Result | Throughput (KB/s) | Receipt confirmed |
|---|---|---|---:|---|
| Nothing Phone (2) | Huawei Nova 9 | Sent | 20.07 | yes |
| Huawei Nova 9 | Samsung Galaxy XCover 4 | Sent | 17.97 | yes |
| Samsung Galaxy XCover 4 | Xiaomi Mi Note 3 | timed out waiting for benchmark line | — | no |
| Xiaomi Mi Note 3 | Realme C55 | Sent | 35.16 | yes |
| Realme C55 | Oppo A53s | Sent | 16.55 | no (receipt not confirmed) |
| Oppo A53s | OPPO Reno8 5G | NotSent (UNREACHABLE) | 1.55 | no |
| OPPO Reno8 5G | Gigaset GX6 | Sent | 17.39 | yes |
| Gigaset GX6 | OPPO A57s | Sent | 18.87 | yes |
| OPPO A57s | Samsung Galaxy Z Flip4 | Sent | 15.41 | yes |
| Samsung Galaxy Z Flip4 | Motorola Edge 30 Fusion | Sent | 666.67 | yes |
| Motorola Edge 30 Fusion | Xiaomi Pocophone F1 | Sent | 25.27 | yes |
| Xiaomi Pocophone F1 | OnePlus 6 | timed out waiting for benchmark line | — | no |
| OnePlus 6 | Nothing Phone (1) | timed out waiting for benchmark line | — | no |
| Nothing Phone (1) | Nothing Phone (2) | Sent | 1454.55 | yes |

**11 of 14 ring-paired runs (79%) sent the payload** (9 of those with a
confirmed recipient receipt), up from 5/14 sent (4/14 confirmed) on the
same fleet's stale build a few minutes earlier -- direct evidence that the
July 7-9 GATT reliability fixes materially improve real-world transport
success rate once actually installed. The 3 remaining failures
(Samsung XCover 4 <-> Xiaomi Mi Note 3, both SDK 28; Oppo A53s -> OPPO
Reno8 5G; and the Xiaomi Pocophone F1 -> OnePlus 6 -> Nothing Phone (1)
chain) show handshake/connection-establishment failures
(`transport.handshake.message1/2.send` with `routeAvailable=false`, or
outright `UNREACHABLE` after the normal retry budget) rather than the
write-timeout loop seen on the stale build, and involve older or
already-documented-quirky devices (see the Quirks column in the
[device test matrix](../docs/reference/device-test-matrix.md)). These look
like genuine per-pair BLE connection flakiness on specific hardware rather
than a single systemic bug, but have not been root-caused further in this
pass.

## Current release-decision posture

Two truths matter at the same time:

1. the historical released baseline still carries the documented iOS waiver
   history
2. the latest current-head retained evidence now clears `SC-004` on both
   reference peers

Use the release-decision document for the canonical release framing, and use the
historical benchmark archive when you need the full path from waiver-era runs to
current-head conformance evidence.

## Current takeaways

- The current JVM benchmark posture meets the retained codec, convergence, and
  memory targets listed above.
- Android physical evidence continues to meet the retained latency,
  cold-start, and LOW-mode targets on the reference peers, but the 64 KiB
  throughput target is not met on the Samsung XCover4 <-> OPPO Reno8 reference
  pairing: Samsung cannot host an L2CAP server socket (SDK 28), so transfers
  fall back to a much slower GATT bearer (~8-10 KB/s versus the `>= 80 KB/s`
  target). This is a per-device SDK/hardware ceiling, not a regression.
- Current-head iPhone 15 product-path evidence now clears the retained
  `>= 60 KB/s` iOS target on both Samsung and OPPO reference peers with
  recipient-confirmed closure.
- The historical investigation trail still matters for understanding how the
  transport posture evolved, but it no longer belongs on the critical reading
  path for the current state.

## Physical device scripts

- `scripts/run_headless_meshlink_benchmark.py` drives exactly one Android peer
  (as sender or receiver via `adb`) against exactly one iOS peer (via
  `xcrun devicectl`), for cross-platform BLE throughput/latency evidence.
  Requires a macOS host with Xcode.
- `scripts/run_fleet_meshlink_benchmark.py` is Android-only and needs no
  Xcode/iOS device. It auto-discovers every currently attached `adb` device,
  dedupes duplicate transports (USB/Wi-Fi/mDNS) for the same physical phone by
  hardware serial, and cross-references friendly device names against
  `docs/reference/device-test-matrix.md`. It supports two independent modes
  via `--mode`:
  - `transport` (default): pairs devices (`--pairing ring` by default, or
    `all-pairs`) using the existing MeshLink proof app's
    `forceInitiator`/`disableAutoSend`/`benchmarkPayloadBytes` launch extras
    so one device sends a benchmark payload while its partner passively
    receives and confirms receipt. Requires `--payload-bytes`.
  - `crypto`: times X25519 key generation/agreement, Ed25519 key
    generation/sign/verify, and ChaCha20-Poly1305 seal/open on each device
    individually (no pairing needed) via the
    `CryptoRuntimePerformanceDeviceTest` instrumented test in the `meshlink`
    module, so crypto performance can be compared across the fleet's very
    different chipsets (e.g. hardware-accelerated `JcaCryptoProvider` vs. the
    pure-Kotlin `Ed25519FallbackCryptoProvider` on older/lower-end devices).
  - Defaults to a dry run that only prints discovery and the planned
    pairing/device list; pass `--execute` to actually launch the app / run the
    instrumented test and drive real BLE traffic or on-device crypto ops.
  - Narrow scope to specific hardware with `--serials <serial1>,<serial2>,...`
    instead of running against every attached device.
  - `transport` mode captures each run's logcat, `proof.log`, and metadata
    under `meshlink-benchmark/fleet-results/runs/<batch>/<pair>/`; results are
    appended to `meshlink-benchmark/fleet-results/ledger.jsonl` (JSON Lines,
    one record per run) and summarized in
    `meshlink-benchmark/fleet-results/latest-summary.md`.
  - `crypto` mode captures each device's logcat and Gradle output under
    `meshlink-benchmark/fleet-results/crypto-runs/<batch>/<device>/`; results
    are appended to `meshlink-benchmark/fleet-results/crypto-ledger.jsonl` and
    summarized in `meshlink-benchmark/fleet-results/crypto-latest-summary.md`.
  - `meshlink-benchmark/fleet-results/` is gitignored (regenerated evidence,
    not source), so treat the JSONL ledgers as local/CI artifacts, not part of
    the committed history.
  - Examples:
    `python3 meshlink-benchmark/scripts/run_fleet_meshlink_benchmark.py --payload-bytes 65536 --execute`
    `python3 meshlink-benchmark/scripts/run_fleet_meshlink_benchmark.py --mode crypto --execute`

## Refresh commands

- JVM benchmarks: `./gradlew :meshlink-benchmark:jvmBenchmark`
- Verified JVM smoke baselines: `./gradlew verifyJvmSmokeBenchmarks`
- Raw JVM smoke benchmark task: `./gradlew :meshlink-benchmark:jvmSmokeBenchmark`
- Android proof benchmarks: run the instrumented proof benchmarks against physical peers
- iOS proof benchmarks: run the XCTest proof benchmarks plus a physical peer-backed proof-app run
