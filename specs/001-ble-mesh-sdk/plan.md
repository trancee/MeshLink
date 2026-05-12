# Implementation Plan: MeshLink Offline BLE Mesh SDK

**Branch**: `001-ble-mesh-sdk` | **Date**: 2026-05-12 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-ble-mesh-sdk/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

Implement MeshLink as a Kotlin Multiplatform, library-first SDK centered on a
single shared `meshlink` module. The public API remains a thin `MeshLinkApi` /
`MeshLink` shell over an internal `MeshEngine` coordinator. Security is split
between Noise XX hop-to-hop sessions on each adjacent link and Noise K
end-to-end payload sealing between the origin and final destination peers.
Diagnostics use one shared 26-code `DiagnosticCode` catalog in `commonMain`,
and all thrown public failures derive from a sealed `MeshLinkException`
hierarchy in `commonMain`; platform exceptions are wrapped before crossing the
public API surface. `MeshLinkConfig` exposes a configurable
`deliveryRetryDeadline` in `kotlin.time.Duration` for the no-route case. While
a delivery has no valid route, shared runtime code schedules attempts with
bounded, jittered exponential backoff and retries immediately when topology
updates reveal a valid path. Retry scheduler state remains in memory only and
is discarded on app or SDK restart. Routing, transfer, trust, diagnostics,
power policy, and the wire codec live in `commonMain`. Android and iOS source
sets provide BLE transport and secure-storage glue only. The repository also
ships runnable proof integrations in `meshlink-sample/android` and
`meshlink-sample/ios` for quickstart validation and automated
reference-hardware benchmarks. To satisfy the user's “no external
dependencies” request, the runtime ships no additional third-party libraries
beyond the constitutionally allowed `kotlinx-coroutines-core`; codec, routing,
and crypto-provider logic remain in-repo. Repository baselines and physical
proof findings are recorded in `benchmarks/README.md` and
`specs/001-ble-mesh-sdk/research.md`; single-hop iOS 64 KiB throughput remains
an explicit release-decision blocker unless a documented waiver narrows the
public iOS large-transfer claim.

## Technical Context

**Language/Version**: Kotlin 2.3.10, Kotlin Multiplatform, JVM toolchain 17  
**Primary Dependencies**: Runtime: Kotlin stdlib 2.3.10 + `kotlinx-coroutines-core` 1.10.2 only; Build/Test: exact pinned versions of `kotlin-test`, Binary Compatibility Validator, Kover, Power-assert, and kotlinx-benchmark declared in `gradle/libs.versions.toml`  
**Storage**: Platform secure storage for pinned trust material and local identity; in-memory route, transfer, retry, and presence state  
**Testing**: `kotlin-test` across targets, JVM/Android unit tests, iOS tests, canonical `MeshTestHarness` + `VirtualMeshTransport`, Wycheproof vectors, API checks, JVM benchmarks via kotlinx-benchmark, Android proof-app automated benchmarks for transport/low-power/cold-start targets, iOS proof-app automated benchmarks for transport/low-power/cold-start targets, and harness-driven memory/convergence measurements  
**Target Platform**: Android API 29+, iOS 15+, JVM benchmark/reference target  
**Project Type**: Kotlin Multiplatform library SDK with benchmark module and runnable Android/iOS proof integrations  
**Performance Goals**: ≥80 KB/s Android L2CAP, ≥60 KB/s iOS L2CAP, 50 ms p95 for 1-hop 256 B message, 8 MB steady-state heap at 8 peers, ≤5% scan duty in LOW mode, <500 ms from `mesh.start()` to first advertisement, 3 s route convergence for 10-node topology, <1 µs JVM codec encode/decode
**Benchmark Evidence Handling**: `benchmarks/README.md` retains observed benchmark and proof-app evidence for reviewer traceability. Those retained baselines do not lower or replace the normative success criteria in `spec.md` or the mirrored performance goals above.
**Constraints**: Offline-only; no servers or accounts; TOFU trust pinning; 64 KiB release payload limit; configurable in-memory delivery retry deadline; no retry persistence across restart; bounded, jittered exponential backoff for no-route scheduling; immediate retry on route availability; L2CAP-first with GATT fallback; no additional third-party runtime dependencies; current physical validation still leaves the iOS ≥60 KB/s 64 KiB single-hop transfer target unmet on reference hardware, so any release before remediation requires an explicit waiver and documented limitation
**Constitutional Constraints**: `explicitApi()` required; Detekt + ktfmt gates; BCV-tracked public API; 100% line/branch coverage; Power-assert diagnostics; Wycheproof validation; canonical virtual harness for integration tests; Android/iOS parity for API, docs, state, diagnostics, the shared 26-code diagnostic catalog, and the sealed `MeshLinkException` hierarchy; benchmark evidence for crypto, routing lookup, wire codec, route convergence, transport throughput/latency, steady-state memory, LOW-power duty cycle, and cold-start paths; all shared logic in `commonMain`; no external crypto library; FlatBuffers wire compatibility; runtime dependency budget limited to `kotlinx-coroutines-core`; repository benchmark baselines must stay documented in `benchmarks/README.md` and `specs/001-ble-mesh-sdk/research.md`
**Applicable Skills**: kotlin-multiplatform, kotlin-gradle-plugin, gradle-build-tool, kotlin-api-guidelines, android-ble, android-bluetooth-sockets, core-bluetooth, kmp-ios-integration, flatbuffers, babel-rfc8966, noise-protocol-framework, tcp-sack-rfc2018, optimize-ble-throughput
**Scale/Scope**: One shared SDK module, two mobile targets, one public API surface, 8-peer steady-state mesh, 10-node convergence validation topology, 64 KiB maximum v1 payload

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- [x] Spec-first evidence exists: the plan references an approved `spec.md` with
      prioritized user stories, acceptance scenarios, measurable requirements,
      assumptions, explicit out-of-scope boundaries, and a Constitutional
      Alignment section.
