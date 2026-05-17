# Release Decision: SC-004 Closure

## Purpose

Capture the remaining release-decision path for `SC-004` so reviewers can see
whether the feature is being held to full conformance or shipped under an
explicit waiver / known limitation.

## Current Status

- **Status**: Closed via explicit waiver / known limitation
- **Blocking requirement**: `SC-004` remains unmet on iOS; the current release
  may proceed only under the waiver guardrails recorded below.
- **Chosen closure path**: Ship under the explicit waiver / known-limitation
  path for the current release.
- **Stakeholder approval**: Approved by the active project stakeholder via
  explicit waiver-path selection in this session on `2026-05-14`.
- **Accepted iOS large-transfer limitation**: iOS single-hop 64 KiB throughput
  on the MeshLink path remains below the normative `>= 60 KB/s` target on
  reference benchmark hardware.
- **Latest bounded conformance attempt**: a fresh iPhone 15 -> OPPO transient-
  appId rerun with a widened 16 KiB inner iOS write batch still completed at
  only `18.37 KB/s`, so the change was reverted.
- **Additional follow-up matrix after `T092`**: fresh Samsung reruns for
  run-loop scheduling (`27.33 KB/s`), shorter sender ACK settlement
  (`24.90 KB/s`), a 32-frame transport coalescing window (`28.33 KB/s`), a
  re-enabled 64 KiB inline path (`31.84 KB/s`), and that inline path plus a
  16 KiB inner batch (`23.05 KB/s`) all remained below the then-current
  Samsung best case (`33.56 KB/s`).
- **More invasive cross-platform role-policy redesign**: the shared discovery
  payload now carries an Android/iOS platform-family hint so mixed-platform
  peers deterministically prefer Android-initiated L2CAP links into the
  iPhone-hosted inbound channel, where iOS can request
  `CBPeripheralManagerConnectionLatencyLow`.
- **Accepted retained evidence for public reference**: Samsung reached
  `39.48 KB/s` (3-run final matrix `24.37-39.48 KB/s`, all with the
  low-latency request present) and OPPO reached `52.03 KB/s` (2-run final
  matrix `45.33-52.03 KB/s`, both with the low-latency request present).
- **Rejected combination**: re-enabling the 64 KiB inline MeshLink path on top
  of the incoming-channel low-latency request still reached only `33.97 KB/s`,
  so the inline path remains rejected.
- **Additional rejected post-redesign follow-ups**: 8-frame iOS coalescing,
  a deterministic-path inline rerun, and ACK-batch=`32` reruns all stayed in
  the `30.70-37.08 KB/s` range and failed to beat the retained deterministic
  baseline (`52.03 KB/s` OPPO, `39.48 KB/s` Samsung).
- **Reason the waiver was selected**: the role-dependent randomness on the
  mixed Android/iOS path is resolved, but the obvious remaining app-layer
  levers inside the current public MeshLink L2CAP product path have now been
  exercised. On the public Android `BluetoothSocket` / LE L2CAP APIs and iOS
  Core Bluetooth L2CAP APIs available here, the next conformance branch looks
  more like a transport-scope decision than another small implementation tweak.
- **Explicit non-conformance that remains**: the waiver narrows release claims,
  but it does not amend `SC-004`; iOS large-transfer throughput is still below
  the normative target.
- **Non-blocker evidence already restored**: recipient-confirmed 64 KiB proof
  completion on the Samsung / OPPO physical path.

## Allowed Closure Paths

### 1. Conformance path

- Retain passing iOS `SC-004` evidence on reference benchmark hardware.
- Keep recipient-confirmed proof evidence aligned with the scored run.
- Update `spec.md`, `plan.md`, `benchmarks/README.md`, and `research.md`.
- **Current execution status (2026-05-14):** Not selected for the current
  release. The bounded iOS MeshLink-path remediation experiments, the deeper
  mixed-platform role-policy redesign, and the later post-redesign follow-ups
  were all retained as evidence, but none closed the remaining iOS throughput
  gap.

### 2. Explicit waiver / known-limitation path

- Record stakeholder approval for shipping with the iOS throughput gap.
- Narrow any public iOS large-transfer performance claim to match retained
  evidence.
- Link the accepted residual risk and supporting evidence in the canonical
  docs.
- **Selected execution status (2026-05-14):** Completed for the current
  release. Public claims are now constrained as follows:
  - do not claim iOS satisfies `SC-004` or `>= 60 KB/s` single-hop 64 KiB
    throughput
  - do not claim Android/iOS parity for 64 KiB throughput; parity claims remain
    limited to API surface, trust, discovery, routing behavior, power
    diagnostics, and the measured 256-byte latency path
  - if public iOS large-transfer behavior is described, cite the retained
    deterministic-path evidence: recipient-confirmed 64 KiB runs completed at
    `39.48-52.03 KB/s` depending on the passive reference peer
  - the proof-only GATT prototype remains non-normative and MUST NOT be cited
    as product-conformance fallback evidence

