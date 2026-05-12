# MeshLink Benchmark and Validation Baselines

This reference records the latest benchmark evidence retained in the repository and the latest physical proof-app measurements that have materially affected implementation decisions. A failing row is still a baseline; it marks the current blocker rather than disappearing from the record.

## JVM baselines (2026-05-11)

| Metric | Latest result | Target | Status |
|---|---:|---:|---|
| Convergence, 10-node topology change | 0.344 ms/op | < 3 s | ✓ met |
| AEAD encrypt | 0.914 us/op | regression-tracked | recorded |
| AEAD decrypt | 0.983 us/op | regression-tracked | recorded |
| X25519 agreement | 63.285 us/op | regression-tracked | recorded |
| Routing lookup, 64 routes | 0.003107 us/op | regression-tracked | recorded |
| Routing lookup, 256 routes | 0.003285 us/op | regression-tracked | recorded |
| Routing lookup, 1024 routes | 0.002998 us/op | regression-tracked | recorded |
| Wire encode message | 0.269 us/op | < 1 us/op | ✓ met |
| Wire decode message | 0.083 us/op | < 1 us/op | ✓ met |
| Wire encode transfer chunk | 0.210 us/op | < 1 us/op | ✓ met |
| Wire decode transfer chunk | 0.083 us/op | < 1 us/op | ✓ met |
| 8-peer establish benchmark smoke | 262.995 ms/op | regression-tracked | recorded |
| 8-peer steady-state memory budget | under 8 MiB cap | <= 8 MiB | ✓ met |

The steady-state memory test currently enforces the retained-heap budget but does not yet persist the measured byte count as a durable artifact.

## Physical mobile baselines

| Metric | Latest evidence | Target | Status | Notes |
|---|---:|---:|---|---|
| Android cold start | physical benchmark pass (< 500 ms) | < 500 ms | ✓ met | Current archived repo state keeps the pass result, but not a durable raw millisecond line. |
| Android LOW-power scan duty | 5% | <= 5% | ✓ met | Verified from `POWER_MODE_CHANGED` diagnostic metadata. |
| Android 256 B latency | physical benchmark pass (<= 50 ms) | <= 50 ms | ✓ met | Current archived repo state keeps the pass result, but not a durable raw millisecond line. |
| Android 64 KiB throughput, OPPO -> Samsung | 1306.12 KB/s | >= 80 KB/s | ✓ met | Samsung received the full 64 KiB payload. |
| iOS cold start, iPhone 15 | 18 ms | < 500 ms | ✓ met | Physical proof log. |
| iOS LOW-power scan duty, iPhone 15 | 5% | <= 5% | ✓ met | Physical diagnostic log. |
| iOS 256 B latency, iPhone 15 -> Samsung | 28 ms | <= 50 ms | ✓ met | Physical proof log. |
| iOS 64 KiB throughput, best successful run, iPhone 15 -> Samsung | 19.94 KB/s | >= 60 KB/s | ✗ blocked | Physical proof log: `BENCHMARK transport bytes=65536 elapsedMs=3210 throughputKBps=19.94 result=Sent`. |
| iOS 64 KiB throughput, latest clean OPPO baseline, iPhone 15 -> OPPO | 16.79 KB/s | >= 60 KB/s | ✗ blocked | Last clean OPPO comparison point before the T047 follow-up diagnostics. |
| iOS 64 KiB telemetry diagnostic run, iPhone 15 -> OPPO | no terminal benchmark line | >= 60 KB/s | ✗ blocked | Post-T047 telemetry-enabled run reached iPhone `write.frame seq=185` / `read.frame seq=5` before `Peer lost`. |
| iOS 64 KiB telemetry diagnostic run, iPhone 15 -> Samsung | no terminal benchmark line | >= 60 KB/s | ✗ blocked | Post-T047 telemetry-enabled run reached iPhone `write.frame seq=190` / `read.frame seq=5`; Samsung saw peer connect/disconnect but no `MSG ... bytes=65536`. |
| Direct-link pairing requirement, OPPO + iPhone final state | no pairing dialog observed | pairing not required | ✓ met | Latest OPPO logcat did not show `PAIRING_REQUEST` or `BluetoothPairingDialog`. |

## Current learnings

- MeshLink direct L2CAP links should remain unpaired; the app-layer Noise and TOFU model already provides the intended security boundary.
- Android API 36 behaved best when explicit insecure LE socket settings were preferred first and legacy insecure LE CoC APIs were kept as fallback for older releases.
- iOS must honor the deterministic initiator tie-break. When iOS loses the key-hash ordering decision, it should wait for the inbound L2CAP channel instead of forcing a competing outbound reconnect.
- Disabling the iOS large-inline send path improved large-payload reliability, but it did not solve throughput.
- The bounded T047 stream-drain and write-batching remediation improved the best clean Samsung rerun only slightly, to `19.94 KB/s`, which still misses the `>= 60 KB/s` target by roughly 3×.
- Telemetry-enabled post-T047 reruns on both OPPO and Samsung still failed to emit a terminal benchmark line; both peers disconnected mid-transfer before the iPhone produced a final throughput result.
- The remaining physical performance blocker is iPhone 15 large-transfer throughput and stability, not discovery, trust pinning, or pairing.

## Refresh commands

- JVM benchmarks: `./gradlew :benchmarks:jvmBenchmark`
- JVM smoke baselines: `./gradlew :benchmarks:jvmSmokeBenchmark`
- Android proof benchmarks: run the instrumented proof benchmarks against physical peers
- iOS proof benchmarks: run the XCTest proof benchmarks plus a physical peer-backed proof-app run
