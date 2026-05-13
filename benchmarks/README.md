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
| iOS LOW-power 256 B delivery, iPhone 15 -> OPPO | `ReceiptTimeout` after 21331 ms | `SC-006` is now explicitly unmet on the iOS proof integration | Fresh headless sender log retained `BENCHMARK transport bytes=256 elapsedMs=21331 throughputKBps=0.01 result=ReceiptTimeout` after `auto-send attempt 1 -> Sent`; passive OPPO log retained `Peer lost: db2ccaf6228459d8aa9dc685`, repeated `passive.receipt.wait ... knownPeers=[]`, and `BENCHMARK receipt abandoned token=00007689200a0c25 ... deadlineMs=18000`. |
| iOS 256 B latency, iPhone 15 -> Samsung | 28 ms | Meets the normative latency target | Physical proof log. |
| iOS 64 KiB throughput, best clean run, iPhone 15 -> Samsung | 19.94 KB/s | `SC-004` remains partially unmet on iOS | Physical proof log: `BENCHMARK transport bytes=65536 elapsedMs=3210 throughputKBps=19.94 result=Sent`. |
| iOS 64 KiB throughput, latest clean OPPO rerun on queued-writer build | 20.05 KB/s | `SC-004` remains partially unmet on iOS | Fresh physical proof log: `BENCHMARK transport bytes=65536 elapsedMs=3192 throughputKBps=20.05 result=Sent`; OPPO receiver logged `MSG from ... bytes=65536`. |
| iOS 64 KiB passive Samsung rerun on queued-writer build | `NotSent(UNREACHABLE)` after 15012 ms | `SC-004` remains partially unmet on iOS; Samsung-path stability remains variable | Fresh physical proof log: `BENCHMARK transport bytes=65536 elapsedMs=15012 throughputKBps=4.26 result=NotSent(reason=UNREACHABLE)` after repeated `HOP_SESSION_FAILED stage=transport.handshake.message1.send`. |
| iOS 64 KiB telemetry diagnostic run, iPhone 15 -> OPPO | no terminal benchmark line | Historical retained evidence only; pre-queued-writer telemetry rerun disconnected before completion | Post-T047 telemetry-enabled run reached iPhone `write.frame seq=185` / `read.frame seq=5` before `Peer lost`. |
| iOS 64 KiB telemetry diagnostic run, iPhone 15 -> Samsung on queued-writer build | 19.62 KB/s | `SC-004` remains partially unmet on iOS | Fresh telemetry proof log: `BENCHMARK transport bytes=65536 elapsedMs=3262 throughputKBps=19.62 result=Sent`; Samsung receiver logged `MSG from ... bytes=65536`. Telemetry batches reached `coalescedFrames=16`, `coalescedBytes=8144`, `backpressureSpins=94..246`, `readyFalseCount=94..246`, and `maxInterWriteGapMs=61..146`. |
| iOS 256 B recipient-confirmed MeshLink rerun, iPhone 15 -> Samsung | `ReceiptTimeout` after 21296 ms | Supporting evidence only; recipient-confirmed completion failed | Fresh proof log: `BENCHMARK transport bytes=256 elapsedMs=21296 throughputKBps=0.01 result=ReceiptTimeout`; passive Samsung proof log retained `BENCHMARK receipt send(b11305) -> NotSent(reason=UNREACHABLE) token=000065f04a5217b1`, which shows the peer tried to return the proof receipt but the return path expired. A later token-correlated minimal repro retained sender token `000067243710210d` with sender-side recent diagnostics still showing `DELIVERY_SUCCEEDED` and `HOP_SESSION_ESTABLISHED`, while the passive Samsung side retained `Peer lost: 327782fbdda5d37776143c2d`, `BENCHMARK receipt send(fb41c7) -> NotSent(reason=UNREACHABLE) token=000067243710210d`, and a token-correlated passive diagnostic summary containing `HOP_SESSION_FAILED stage=transport.handshake.message1.send`. |
| iOS 64 KiB recipient-confirmed MeshLink rerun, iPhone 15 -> Samsung | `ReceiptTimeout` after 21402 ms | Supporting evidence only; recipient-confirmed completion failed | Fresh proof log: `BENCHMARK transport bytes=65536 elapsedMs=21402 throughputKBps=2.99 result=ReceiptTimeout`; passive Samsung proof log retained `BENCHMARK receipt send(fcbf19) -> NotSent(reason=UNREACHABLE) token=000065f7e1569e9e`. |
| iOS 256 B recipient-confirmed MeshLink rerun, iPhone 15 -> OPPO | `ReceiptTimeout` after 21358 ms | Supporting evidence only; recipient-confirmed completion failed | Fresh proof log: `BENCHMARK transport bytes=256 elapsedMs=21358 throughputKBps=0.01 result=ReceiptTimeout`; passive OPPO proof log retained `BENCHMARK receipt send(262742) -> NotSent(reason=UNREACHABLE) token=000065ff936e78d5`. |
| iOS 64 KiB recipient-confirmed MeshLink rerun, iPhone 15 -> OPPO | `ReceiptTimeout` after 21428 ms | Supporting evidence only; recipient-confirmed completion failed | Fresh proof log: `BENCHMARK transport bytes=65536 elapsedMs=21428 throughputKBps=2.99 result=ReceiptTimeout`; passive OPPO proof log retained `BENCHMARK receipt send(a9ccef) -> NotSent(reason=UNREACHABLE) token=00006607417725e7`. |
| iOS 256 B headless reverse-path rerun, iPhone 15 -> Samsung | `ReceiptTimeout` after 21363 ms | Supporting evidence only; passive Samsung still abandoned the proof receipt after losing the sender, while the sender still retained `knownPeers=[a5495f]` and `routeState=available` at timeout | Fresh headless sender log retained token `000074048e0c137a`, `BENCHMARK transport bytes=256 elapsedMs=21363 throughputKBps=0.01 result=ReceiptTimeout`, and sender `recentDiags` / `routeTimeline` entries containing `DELIVERY_SUCCEEDED`, `HOP_SESSION_ESTABLISHED`, and `ROUTE_DISCOVERED`; passive Samsung proof log retained `Peer lost: b7b286a92d32d218758b6e34`, `DIAG ROUTE_EXPIRED stage=transport.peerLost.routeExpired`, repeated `passive.receipt.wait ... knownPeers=[]`, and `BENCHMARK receipt abandoned token=000074048e0c137a ... deadlineMs=18000`. |
| iOS 256 B headless reverse-path rerun, iPhone 15 -> OPPO | `ReceiptTimeout` after 21356 ms | Supporting evidence only; both sender and passive OPPO timelines now show route expiry / peer loss before receipt abandonment | Fresh headless sender log retained token `0000741524b4f6cb`, `BENCHMARK transport bytes=256 elapsedMs=21356 throughputKBps=0.01 result=ReceiptTimeout`, `DIAG ROUTE_EXPIRED`, and `Peer lost: 2dfbea3408c4c93e47add166` before the sender result line; passive OPPO proof log retained `Peer lost: 04b8e07748cdec6b33459367`, `DIAG ROUTE_EXPIRED stage=transport.peerLost.routeExpired`, repeated `passive.receipt.wait ... knownPeers=[]`, and `BENCHMARK receipt abandoned token=0000741524b4f6cb ... deadlineMs=18000`. |
| iOS 64 KiB proof-only GATT prototype, iPhone 15 -> Samsung | 23.92 KB/s | Supporting evidence only; feasible fallback prototype but still below the normative iOS target | Fresh physical proof log: `BENCHMARK transport bytes=65536 elapsedMs=2676 throughputKBps=23.92 result=Sent mode=gattPrototype setupMs=1994`; iPhone reported `maxWriteWithoutResponse=512`, Samsung logged `mtu=517`, and the Android proof app emitted a full 64 KiB receipt notification. |
| iOS 64 KiB proof-only GATT prototype, iPhone 15 -> OPPO | 21.96 KB/s | Supporting evidence only; same throughput class as current iOS L2CAP evidence | Fresh physical proof log: `BENCHMARK transport bytes=65536 elapsedMs=2915 throughputKBps=21.96 result=Sent mode=gattPrototype setupMs=1926`; iPhone reported `maxWriteWithoutResponse=512`, OPPO logged `mtu=517`, and the Android proof app emitted a full 64 KiB receipt notification. |
| Direct-link pairing requirement, OPPO + iPhone final state | no pairing dialog observed | Supporting evidence only; not a standalone success criterion | Latest OPPO logcat did not show `PAIRING_REQUEST` or `BluetoothPairingDialog`. |

