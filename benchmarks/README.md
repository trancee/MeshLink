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
| LOW-mode 1-hop 256 B delivery | <= 5 s after peer discovery and connection establishment | Spec `SC-006`, Plan `Performance Goals` |
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
| Android LOW-power 256 B delivery, Samsung -> OPPO | 411 ms | Meets the normative LOW-power delivery target | Fresh sender log: `BENCHMARK transport bytes=256 elapsedMs=411 throughputKBps=0.61 result=Sent`; passive OPPO log retained `BENCHMARK receipt peer remapped origin=f48c9d direct=ea85e3` and `BENCHMARK receipt send(ea85e3) -> Sent ... attempt=1`. |
| Android 256 B latency | physical benchmark pass (<= 50 ms) | Meets the normative latency target | Current archived repo state keeps the pass result, but not a durable raw millisecond line. |
| Android 64 KiB throughput, OPPO -> Samsung | 1306.12 KB/s | Meets the normative Android throughput target | Samsung received the full 64 KiB payload. |
| iOS cold start, iPhone 15 | 18 ms | Meets the normative cold-start target | Physical proof log. |
| iOS LOW-power scan duty, iPhone 15 | 5% | Meets the normative LOW-mode duty target | Physical diagnostic log. |
| iOS LOW-power 256 B delivery, iPhone 15 -> OPPO | 239 ms | Meets the normative LOW-power delivery target | Latest headless sender log retained `BENCHMARK transport bytes=256 elapsedMs=239 throughputKBps=1.05 result=Sent`; passive OPPO log retained `BENCHMARK receipt send(9dc685) -> Sent token=0000770594a52dcf attempt=1`. The earlier `21331 ms ReceiptTimeout` repro was traced to passive proof-app peer bookkeeping and is retained in `research.md` as superseded diagnostic evidence. |
| iOS 256 B latency, iPhone 15 -> Samsung | 28 ms | Meets the normative latency target | Physical proof log. |
| iOS 64 KiB throughput, best clean run, iPhone 15 -> Samsung | 19.94 KB/s | `SC-004` remains partially unmet on iOS | Physical proof log: `BENCHMARK transport bytes=65536 elapsedMs=3210 throughputKBps=19.94 result=Sent`. |
| iOS 64 KiB throughput, latest clean OPPO rerun on queued-writer build | 20.05 KB/s | `SC-004` remains partially unmet on iOS | Fresh physical proof log: `BENCHMARK transport bytes=65536 elapsedMs=3192 throughputKBps=20.05 result=Sent`; OPPO receiver logged `MSG from ... bytes=65536`. |
| iOS 64 KiB passive Samsung rerun on queued-writer build | `NotSent(UNREACHABLE)` after 15012 ms | `SC-004` remains partially unmet on iOS; Samsung-path stability remains variable | Fresh physical proof log: `BENCHMARK transport bytes=65536 elapsedMs=15012 throughputKBps=4.26 result=NotSent(reason=UNREACHABLE)` after repeated `HOP_SESSION_FAILED stage=transport.handshake.message1.send`. |
| iOS 64 KiB telemetry diagnostic run, iPhone 15 -> OPPO | no terminal benchmark line | Historical retained evidence only; pre-queued-writer telemetry rerun disconnected before completion | Post-T047 telemetry-enabled run reached iPhone `write.frame seq=185` / `read.frame seq=5` before `Peer lost`. |
| iOS 64 KiB telemetry diagnostic run, iPhone 15 -> Samsung on queued-writer build | 19.62 KB/s | `SC-004` remains partially unmet on iOS | Fresh telemetry proof log: `BENCHMARK transport bytes=65536 elapsedMs=3262 throughputKBps=19.62 result=Sent`; Samsung receiver logged `MSG from ... bytes=65536`. Telemetry batches reached `coalescedFrames=16`, `coalescedBytes=8144`, `backpressureSpins=94..246`, `readyFalseCount=94..246`, and `maxInterWriteGapMs=61..146`. |
| iOS 64 KiB conformance-path rerun, iPhone 15 -> OPPO, transient appId, widened 16 KiB inner batch experiment | 18.37 KB/s | `SC-004` remains partially unmet on iOS | Fresh sender proof log: `BENCHMARK transport bytes=65536 elapsedMs=3484 throughputKBps=18.37 result=Sent`; passive OPPO proof log retained `MSG from ... bytes=65536 benchmarkToken=000087152badcdc3` plus `BENCHMARK receipt send(2e0aa2) -> Sent token=000087152badcdc3 attempt=1`. Telemetry showed `writeBatches=1` and `maxWriteBatchBytes=8144` for the coalesced 8,144-byte writes, but throughput worsened relative to the earlier clean OPPO rerun, so the 16 KiB inner-batch experiment was reverted. |
| iOS Samsung follow-up transport/inline tuning mini-matrix (run-loop scheduling, shorter sender ACK settlement, 32-frame coalescing, 64 KiB inline path, and inline path + 16 KiB inner batch) | 23.05-31.84 KB/s | `SC-004` remains partially unmet on iOS | Fresh recipient-confirmed Samsung reruns landed at `27.33`, `24.90`, `28.33`, `31.84`, and `23.05 KB/s`; none exceeded the current best-case Samsung baseline of `33.56 KB/s`. The re-enabled inline-path telemetry still showed one `coalescedBytes=66037` write requiring `writeCalls=28`, `writeBatches=17`, `readyFalseCount=1100`, `maxInterWriteGapMs=190`, and `totalElapsedMs=1471`, which confirms CoreBluetooth backpressure remains the dominant limit even after removing transfer-chunk ACK traffic from the large-payload path. |
| iOS Samsung incoming-channel low-latency design change, recipient-confirmed series | 18.16-38.28 KB/s | `SC-004` remains partially unmet on iOS, but Samsung best-case improved | A more invasive iOS-only design change now requests `CBPeripheralManagerConnectionLatencyLow` on inbound L2CAP channels when the first write starts. In the retained Samsung series, runs that logged `requested low connection latency ...` reached `38.28 KB/s` and `36.14 KB/s`; a same-design run that never emitted the request because the iPhone took the initiating/central role fell back to `18.16 KB/s`. Fresh post-install refresh evidence kept the same split: `34.33 KB/s` with the low-latency request vs `19.85 KB/s` without it. |
| iOS Samsung low-latency + 64 KiB inline-path combination | 20.68-33.97 KB/s | Rejected follow-up; still below the chunked + low-latency best case | A temporary combination of the inbound low-latency request with the re-enabled 64 KiB inline MeshLink path did not outperform the chunked low-latency design. The only run that emitted the low-latency request reached `33.97 KB/s`, while the other inline runs without the request measured `23.78 KB/s` and `20.68 KB/s`, so the inline path remained rejected. |
| iOS 256 B recipient-confirmed MeshLink rerun, iPhone 15 -> Samsung | `ReceiptTimeout` after 21296 ms | Supporting evidence only; recipient-confirmed completion failed | Fresh proof log: `BENCHMARK transport bytes=256 elapsedMs=21296 throughputKBps=0.01 result=ReceiptTimeout`; passive Samsung proof log retained `BENCHMARK receipt send(b11305) -> NotSent(reason=UNREACHABLE) token=000065f04a5217b1`, which shows the peer tried to return the proof receipt but the return path expired. A later token-correlated minimal repro retained sender token `000067243710210d` with sender-side recent diagnostics still showing `DELIVERY_SUCCEEDED` and `HOP_SESSION_ESTABLISHED`, while the passive Samsung side retained `Peer lost: 327782fbdda5d37776143c2d`, `BENCHMARK receipt send(fb41c7) -> NotSent(reason=UNREACHABLE) token=000067243710210d`, and a token-correlated passive diagnostic summary containing `HOP_SESSION_FAILED stage=transport.handshake.message1.send`. |
| iOS 64 KiB recipient-confirmed MeshLink rerun, iPhone 15 -> Samsung | `ReceiptTimeout` after 21402 ms | Supporting evidence only; recipient-confirmed completion failed | Fresh proof log: `BENCHMARK transport bytes=65536 elapsedMs=21402 throughputKBps=2.99 result=ReceiptTimeout`; passive Samsung proof log retained `BENCHMARK receipt send(fcbf19) -> NotSent(reason=UNREACHABLE) token=000065f7e1569e9e`. |
| iOS 256 B recipient-confirmed MeshLink rerun, iPhone 15 -> OPPO | `ReceiptTimeout` after 21358 ms | Supporting evidence only; recipient-confirmed completion failed | Fresh proof log: `BENCHMARK transport bytes=256 elapsedMs=21358 throughputKBps=0.01 result=ReceiptTimeout`; passive OPPO proof log retained `BENCHMARK receipt send(262742) -> NotSent(reason=UNREACHABLE) token=000065ff936e78d5`. |
| iOS 64 KiB recipient-confirmed MeshLink rerun, iPhone 15 -> OPPO | `ReceiptTimeout` after 21428 ms | Supporting evidence only; recipient-confirmed completion failed | Fresh proof log: `BENCHMARK transport bytes=65536 elapsedMs=21428 throughputKBps=2.99 result=ReceiptTimeout`; passive OPPO proof log retained `BENCHMARK receipt send(a9ccef) -> NotSent(reason=UNREACHABLE) token=00006607417725e7`. |
| iOS 64 KiB recipient-confirmed MeshLink stabilization series, iPhone 15 -> OPPO (post queue-flush remediation, 5 runs) | 14.50-17.09 KB/s, avg 15.57 KB/s, 5/5 `Sent` | Recipient-confirmed proof completion restored on OPPO; `SC-004` throughput remains unmet | Retained run directory `/tmp/oppo_flush_queue_series_20260514T112028`; sender runs ended `BENCHMARK transport bytes=65536 ... result=Sent`, and passive OPPO proof logs retained matching `BENCHMARK receipt send(...) -> Sent ... attempt=1` lines for all five tokens. |
| iOS 64 KiB recipient-confirmed MeshLink stabilization series, iPhone 15 -> Samsung (post queue-flush remediation, 5 runs) | 27.85-33.56 KB/s, avg 30.64 KB/s, 5/5 `Sent` | Recipient-confirmed proof completion restored on Samsung; `SC-004` throughput remains unmet | Retained run directory `/tmp/samsung_flush_queue_series_20260514T112327`; sender runs ended `BENCHMARK transport bytes=65536 ... result=Sent`, and passive Samsung proof logs retained matching `BENCHMARK receipt send(...) -> Sent ... attempt=1` lines for all five tokens. |
| iOS 256 B headless reverse-path rerun, iPhone 15 -> Samsung | `ReceiptTimeout` after 21363 ms | Supporting evidence only; passive Samsung still abandoned the proof receipt after losing the sender, while the sender still retained `knownPeers=[a5495f]` and `routeState=available` at timeout | Fresh headless sender log retained token `000074048e0c137a`, `BENCHMARK transport bytes=256 elapsedMs=21363 throughputKBps=0.01 result=ReceiptTimeout`, and sender `recentDiags` / `routeTimeline` entries containing `DELIVERY_SUCCEEDED`, `HOP_SESSION_ESTABLISHED`, and `ROUTE_DISCOVERED`; passive Samsung proof log retained `Peer lost: b7b286a92d32d218758b6e34`, `DIAG ROUTE_EXPIRED stage=transport.peerLost.routeExpired`, repeated `passive.receipt.wait ... knownPeers=[]`, and `BENCHMARK receipt abandoned token=000074048e0c137a ... deadlineMs=18000`. |
| iOS 256 B headless reverse-path rerun, iPhone 15 -> OPPO | `ReceiptTimeout` after 21356 ms | Supporting evidence only; both sender and passive OPPO timelines now show route expiry / peer loss before receipt abandonment | Fresh headless sender log retained token `0000741524b4f6cb`, `BENCHMARK transport bytes=256 elapsedMs=21356 throughputKBps=0.01 result=ReceiptTimeout`, `DIAG ROUTE_EXPIRED`, and `Peer lost: 2dfbea3408c4c93e47add166` before the sender result line; passive OPPO proof log retained `Peer lost: 04b8e07748cdec6b33459367`, `DIAG ROUTE_EXPIRED stage=transport.peerLost.routeExpired`, repeated `passive.receipt.wait ... knownPeers=[]`, and `BENCHMARK receipt abandoned token=0000741524b4f6cb ... deadlineMs=18000`. |
| iOS 64 KiB proof-only GATT prototype, iPhone 15 -> Samsung | 23.92 KB/s | Supporting evidence only; feasible fallback prototype but still below the normative iOS target | Fresh physical proof log: `BENCHMARK transport bytes=65536 elapsedMs=2676 throughputKBps=23.92 result=Sent mode=gattPrototype setupMs=1994`; iPhone reported `maxWriteWithoutResponse=512`, Samsung logged `mtu=517`, and the Android proof app emitted a full 64 KiB receipt notification. |
| iOS 64 KiB proof-only GATT prototype, iPhone 15 -> OPPO | 21.96 KB/s | Supporting evidence only; same throughput class as current iOS L2CAP evidence | Fresh physical proof log: `BENCHMARK transport bytes=65536 elapsedMs=2915 throughputKBps=21.96 result=Sent mode=gattPrototype setupMs=1926`; iPhone reported `maxWriteWithoutResponse=512`, OPPO logged `mtu=517`, and the Android proof app emitted a full 64 KiB receipt notification. |
| Direct-link pairing requirement, OPPO + iPhone final state | no pairing dialog observed | Supporting evidence only; not a standalone success criterion | Latest OPPO logcat did not show `PAIRING_REQUEST` or `BluetoothPairingDialog`. |