- [x] Root-constitution impact is mapped: applicable obligations from
      `constitution.md` are listed in Technical Context and carried into the
      design, especially code quality, testing, parity, performance, and
      compatibility constraints.
- [x] Story slices remain independently valuable: each planned user story can be
      implemented, validated, and demonstrated on its own, or any dependency is
      explicitly justified in Complexity Tracking.
- [x] All unknowns are surfaced: the spec clarifications are resolved, no
      `NEEDS CLARIFICATION` items remain after `research.md`, and the remaining
      physical iOS throughput gap is captured explicitly as a validation risk
      rather than left implicit.
- [x] Validation and evidence are defined: each user story has a clear
      verification approach, and the plan identifies applicable automated tests,
      compatibility checks, documentation-parity work, and benchmark evidence.
- [x] Relevant skills are identified: the plan lists the project/global skills
      that MUST be consulted before implementation or best-practice-heavy work,
      or explicitly records that no specialized skill applies.
- [x] Commit discipline is planned: file-modifying governed work will be
      checkpointed with Conventional Commits between workflow steps, and the
      optional `speckit.git.commit` hook may satisfy that checkpoint when used.
- [x] High-risk MeshLink changes are called out: public API, crypto-provider,
      wire-format, runtime-dependency, minimum-platform, and cross-platform
      event/error/state changes are treated as explicit review surfaces whenever
      they are touched.
- [x] Artifact sync is planned: `research.md`, `data-model.md`, `quickstart.md`,
      `contracts/`, `tasks.md`, proof-sample READMEs, supporting docs under
      `docs/explanation/`, and `AGENTS.md` stay synchronized when feature or
      governance behavior changes.

**Post-design re-evaluation (2026-05-12):** Existing `research.md`,
`data-model.md`, `quickstart.md`, and `contracts/` remain aligned with the
spec and both constitutions. No unresolved clarifications remain. The only
open delivery risk is closing the still-unmet iOS single-hop 64 KiB
throughput target on reference hardware.

