# Release Decision: SC-004 Closure

## Purpose

Capture the remaining release-decision path for `SC-004` so reviewers can see
whether the feature is being held to full conformance or shipped under an
explicit waiver / known limitation.

## Current Status

- **Status**: Open — conformance path selected
- **Blocking requirement**: `SC-004`
- **Chosen closure path**: Continue on the explicit spec-conformance path; the
  waiver / known-limitation path is not currently selected.
- **Current blocker**: iOS single-hop 64 KiB throughput on the MeshLink path
  remains below the normative `>= 60 KB/s` target on reference benchmark
  hardware.
- **Latest bounded conformance attempt**: a fresh iPhone 15 -> OPPO transient-
  appId rerun with a widened 16 KiB inner iOS write batch still completed at
  only `18.37 KB/s`, so the change was reverted and the blocker remains open.
- **Additional follow-up matrix after `T092`**: fresh Samsung reruns for
  run-loop scheduling (`27.33 KB/s`), shorter sender ACK settlement
  (`24.90 KB/s`), a 32-frame transport coalescing window (`28.33 KB/s`), a
  re-enabled 64 KiB inline path (`31.84 KB/s`), and that inline path plus a
  16 KiB inner batch (`23.05 KB/s`) all remained below the then-current
  Samsung best case (`33.56 KB/s`).
- **More invasive iOS-only design change**: requesting
  `CBPeripheralManagerConnectionLatencyLow` on inbound L2CAP channels lifted
  the retained Samsung best case to `38.28 KB/s`, with a fresh final-code
  refresh still reaching `34.33 KB/s` on a run that emitted the same request.
- **Residual blocker after that design change**: same-design runs where the
  iPhone took the initiating/central role never emitted the low-latency
  request and fell back to `18.16-19.85 KB/s`; even the improved incoming-
  channel runs remain far below the normative `>= 60 KB/s` target.
- **Rejected combination**: re-enabling the 64 KiB inline MeshLink path on top
  of the incoming-channel low-latency request still reached only `33.97 KB/s`,
  so the inline path remains rejected.
- **Escalated interpretation**: the blocker has now narrowed to a more
  invasive, likely cross-platform role/connection-parameter problem plus the
  remaining CoreBluetooth large-write limit. Another small iOS-only constant
  tweak is unlikely to close `SC-004`; the next branch is either a deeper
  role-policy redesign or the explicit waiver path.
- **Non-blocker evidence already restored**: recipient-confirmed 64 KiB proof
  completion on the Samsung / OPPO physical path.

## Allowed Closure Paths

### 1. Conformance path

- Retain passing iOS `SC-004` evidence on reference benchmark hardware.
- Keep recipient-confirmed proof evidence aligned with the scored run.
- Update `spec.md`, `plan.md`, `benchmarks/README.md`, and `research.md`.
- **Current execution status (2026-05-14):** Selected. One bounded iOS
  MeshLink-path remediation experiment has been completed, additional bounded
  iOS-only follow-ups have been retained, and the latest more invasive iOS-only
  connection-latency design change still leaves `SC-004` open. The next
  conformance step is no longer another small constant tweak; it is either a
  deeper role-policy redesign or a decision to switch to the waiver path.

### 2. Explicit waiver / known-limitation path

- Record stakeholder approval for shipping with the iOS throughput gap.
- Narrow any public iOS large-transfer performance claim to match retained
  evidence.
- Link the accepted residual risk and supporting evidence in the canonical
  docs.

## Evidence Sources

- `specs/001-ble-mesh-sdk/spec.md`
- `specs/001-ble-mesh-sdk/plan.md`
- `specs/001-ble-mesh-sdk/research.md`
- `benchmarks/README.md`

## Update Rule

Whenever the chosen closure path changes, this file MUST be updated in the same
change set as the corresponding `spec.md`, `plan.md`, and `tasks.md` updates.
