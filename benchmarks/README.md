# MeshLink Benchmark and Validation Baselines

This reference retains observed benchmark and proof-app evidence. The
normative acceptance thresholds live in `specs/001-ble-mesh-sdk/spec.md`
(Success Criteria) and are mirrored for implementation planning in
`specs/001-ble-mesh-sdk/plan.md` (Performance Goals). A failing row here is
still valuable evidence, but it does not relax the underlying requirement.

## Normative acceptance thresholds (mirror only)

| Criterion | Normative threshold | Canonical source |
|---|---:|---|
| 1-hop 256 B message latency | <= 50 ms p95 after connection establishment | Spec `SC-004`, Plan `Performance Goals` |
| 1-hop 64 KiB Android throughput | >= 80 KB/s | Spec `SC-004`, Plan `Performance Goals` |
| 1-hop 64 KiB iOS throughput | >= 60 KB/s | Spec `SC-004`, Plan `Performance Goals` |
| 8-peer steady-state heap | <= 8 MiB | Spec `SC-005`, Plan `Performance Goals` |
| LOW-mode scan duty | <= 5% | Spec `SC-006`, Plan `Performance Goals` |
| Cold start to first advertisement | < 500 ms | Plan `Performance Goals` |
| 10-node route convergence | < 3 s | Spec `SC-002`, Plan `Performance Goals` |
| JVM wire encode/decode | < 1 us/op | Plan `Performance Goals` |

Rows not listed above are retained as regression-tracked evidence only.

## Latest retained evidence

### JVM evidence (2026-05-11)

| Metric | Latest result | Interpretation |
|---|---:|---|
| Convergence, 10-node topology change | 0.344 ms/op | Meets the normative < 3 s route-convergence target |
| AEAD encrypt | 0.914 us/op | Regression-tracked evidence only |
| AEAD decrypt | 0.983 us/op | Regression-tracked evidence only |
| X25519 agreement | 63.285 us/op | Regression-tracked evidence only |
| Routing lookup, 64 routes | 0.003107 us/op | Regression-tracked evidence only |
| Routing lookup, 256 routes | 0.003285 us/op | Regression-tracked evidence only |
| Routing lookup, 1024 routes | 0.002998 us/op | Regression-tracked evidence only |
| Wire encode message | 0.269 us/op | Meets the normative < 1 us/op codec target |
| Wire decode message | 0.083 us/op | Meets the normative < 1 us/op codec target |
| Wire encode transfer chunk | 0.210 us/op | Meets the normative < 1 us/op codec target |
| Wire decode transfer chunk | 0.083 us/op | Meets the normative < 1 us/op codec target |
| 8-peer establish benchmark smoke | 262.995 ms/op | Regression-tracked evidence only |
| 8-peer steady-state memory budget | under 8 MiB cap | Meets the normative <= 8 MiB memory target |

The steady-state memory test currently enforces the retained-heap budget but does not yet persist the measured byte count as a durable artifact.

### Physical mobile evidence

| Metric | Latest evidence | Interpretation | Notes |
|---|---:|---|---|
| Android cold start | physical benchmark pass (< 500 ms) | Meets the normative cold-start target | Current archived repo state keeps the pass result, but not a durable raw millisecond line. |
| Android LOW-power scan duty | 5% | Meets the normative LOW-mode duty target | Verified from `POWER_MODE_CHANGED` diagnostic metadata. |
| Android 256 B latency | physical benchmark pass (<= 50 ms) | Meets the normative latency target | Current archived repo state keeps the pass result, but not a durable raw millisecond line. |
| Android 64 KiB throughput, OPPO -> Samsung | 1306.12 KB/s | Meets the normative Android throughput target | Samsung received the full 64 KiB payload. |
| iOS cold start, iPhone 15 | 18 ms | Meets the normative cold-start target | Physical proof log. |
| iOS LOW-power scan duty, iPhone 15 | 5% | Meets the normative LOW-mode duty target | Physical diagnostic log. |
| iOS 256 B latency, iPhone 15 -> Samsung | 28 ms | Meets the normative latency target | Physical proof log. |
| iOS 64 KiB throughput, best clean run, iPhone 15 -> Samsung | 19.94 KB/s | `SC-004` remains partially unmet on iOS | Physical proof log: `BENCHMARK transport bytes=65536 elapsedMs=3210 throughputKBps=19.94 result=Sent`. |
| iOS 64 KiB throughput, latest clean OPPO comparison baseline | 16.79 KB/s | `SC-004` remains partially unmet on iOS | Last clean OPPO comparison point before the T047 follow-up diagnostics. |
| iOS 64 KiB telemetry diagnostic run, iPhone 15 -> OPPO | no terminal benchmark line | `SC-004` remains partially unmet on iOS; telemetry rerun disconnected before completion | Post-T047 telemetry-enabled run reached iPhone `write.frame seq=185` / `read.frame seq=5` before `Peer lost`. |
| iOS 64 KiB telemetry diagnostic run, iPhone 15 -> Samsung | no terminal benchmark line | `SC-004` remains partially unmet on iOS; telemetry rerun disconnected before completion | Post-T047 telemetry-enabled run reached iPhone `write.frame seq=190` / `read.frame seq=5`; Samsung saw peer connect/disconnect but no `MSG ... bytes=65536`. |
| Direct-link pairing requirement, OPPO + iPhone final state | no pairing dialog observed | Supporting evidence only; not a standalone success criterion | Latest OPPO logcat did not show `PAIRING_REQUEST` or `BluetoothPairingDialog`. |

## Current release-decision posture

`SC-004` remains partially unmet on iOS. The best clean retained reference-hardware run on iPhone 15 -> Samsung reached `19.94 KB/s`, below the required `>= 60 KB/s`, and telemetry-enabled reruns to both Samsung and OPPO disconnected before a terminal benchmark line was emitted. Unless an explicit waiver narrows the public iOS large-transfer claim, this keeps release blocked on the iOS throughput portion of `SC-004`.

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
