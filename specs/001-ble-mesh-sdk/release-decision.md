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

**Current Phase-21 blocker:** the first shared-Kotlin product-path integration
attempt is blocked on the iOS bridge seam, not on the proof bearer itself. The
retained Samsung product-path attempt
(`/tmp/ios_meshlink_gattside_samsung_bridge_20260514T191931`) showed the side
link connecting and becoming ready, then failed inside the iOS shared-Kotlin
transport with `kotlin.TypeCastException: class kotlinx.cinterop.CPointer
cannot be cast to class platform.Foundation.NSData` while trying to feed GATT
notify chunks into `CBPeripheralManager.updateValue`. Until that bridge is
resolved, Phase 21 cannot truthfully claim a working product-path bearer.

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
