# Release decision: SC-004 closure

## Purpose

Capture the release-decision path for `SC-004` so reviewers can see whether the
feature is being held to full conformance or shipped under an explicit waiver.

## Current status

- **Released baseline status**: closed via explicit waiver / known limitation
- **Released-baseline blocking requirement**: `SC-004` remained unmet on iOS,
  so that release could proceed only under the waiver guardrails recorded below
- **Released-baseline chosen closure path**: explicit waiver /
  known-limitation path
- **Stakeholder approval**: approved on `2026-05-14`
- **Released-baseline accepted limitation**: iOS single-hop 64 KiB throughput on
  the MeshLink path remained below the normative `>= 60 KB/s` target on
  reference benchmark hardware
- **Released-baseline public reference evidence**: Samsung reached `39.48 KB/s`
  and OPPO reached `52.03 KB/s`
- **Current-head future-branch status**: recipient-confirmed product-path
  evidence now closes `SC-004` on the reference matrix
- **Current-head retained evidence**: Samsung now retains `61.96-70.64 KB/s`
  and OPPO retains `77.48-80.10 KB/s` on the latest rebuilt/install headless
  path
- **Non-blocker evidence already restored**: recipient-confirmed 64 KiB proof
  completion on the Samsung and OPPO physical path

## Allowed closure paths

### 1. Conformance path

- Retain passing iOS `SC-004` evidence on reference benchmark hardware.
- Keep recipient-confirmed proof evidence aligned with the scored run.
- Update `spec.md`, `plan.md`, `benchmarks/README.md`, and `research.md`.
- **Current execution status**: achieved on the latest future branch. The
  released baseline still uses the waiver path.

### 2. Explicit waiver / known-limitation path

- Record stakeholder approval for shipping with the iOS throughput gap.
- Narrow any public iOS large-transfer claim to match retained evidence.
- Link the accepted residual risk and supporting evidence in the canonical
  docs.
- **Current execution status**: completed for the released baseline.

Public claims for the released baseline remain constrained as follows:

- do not claim iOS satisfies `SC-004` or `>= 60 KB/s` single-hop 64 KiB
  throughput
- do not claim Android/iOS parity for 64 KiB throughput
- if public iOS large-transfer behavior is described, cite the retained
  deterministic-path evidence: `39.48-52.03 KB/s`
- the proof-only GATT prototype remains non-normative and must not be cited as
  product-conformance fallback evidence

## Future conformance branch after the waived release

The released baseline stays on the waiver path above. That did **not** authorize
another small L2CAP tuning pass as the next closure attempt.

The post-waiver future branch instead compares materially different transport
and platform redesigns, starting with a proof-only reverse-direction branch that
uses iPhone-hosted GATT notifications into an Android central client while
preserving recipient-confirmed completion.

Fresh proof-only reverse-direction evidence made that branch promising rather
than hypothetical:

- OPPO retained runs: `73.65 KB/s`, `43.13 KB/s`, plus one
  `NotSent(reason=CENTRAL_UNSUBSCRIBED)` failure
- Samsung retained runs: `65.98 KB/s`, `53.87 KB/s`, and `68.52 KB/s`

That was enough to justify product-path integration work. Later current-head
product-path evidence then resolved the mixed-bearer closure blocker and pushed
throughput above the normative iOS target on both reference peers.

## Evidence sources

- `specs/ble-mesh-sdk/spec.md`
- `specs/ble-mesh-sdk/plan.md`
- `specs/ble-mesh-sdk/research.md`
- `benchmarks/README.md`

## Update rule

Whenever the chosen closure path changes, update this file in the same change
set as the corresponding `spec.md`, `plan.md`, and `tasks.md` updates.