## Current release-decision posture

`SC-004` remains partially unmet on iOS. The best clean retained reference-hardware L2CAP run on iPhone 15 -> Samsung is still only `19.94 KB/s`, the latest clean queued-writer OPPO rerun reached `20.05 KB/s`, and the latest telemetry-enabled Samsung rerun reached `19.62 KB/s`; all remain far below the required `>= 60 KB/s`. A proof-only native GATT fallback prototype proved feasible on the same iPhone 15 hardware (`23.92 KB/s` to Samsung, `21.96 KB/s` to OPPO), but those runs also remain well below the normative target and therefore do not change the blocker posture. The stricter recipient-confirmed MeshLink matrix also failed in all four fresh Samsung/OPPO × 256 B/64 KiB cells: every sender run ended `ReceiptTimeout` at ~21.3-21.4 s, while the passive Android peers retained `BENCHMARK receipt send(...) -> NotSent(reason=UNREACHABLE)` lines for the matching tokens. A later post-remediation matrix with bounded passive receipt retries still did not restore recipient-confirmed completion: Samsung 256 B remained `ReceiptTimeout`, Samsung 64 KiB / OPPO 256 B / OPPO 64 KiB all ended `NotSent(reason=UNREACHABLE)` on the sender side, and the passive Samsung 256 B proof app retained only repeated `passive.receipt.wait ... knownPeers=[]` lines plus `BENCHMARK receipt abandoned ... deadlineMs=18000`. Explicit LOW-power `SC-006` coverage now exists as well: Android passed the 256-byte LOW-mode proof run in `411 ms`, but the latest iPhone 15 -> OPPO LOW-mode sender run still ended `ReceiptTimeout` after `21331 ms` once the direct peer disappeared before the receipt could return. That means the current blocker is not only iPhone large-transfer throughput but also unstable proof-receipt return delivery and peer reappearance on the MeshLink path, including the iOS LOW-power sender benchmark. Unless an explicit waiver narrows the public iOS large-transfer claim, this keeps release blocked on the iOS portion of `SC-004`, on iOS `SC-006`, and on recipient-confirmed proof completion.