## Future Conformance Branch After the Waived Release

The current release stays on the explicit waiver path above. That does **not**
authorize silent return to small L2CAP tuning work as the next future
conformance branch.

The first post-waiver future-closure branch is now:

- compare materially different transport / platform redesigns instead of more
  L2CAP batching or ACK-cadence variants
- start with a proof-only reverse-direction branch that exercises a different
  iOS API path: iPhone-hosted GATT notifications from an iOS peripheral manager
  into an Android central client, with recipient-confirmed completion retained
- only consider product-path integration or spec amendment if that proof branch
  materially outperforms the retained deterministic L2CAP baseline and gives a
  credible path to `>= 60 KB/s`

Fresh proof-only reverse-direction evidence now makes that branch promising
rather than hypothetical:

- OPPO retained runs: `73.65 KB/s`, `43.13 KB/s`, plus one
  `NotSent(reason=CENTRAL_UNSUBSCRIBED)` failure
- Samsung retained runs: `65.98 KB/s`, `53.87 KB/s`, and `68.52 KB/s`
- the branch therefore exceeded the normative iOS target on three retained
  physical runs and preserved recipient-confirmed completion on those successes

**Decision:** promote the reverse-direction GATT-notify branch to the next
product-path integration candidate for a future non-waived release, while
keeping the current release on the explicit waiver path until product-path
integration and fresh retained non-proof evidence succeed.

**Phase-21 update after reopening product-path work:** the original iOS bridge
seam is now resolved by an optional Swift-installed transport bridge that calls
`CBPeripheralManager.updateValue` from native code, so the product-path bearer
can progress past the earlier `NSData` cast failure.

**Phase-21/22 resolution update (2026-05-17):** the later mixed-bearer
control-plane blocker is now resolved on current HEAD. After stabilizing iOS
notify-frame drain completion plus Android GATT-notify frame ordering and
Android->iOS GATT-write chunk sizing, fresh retained product-path reruns
completed recipient-confirmed 64 KiB delivery on both reference peers:

- Samsung rerun `/tmp/ios_meshlink_gattside_samsung_framingfix_20260517T154549`
  retained `BENCHMARK transport bytes=65536 elapsedMs=1406 throughputKBps=45.52
  result=Sent`, `BENCHMARK receipt from ... token=0000a8ff28258725 bytes=65536`,
  and passive Samsung retained `BENCHMARK receipt send(651947) -> Sent ...
  attempt=1`
- OPPO rerun `/tmp/ios_meshlink_gattside_oppo_writecap_20260517T155015`
  retained `BENCHMARK transport bytes=65536 elapsedMs=1218 throughputKBps=52.55
  result=Sent`, `BENCHMARK receipt from ... token=0000a93d37a4d03a bytes=65536`,
  and passive OPPO retained `BENCHMARK receipt send(320fdf) -> Sent ...
  attempt=1`

Phase 21/22 is therefore no longer blocked on the original `NSData` seam,
forward transfer completion, or recipient-confirmed mixed-bearer proof
closure.

**Latest current-HEAD throughput update (2026-05-17):** the future branch no
longer depends on another BLE PHY tweak alone. Fresh retained headless reruns
first pushed OPPO over the normative iOS target while leaving Samsung at only
`52.72-58.99 KB/s` despite explicit LE 2M PHY (`phy tx=2 rx=2`). The later
winning remediation instead came from the shared engine and proof harness:
large inline sends now suspend concurrent discovery, and the iPhone proof app
restarts benchmark auto-send when a direct route recovers after a transient
peer-loss event. Fresh retained current-head reruns now retain `61.13-68.67
KB/s` on Samsung and `77.02-78.24 KB/s` on OPPO with recipient-confirmed proof
closure. This future branch therefore now has retained evidence capable of
closing iOS `SC-004` on the reference matrix, even though the already-released
baseline still keeps its explicit waiver / known-limitation history.

This future branch does not change the current release claims or waiver
guardrails.

## Evidence Sources

- `specs/001-ble-mesh-sdk/spec.md`
- `specs/001-ble-mesh-sdk/plan.md`
- `specs/001-ble-mesh-sdk/research.md`
- `benchmarks/README.md`

## Update Rule

Whenever the chosen closure path changes, this file MUST be updated in the same
change set as the corresponding `spec.md`, `plan.md`, and `tasks.md` updates.
Future conformance-branch changes after a waived release MUST also update the
matching `research.md` and task-ledger entries in the same change set.