## Current release-decision posture

`SC-004` remains partially unmet on iOS. The current post-remediation recipient-confirmed 64 KiB series restored clean physical proof completion on both reference peers—OPPO finished 5/5 at `14.50-17.09 KB/s` (avg `15.57 KB/s`) and Samsung finished 5/5 at `27.85-33.56 KB/s` (avg `30.64 KB/s`)—and a later iOS-only incoming-channel low-latency design change lifted the retained Samsung best case to `38.28 KB/s` (with a fresh post-install refresh at `34.33 KB/s`). Even so, those runs still remain far below the normative `>= 60 KB/s` iOS throughput target. The earlier 2026-05-12 recipient-confirmed failures are therefore retained as superseded diagnostic evidence, not as the current blocker state. A proof-only native GATT fallback prototype remains supporting evidence only (`23.92 KB/s` to Samsung, `21.96 KB/s` to OPPO) and still does not change the blocker posture. Explicit LOW-power `SC-006` coverage now exists and the latest iPhone 15 -> OPPO LOW-mode sender rerun passed in `239 ms` once the passive Android proof app restored the direct peer handle from the inbound benchmark payload. That means the remaining blocker is now iPhone large-transfer throughput on the MeshLink path, plus the role-dependent inability to request the improved low-latency connection profile on every direct run. Unless an explicit waiver narrows the public iOS large-transfer claim, release remains blocked on the iOS portion of `SC-004`.

