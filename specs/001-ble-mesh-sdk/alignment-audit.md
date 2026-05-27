# MeshLink spec-to-code alignment audit

**Date:** 2026-05-18  
**Branch audited:** `001-ble-mesh-sdk-spec-alignment-audit` (based on `main` at `1275e63`)  
**Audience:** maintainers and reviewers validating whether the current MeshLink
codebase still matches the approved `001-ble-mesh-sdk` specification.

After reading this audit, a reviewer should know which parts of the
implementation are aligned and whether any known implementation, coverage, or
retained-evidence gaps still remain.

## Scope and method

This audit compared the codebase against the canonical feature artifacts:

- `specs/001-ble-mesh-sdk/spec.md`
- `specs/001-ble-mesh-sdk/plan.md`
- `specs/001-ble-mesh-sdk/tasks.md`
- `specs/001-ble-mesh-sdk/contracts/meshlink-api.md`
- `specs/001-ble-mesh-sdk/contracts/discovery-advertisement.md`
- `benchmarks/README.md`

It also inspected these implementation and evidence surfaces:

- `meshlink/src/commonMain/`
- `meshlink/src/androidMain/`
- `meshlink/src/iosMain/`
- `meshlink/src/commonTest/`
- `benchmarks/src/jvmMain/`
- `meshlink-proof/android/`
- `meshlink-proof/ios/`

Fresh verification during the audit included:

- targeted API, routing, transfer, parity, discovery, and power tests
- Android and iOS compile checks for the SDK
- JVM benchmark-module compilation through `:benchmarks:jvmBenchmark`
- open-task and evidence scans against the current spec artifacts

## Overall assessment

The current MeshLink runtime is **mostly aligned** with the approved
`001-ble-mesh-sdk` spec.

The public API, TOFU trust model, routing, selective-ACK transfer path,
discovery contract, Android/iOS parity model, and retained `SC-004`
future-branch evidence are all present and supported by current code and tests.

The earlier `FR-008`, benchmark-build, `SC-005`, and `SC-001` follow-up gaps
have been closed or reduced to historical context.

## Areas verified as aligned

### 1. Public API, lifecycle, and cross-platform parity

**Spec references:** `FR-012`, `FR-014`, `FR-014a`, `SC-007`  
**Status:** Aligned

The shared public API matches the approved contract, including the factory
entry points, shared config DSL, lifecycle surface, and 26-code diagnostic
catalog.

Supporting verification includes `MeshLinkContractTest`,
`CrossPlatformParityTest`, and a fresh `apiCheck` pass.

### 2. TOFU trust, identity change handling, and trust reset

**Spec references:** `FR-002`, `FR-003`, `FR-003a`, `FR-015a`  
**Status:** Aligned

The codebase still implements TOFU trust, persisted trust records, and explicit
trust reset behavior.

Supporting verification includes restart survival, trust-reset behavior,
timestamp preservation, and storage-minimization coverage in the current test
suite.

### 3. Offline encrypted direct messaging and relay confidentiality

**Spec references:** `FR-001`, `FR-004`, `FR-005`, `FR-015`, `FR-018`  
**Status:** Aligned

The runtime remains offline-first, keeps messaging logic in shared code, and
preserves both hop-to-hop and end-to-end security layering.

Supporting verification includes direct-message integration tests,
confidentiality checks, and fresh Android/iOS compile checks.

### 4. Routing, no-route retry, and multi-hop delivery

**Spec references:** `FR-006`, `FR-007`, `SC-002`  
**Status:** Aligned

The codebase still contains the expected routing and retry machinery, including
route coordination, in-memory retry scheduling, and reconvergence behavior.

Supporting verification includes multi-hop routing tests, immediate retry on
route recovery, unreachable-on-expiry behavior, and retained convergence
evidence.

### 5. Large transfer, selective acknowledgement, and resume semantics

**Spec references:** `FR-009`, `FR-010`, `SC-003`  
**Status:** Aligned

The implementation still uses chunked transfer state, selective-ACK handling,
and route-change resume semantics.

Supporting verification includes large-transfer integration tests for resume,
out-of-order handling, duplicate handling, partial acknowledgements, and wire
compatibility fixtures.

### 6. Discovery advertisement contract

**Spec references:** `FR-015b`  
**Status:** Aligned

The discovery contract in code still matches the approved spec, including the
shared payload layout and platform-family hint semantics.

### 7. Wire compatibility

**Spec references:** `FR-016`  
**Status:** Aligned

The repository still retains deployed-wire fixtures and explicit decode
compatibility checks.

### 8. Power policy and LOW-mode contract

**Spec references:** `FR-013`, `FR-013a`, `FR-013b`, `SC-006`  
**Status:** Aligned

The shared power policy still exposes the expected LOW-mode behavior, and the
retained physical evidence remains synchronized with the documented posture.

### 9. `SC-004` current-head future-branch closure framing

**Spec references:** `SC-004`, `SC-004a`
**Status:** Aligned with current written posture

The canonical artifacts consistently distinguish:

- the **released baseline**, which remains waived
- the **current-head future branch**, which now has retained passing evidence

This audit did not rerun physical benchmarks, but the retained evidence and
wording are internally consistent.

## Closed gaps and current notes

### Closed gap — `SC-001` now retains a passing timed quickstart run

**Spec references:** `SC-001`  
**Status:** Closed

`research.md` now retains a successful timed quickstart run with timestamps,
elapsed duration, observer note, sender proof-log evidence, recipient
proof-log evidence, and the isolated proof `appId` and device pair used for the
run.
