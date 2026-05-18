# Release Decision: SC-004 Closure

## Purpose

Capture the remaining release-decision path for `SC-004` so reviewers can see
whether the feature is being held to full conformance or shipped under an
explicit waiver / known limitation.

## Current Status

- **Released baseline status**: Closed via explicit waiver / known limitation.
- **Released-baseline blocking requirement**: `SC-004` remained unmet on iOS,
  so that release may proceed only under the waiver guardrails recorded below.
- **Released-baseline chosen closure path**: Ship under the explicit waiver /
  known-limitation path for the current release.
- **Stakeholder approval**: Approved by the active project stakeholder via
  explicit waiver-path selection in this session on `2026-05-14`.
- **Released-baseline accepted iOS large-transfer limitation**: iOS single-hop
  64 KiB throughput on the MeshLink path remained below the normative
  `>= 60 KB/s` target on reference benchmark hardware.
- **Released-baseline public-reference evidence**: Samsung reached
  `39.48 KB/s` (3-run final matrix `24.37-39.48 KB/s`, all with the
  low-latency request present) and OPPO reached `52.03 KB/s` (2-run final
  matrix `45.33-52.03 KB/s`, both with the low-latency request present).
- **Current-head future-branch status**: recipient-confirmed product-path
  evidence now closes `SC-004` on the reference matrix.
- **Current-head retained evidence**: Samsung now retains `61.96-70.64 KB/s`
  across `/tmp/ios_meshlink_headless_samsung_promotefix_1` through `_5`, and
  OPPO now retains `77.48-80.10 KB/s` across
  `/tmp/ios_meshlink_headless_oppo_serialfix_1` through `_3`, all with
  recipient-confirmed proof receipts on attempt 1 on the latest rebuilt/
  install headless path.
- **Current-head repeat-series evidence**: after proof auto-send task
  de-duplication, fresh immediate retained series already stayed above target
  on both reference peers: Samsung `60.38-63.81 KB/s` across 5 runs (avg
  `62.41 KB/s`) and OPPO `74.51-79.50 KB/s` across 3 runs (avg `77.20 KB/s`).
  A later runner correction then fixed another benchmarking artifact by keeping
  one stable app ID across a retained repeat series by default. Under that
  corrected comparison-series posture, Samsung now retains two fresh 5-run
  series at `60.09-65.37 KB/s` (avg `62.83 KB/s`) and `62.44-66.81 KB/s`
  (avg `64.17 KB/s`), and OPPO retains a fresh 3-run series at
  `82.05-85.11 KB/s` (avg `83.94 KB/s`). Per-run varied-app-id repeat output is
  now treated as diagnostic cold-identity churn evidence rather than as the
  canonical conformance series.
- **Winning current-head remediations**: large inline sends now suspend
  discovery for the scored send window, the iPhone proof app both reschedules
  auto-send when a direct route recovers after a transient `Peer lost` event
  and cancels stale per-peer auto-send tasks so one physical run cannot emit
  duplicate scored benchmark sends, iOS now promotes remapped temporary inbound
  L2CAP links onto the resolved peer ID, and the headless runner fails fast on
  stale Android serials while preferring local cached iOS signing assets.
- **Non-blocker evidence already restored**: recipient-confirmed 64 KiB proof
  completion on the Samsung / OPPO physical path.

## Allowed Closure Paths

### 1. Conformance path

- Retain passing iOS `SC-004` evidence on reference benchmark hardware.
- Keep recipient-confirmed proof evidence aligned with the scored run.
- Update `spec.md`, `plan.md`, `benchmarks/README.md`, and `research.md`.
- **Current execution status (2026-05-17):** Achieved on the latest future
  branch. The released baseline still uses the explicit waiver path, but the
  newer current-head mixed-bearer product path now has retained reference-
  hardware evidence above the normative iOS threshold.

### 2. Explicit waiver / known-limitation path

- Record stakeholder approval for shipping with the iOS throughput gap.
- Narrow any public iOS large-transfer performance claim to match retained
  evidence.
- Link the accepted residual risk and supporting evidence in the canonical
  docs.
- **Selected execution status (2026-05-14):** Completed for the released
  baseline. Public claims for that released baseline remain constrained as
  follows:
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

The released baseline stays on the explicit waiver path above. That did **not**
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

**Decision (historical):** promote the reverse-direction GATT-notify branch to
the next product-path integration candidate for a future non-waived release,
while keeping the released baseline on the explicit waiver path until product-
path integration and fresh retained non-proof evidence succeed.

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
peer-loss event. A later runner / iOS inbound-link follow-up then removed a
stale-serial headless false hang and kept accepted inbound iOS L2CAP links
bound to the resolved peer ID once discovery/GATT-side identifiers converged.
Fresh retained current-head reruns now retain `61.96-70.64 KB/s` on Samsung
and `77.48-80.10 KB/s` on OPPO with recipient-confirmed proof closure. This
future branch therefore now has retained evidence capable of closing iOS
`SC-004` on the reference matrix, even though the already-released baseline
still keeps its explicit waiver / known-limitation history.

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