## Current learnings

- MeshLink direct L2CAP links should remain unpaired; the app-layer Noise and TOFU model already provides the intended security boundary.
- Android API 36 behaved best when explicit insecure LE socket settings were preferred first and legacy insecure LE CoC APIs were kept as fallback for older releases.
- iOS must honor the deterministic initiator tie-break. When iOS loses the key-hash ordering decision, it should wait for the inbound L2CAP channel instead of forcing a competing outbound reconnect.
- Disabling the iOS large-inline send path improved large-payload reliability, but it did not solve throughput.
- The queued-writer follow-up (`c45b7dc`) did not materially change the iPhone 15 throughput class: the latest clean OPPO rerun reached `20.05 KB/s` and the latest telemetry-enabled Samsung rerun reached `19.62 KB/s`, still roughly 3× below the `>= 60 KB/s` target.
- A later bounded conformance-path experiment widened the iOS inner write-batch size to `16 KiB` while keeping coalesced writes at `8144` bytes. That removed the extra inner batch boundary (`writeBatches=1` instead of `2`) but still measured only `18.37 KB/s` on a fresh iPhone 15 -> OPPO transient-appId rerun, so the change was reverted. This strengthens the earlier conclusion that CoreBluetooth stream availability / OS scheduling dominates more than the inner 4 KiB batching boundary itself.
- Additional 2026-05-14 Samsung follow-up reruns also failed to beat the existing `33.56 KB/s` best case. Stream run-loop scheduling (`27.33 KB/s`), shorter sender ACK-settlement timers (`24.90 KB/s`), a 32-frame transport coalescing window (`28.33 KB/s`), a re-enabled 64 KiB inline path (`31.84 KB/s`), and the same inline path with a 16 KiB inner batch (`23.05 KB/s`) all remained below the then-current best retained Samsung series. The strongest negative evidence came from the inline path: even a single `66037`-byte coalesced write still spent `1471 ms` in the CoreBluetooth output loop with `readyFalseCount=1100`, so the blocker is no longer just transfer-chunk bookkeeping.
- A more invasive iOS-only follow-up that requests `CBPeripheralManagerConnectionLatencyLow` on inbound L2CAP channels did improve the Samsung best case to `38.28 KB/s`, with a fresh final-code refresh still reaching `34.33 KB/s` on a run that emitted the same request. However, same-design runs where the iPhone took the initiating/central role never emitted that request and fell back to `18.16-19.85 KB/s`. The remaining blocker is therefore both throughput and role-dependent connection-parameter control.
- Combining that low-latency request with the re-enabled 64 KiB inline MeshLink path did not help: the best such run reached only `33.97 KB/s`, so the inline path remains rejected even after the connection-latency change.
- The fresh telemetry-enabled Samsung rerun no longer stalls before a terminal benchmark line, which suggests the queued writer keeps the app-side pipeline fed. However, the recorded `coalescedFrames=16`, `coalescedBytes=8144`, `backpressureSpins=94..246`, `readyFalseCount=94..246`, and `maxInterWriteGapMs=61..146` still point to CoreBluetooth stream availability / OS scheduling as the dominant bottleneck.
- A proof-only native GATT fallback prototype is feasible on the reference hardware: iPhone 15 negotiated `maxWriteWithoutResponse=512`, Android servers negotiated `mtu=517`, and the prototype delivered full 64 KiB receipts to Samsung (`23.92 KB/s`) and OPPO (`21.96 KB/s`). That is a modest improvement over the clean Samsung L2CAP evidence but still far below the normative `>= 60 KB/s` iOS target, so it does not justify pivoting the blocker claim to GATT.
- The first Android advertiser attempt used GATT service data for app-id isolation and exceeded the legacy advertising budget (`ADVERTISE_FAILED_DATA_TOO_LARGE`); switching the proof-only discovery marker to manufacturer data fixed discovery on the physical rerun.
- The fresh recipient-confirmed MeshLink matrix now makes the return-path weakness explicit: Samsung/OPPO × 256 B/64 KiB all ended `ReceiptTimeout`, and each passive Android peer retained a matching `BENCHMARK receipt send(...) -> NotSent(reason=UNREACHABLE)` line. The benchmark payload therefore reached the passive peer often enough to trigger receipt logic, but the proof receipt could not make the trip back to the iPhone before expiry.
- A token-correlated Samsung 256-byte minimal repro sharpened the same conclusion: the sender-side correlation log still retained recent `DELIVERY_SUCCEEDED` / `HOP_SESSION_ESTABLISHED` context for token `000067243710210d`, while the passive side retained `Peer lost`, `BENCHMARK receipt send(...) -> NotSent(reason=UNREACHABLE)`, and `HOP_SESSION_FAILED stage=transport.handshake.message1.send` in the token-correlated diagnostic summary. That points more strongly to a return-path session / route collapse after payload receipt than to a sender-side benchmark-format bug.
- A bounded passive receipt retry helper changed the Samsung 256-byte failure shape but did not resolve it: in the post-remediation matrix the passive Samsung proof app no longer burned one long `UNREACHABLE` send attempt, but instead retained repeated `passive.receipt.wait ... knownPeers=[]` correlation lines followed by `BENCHMARK receipt abandoned ... deadlineMs=18000`. Three other post-remediation matrix cells (Samsung 64 KiB, OPPO 256 B, OPPO 64 KiB) failed earlier as sender-side `NotSent(reason=UNREACHABLE)`. The helper therefore improved diagnosis, not conformance.
- Fresh headless 256-byte reruns on 2026-05-13 sharpened the Samsung-vs-OPPO comparison: Samsung still timed out while the sender retained `knownPeers=[a5495f]` and `routeState=available`, but the passive Samsung peer had already logged `Peer lost`, `ROUTE_EXPIRED`, and `receipt abandoned`; OPPO was more symmetric, with both sender and passive logs retaining `ROUTE_EXPIRED` / `Peer lost` before the passive receipt window expired. That points to a shared post-payload peer-reappearance failure, plus an extra stale-route / stale-presence asymmetry on the Samsung path.
- A separate fresh passive Samsung rerun still failed `NotSent(UNREACHABLE)` after repeated `transport.handshake.message1.send` failures, so large-transfer stability remains variable on the Samsung reference path.
- The proof apps' 1-hop benchmark receipt path must map the full `originPeerId`
  back to the discovered direct peer handle when that handle is only the
  12-byte advertisement key-hash prefix. Without that remap, Android LOW-power
  proof runs can fail on proof-app receipt addressing before they exercise the
  actual transport path.
