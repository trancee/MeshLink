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
- **More invasive cross-platform role-policy redesign**: the shared discovery
  payload now carries an Android/iOS platform-family hint so mixed-platform
  peers deterministically prefer Android-initiated L2CAP links into the
  iPhone-hosted inbound channel, where iOS can request
  `CBPeripheralManagerConnectionLatencyLow`.
- **Fresh retained best-case evidence on that deterministic path**: Samsung now
  reached `39.48 KB/s` (3-run final matrix `24.37-39.48 KB/s`, all with the
  low-latency request present) and OPPO reached `52.03 KB/s` (2-run final
  matrix `45.33-52.03 KB/s`, both with the low-latency request present).
- **Residual blocker after the redesign**: the role-dependent randomness on the
  mixed Android/iOS path is now resolved, but even the improved deterministic
  path still remains below the normative `>= 60 KB/s` target.
- **Rejected combination**: re-enabling the 64 KiB inline MeshLink path on top
  of the incoming-channel low-latency request still reached only `33.97 KB/s`,
  so the inline path remains rejected.
- **Escalated interpretation**: the blocker has narrowed again. It is no
  longer the mixed-platform initiator policy; it is the remaining throughput
  shortfall on the now-deterministic optimized path.
- **Additional rejected post-redesign follow-ups**: 8-frame iOS coalescing,
  a deterministic-path inline rerun, and ACK-batch=`32` reruns all stayed in
  the `30.70-37.08 KB/s` range and failed to beat the retained deterministic
  baseline (`52.03 KB/s` OPPO, `39.48 KB/s` Samsung).
- **Current blocker interpretation**: the obvious remaining app-layer levers
  inside the current public MeshLink L2CAP product path have now been
  exercised. On the public Android `BluetoothSocket` / LE L2CAP APIs and iOS
  Core Bluetooth L2CAP APIs available here, the next conformance branch looks
  more like a transport-scope decision than another small implementation tweak.
- **Non-blocker evidence already restored**: recipient-confirmed 64 KiB proof
  completion on the Samsung / OPPO physical path.

## Allowed Closure Paths

### 1. Conformance path

- Retain passing iOS `SC-004` evidence on reference benchmark hardware.
- Keep recipient-confirmed proof evidence aligned with the scored run.
- Update `spec.md`, `plan.md`, `benchmarks/README.md`, and `research.md`.
- **Current execution status (2026-05-14):** Selected. One bounded iOS
  MeshLink-path remediation experiment has been completed, additional bounded
  iOS-only follow-ups have been retained, and the deeper cross-platform
  role-policy redesign has now also been implemented and physically rerun.
  `SC-004` remains open, but the next conformance step is no longer another
  initiator-policy or small app-layer tuning change. It is either a materially
  different transport/platform strategy or the explicit waiver path.

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