**Release-decision framing (2026-05-12):** `SC-004` remains partially unmet on
iOS. Android meets its single-hop throughput target, but the best clean iPhone
15 -> Samsung 64 KiB run retained in the repository reached only `19.94 KB/s`,
below the required `>= 60 KB/s`, and telemetry-enabled reruns to both Samsung
and OPPO disconnected before a terminal benchmark line was emitted. The
release path is therefore binary: either keep release blocked until iOS
satisfies `SC-004`, or ship only under an explicit waiver that narrows iOS
large-transfer performance claims and records stakeholder acceptance of the
residual risk. That residual risk is two fold: materially slower 64 KiB
single-hop transfers on iOS and unresolved mid-transfer disconnect behavior
during physical telemetry runs.

## Artifact Governance & Source-of-Truth Precedence

- `spec.md` is normative for feature scope, acceptance wording, success
  criteria, release criteria, and blocker state. If `spec.md`, `plan.md`, and
  `tasks.md` differ on those points, `spec.md` wins unless it is explicitly
  amended.
- `plan.md` is normative for technical approach, engineering constraints,
  review surfaces, and artifact-governance rules. It may interpret `spec.md`
  for implementation, but it must not weaken or silently override `spec.md`.
- `tasks.md` is the canonical execution plan and append-only historical ledger.
  It records sequencing, traceability, and completion state, but task wording
  does not by itself change `spec.md` requirements or `plan.md` constraints.
- Supporting artifacts such as `research.md`, `quickstart.md`, `contracts/`,
  `benchmarks/README.md`, and generated checklists provide evidence,
  explanation, or review aids and must stay aligned with the canonical trio
  above; they do not override them.
- Repeated `/plan`, `/tasks`, and `/implement` reruns are preservation passes
  against the current canonical artifacts. Reruns may clarify wording and
  append new remediation work, but they must not regenerate from template in a
  way that erases accepted release framing, completed task state, or prior
  follow-up history.
- When new work is discovered after an item is complete, append a new task or
  follow-up phase instead of reopening, deleting, or silently repurposing the
  completed ledger entry, except for obvious clerical corrections.

## Project Structure

### Documentation (this feature)

```text
specs/001-ble-mesh-sdk/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── meshlink-api.md
│   └── wire-envelope.md
└── tasks.md
```

### Source Code (repository root)

```text
settings.gradle.kts
gradle/
└── libs.versions.toml

meshlink/
├── build.gradle.kts
├── api/
│   └── meshlink.api
└── src/
    ├── commonMain/kotlin/ch/trancee/meshlink/
    │   ├── api/
    │   ├── config/
    │   ├── crypto/
    │   ├── diagnostics/
    │   ├── engine/
    │   ├── identity/
    │   ├── power/
    │   ├── presence/
    │   ├── routing/
    │   ├── storage/
    │   ├── transfer/
    │   ├── transport/
    │   ├── trust/
    │   └── wire/
    ├── commonTest/
    │   ├── kotlin/ch/trancee/meshlink/
    │   └── resources/wycheproof/
    ├── androidMain/kotlin/ch/trancee/meshlink/platform/android/
    ├── androidUnitTest/kotlin/ch/trancee/meshlink/platform/android/
    ├── iosMain/kotlin/ch/trancee/meshlink/platform/ios/
    └── iosTest/kotlin/ch/trancee/meshlink/platform/ios/

benchmarks/
├── build.gradle.kts
└── src/jvmMain/kotlin/ch/trancee/meshlink/benchmarks/

meshlink-sample/
├── android/
└── ios/
```

**Structure Decision**: Use one KMP runtime module (`meshlink`) plus one JVM-only
benchmark module and two proof/sample integrations. Keep all protocol, routing,
transfer, trust, diagnostics, and configuration logic in `commonMain`. Restrict
`androidMain` and `iosMain` to BLE transport, secure storage, and other
platform APIs. Keep runtime dependencies to the constitutional minimum and
isolate all build/test tooling outside the shipped runtime artifact.

## Complexity Tracking

No constitutional violations currently require justification. The user request
for “no external dependencies” is implemented as “no additional third-party
runtime dependencies beyond the constitutionally allowed
`kotlinx-coroutines-core`.
”