## Current learnings

- MeshLink direct L2CAP links should remain unpaired; the app-layer Noise and TOFU model already provides the intended security boundary.
- Android API 36 behaved best when explicit insecure LE socket settings were preferred first and legacy insecure LE CoC APIs were kept as fallback for older releases.
- iOS must honor the deterministic initiator tie-break. When iOS loses the key-hash ordering decision, it should wait for the inbound L2CAP channel instead of forcing a competing outbound reconnect.
- Disabling the iOS large-inline send path improved large-payload reliability, but it did not solve throughput.
- The queued-writer follow-up (`c45b7dc`) did not materially change the iPhone 15 throughput class: the latest clean OPPO rerun reached `20.05 KB/s` and the latest telemetry-enabled Samsung rerun reached `19.62 KB/s`, still roughly 3× below the `>= 60 KB/s` target.
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
- After the proof benchmarks switched to recipient-confirmed semantics, the
  benchmark harnesses needed a longer observation window so failing runs surface
  a scored `BENCHMARK transport ... result=...` line instead of a raw harness
  timeout.
- The remaining physical performance blocker is iPhone 15 large-transfer throughput and stability, not discovery, trust pinning, or pairing.

## Refresh commands

- JVM benchmarks: `./gradlew :benchmarks:jvmBenchmark`
- JVM smoke baselines: `./gradlew :benchmarks:jvmSmokeBenchmark`
- Android proof benchmarks: run the instrumented proof benchmarks against physical peers
- iOS proof benchmarks: run the XCTest proof benchmarks plus a physical peer-backed proof-app run