- Passive Android proof peers must also retain that resolved direct peer handle
  in benchmark bookkeeping when an inbound benchmark payload arrives. Otherwise
  the proof app can clear `knownPeers` on scan/presence churn, receive the
  payload over a usable inbound L2CAP link, and still suppress the proof receipt
  as if no peer were available.
- After the proof benchmarks switched to recipient-confirmed semantics, the
  benchmark harnesses needed a longer observation window so failing runs surface
  a scored `BENCHMARK transport ... result=...` line instead of a raw harness
  timeout.
- The recipient-confirmed 64 KiB proof-completion blocker is now closed on the current reference path: the post-remediation OPPO series finished 5/5 at `14.50-17.09 KB/s`, and the post-remediation Samsung series finished 5/5 at `27.85-33.56 KB/s`.
- The remaining physical performance blocker is iPhone 15 large-transfer throughput, not discovery, trust pinning, pairing, or recipient-confirmed completion stability on the current MeshLink path.

## Refresh commands

- JVM benchmarks: `./gradlew :benchmarks:jvmBenchmark`
- JVM smoke baselines: `./gradlew :benchmarks:jvmSmokeBenchmark`
- Android proof benchmarks: run the instrumented proof benchmarks against physical peers
- iOS proof benchmarks: run the XCTest proof benchmarks plus a physical peer-backed proof-app run
