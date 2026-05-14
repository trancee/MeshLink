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
public API surface. Host applications retain responsibility for user-facing
trust-reset UX, recipient selection, and any persistence of decrypted message
content outside the SDK. `MeshLinkConfig` exposes a configurable
`deliveryRetryDeadline` in `kotlin.time.Duration` for the no-route case, and
`meshLinkConfig` is the single shared cross-platform DSL builder for producing
`MeshLinkConfig` values. While a delivery has no valid route, shared runtime
code schedules attempts with bounded, jittered exponential backoff and retries
immediately when topology updates reveal a valid path. Retry scheduler state
remains in memory only and is discarded on app or SDK restart. Routing,
transfer, trust, diagnostics, power policy, and the wire codec live in
`commonMain`. Discovery uses one fixed advertisement contract: the 32-bit
service UUID `4d455348` plus one second 128-bit UUID carrying the 16-byte
MeshLink discovery payload in a single advertisement with no scan response.
The payload's shared header now also uses the previously reserved low 3 bits as
an Android/iOS platform-family hint so mixed-platform peers can deterministically
prefer Android-initiated L2CAP links into iPhone-hosted inbound channels.
Android and iOS source sets provide BLE transport and secure-storage glue only.
The repository also ships runnable proof integrations in `meshlink-sample/android` and
`meshlink-sample/ios` for quickstart validation and automated
reference-hardware benchmarks. To satisfy the user's “no external
dependencies” request, the runtime ships no additional third-party libraries
beyond the constitutionally allowed `kotlinx-coroutines-core`; codec, routing,
and crypto-provider logic remain in-repo. Repository baselines and physical
proof findings are recorded in `benchmarks/README.md` and
`specs/001-ble-mesh-sdk/research.md`; single-hop iOS 64 KiB throughput on the
current physical MeshLink path remains the underlying release-decision
non-conformance, and the current release relies on the documented waiver that
narrows the public iOS large-transfer claim and acknowledges the retained
throughput evidence gap. Recipient-confirmed MeshLink proof completion on the
current Samsung/OPPO physical path has been restored and must stay covered by
the retained benchmark evidence, but it is no longer the active blocker state.

## Technical Context

**Language/Version**: Kotlin 2.3.10, Kotlin Multiplatform, JVM toolchain 17  
**Primary Dependencies**: Runtime: Kotlin stdlib 2.3.10 + `kotlinx-coroutines-core` 1.10.2 only; Build/Test: exact pinned versions of `kotlin-test`, Binary Compatibility Validator, Kover, Power-assert, and kotlinx-benchmark declared in `gradle/libs.versions.toml`  
**Storage**: Platform secure storage for the minimum pinned trust material required for TOFU verification, plus `firstSeenAtEpochMillis` / `lastVerifiedAtEpochMillis` audit fields and the local identity. Route, transfer, retry, and presence state remain in memory, and the SDK runtime MUST NOT persist full peer identifiers, plaintext payloads, decrypted message content, or developer-visible diagnostics.
**Testing**: `kotlin-test` across targets, JVM/Android unit tests, iOS tests, canonical `MeshTestHarness` + `VirtualMeshTransport`, deployed-wire backward-compatibility fixtures for FlatBuffers-compatible envelopes, Wycheproof vectors, API checks, JVM benchmarks via kotlinx-benchmark, Android proof-app automated benchmarks for transport/low-power/cold-start targets, iOS proof-app automated benchmarks for transport/low-power/cold-start targets, an explicit `SC-004` protocol with one excluded warmup exchange, timed quickstart reader-test evidence for `SC-001`, retained raw sender/recipient evidence, recipient-confirmed proof-benchmark semantics on physical proof reruns, and harness-driven memory/convergence measurements. For `SC-004` scoring, the active benchmark device MUST be reference benchmark hardware for its platform class; passive peers act as controlled fixtures whose model, OS version, proof-app version, and transport role are retained with the evidence and held constant within a series.
**Target Platform**: Android API 29+, iOS 15+, JVM benchmark/reference target  
**Project Type**: Kotlin Multiplatform library SDK with benchmark module and runnable Android/iOS proof integrations  
**Performance Goals**: ≥80 KB/s Android L2CAP, ≥60 KB/s iOS L2CAP, 50 ms p95 for a 1-hop 256 B message, 8 MB steady-state heap at 8 peers, ≤5% scan duty and maintained connection intervals of `>= 500 ms` in LOW mode, ≤5 s for a 1-hop 256 B message in LOW mode after peer discovery and connection establishment, power-tier output that exposes advertisement interval / maintained-connection interval / max-connections / chunk-budget behavior, <500 ms from `mesh.start()` to first advertisement, 3 s control-plane route convergence for a 10-node topology, <1 µs JVM codec encode/decode
**Benchmark Evidence Handling**: `benchmarks/README.md` retains observed benchmark and proof-app evidence for reviewer traceability. Those retained baselines do not lower or replace the normative success criteria in `spec.md` or the mirrored performance goals above.
**Constraints**: Offline-only; no servers or accounts; TOFU trust pinning; 64 KiB release payload limit; configurable in-memory delivery retry deadline; no retry persistence across restart; bounded, jittered exponential backoff for no-route scheduling; immediate retry on route availability; L2CAP-first on the normative product path; discovery uses the fixed `4d455348` + 16-byte payload single-advertisement / no-scan-response contract; the retained GATT prototype is proof-only investigative evidence and does not constitute product-conformance fallback unless the specification is explicitly amended; no additional third-party runtime dependencies; current physical validation still leaves the iOS ≥60 KB/s 64 KiB single-hop transfer target unmet on reference hardware even though recipient-confirmed 64 KiB MeshLink proof completion is now restored on both Samsung and OPPO, so any release before remediation still requires an explicit waiver and documented limitation
**Constitutional Constraints**: `explicitApi()` required; Detekt + ktfmt gates; BCV-tracked public API; 100% line/branch coverage; Power-assert diagnostics; Wycheproof validation; canonical virtual harness for integration tests; Android/iOS parity for API, docs, state, diagnostics, the shared 26-code diagnostic catalog, the sealed `MeshLinkException` hierarchy, and the shared `meshLinkConfig` DSL builder; benchmark evidence for crypto, routing lookup, wire codec, route convergence, transport throughput/latency, steady-state memory, LOW-power duty cycle, LOW-tier maintained connection intervals, and cold-start paths; discovery-advertisement contract validation; all shared logic in `commonMain`; no external crypto library; FlatBuffers wire compatibility plus explicit backward-compatibility validation evidence; runtime dependency budget limited to `kotlinx-coroutines-core`; repository benchmark baselines must stay documented in `benchmarks/README.md` and `specs/001-ble-mesh-sdk/research.md`
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
      `release-decision.md`, `contracts/`, `tasks.md`, proof-sample READMEs,
      supporting docs under `docs/explanation/`, and `AGENTS.md` stay
      synchronized when feature or governance behavior changes.

**Post-design re-evaluation (2026-05-14):** Existing `research.md`,
`data-model.md`, `quickstart.md`, `release-decision.md`, and `contracts/`
remain aligned with the spec and both constitutions. No unresolved
clarifications remain. The open
delivery risk is now narrower on reference hardware: closing the still-unmet
iOS single-hop 64 KiB throughput target while preserving the newly restored
recipient-confirmed MeshLink proof path on Samsung and OPPO.

**Release-decision framing (2026-05-14):** `SC-004` remains partially unmet on
iOS, but the stricter recipient-confirmed 64 KiB MeshLink proof path is now
restored on the current reference hardware. Android still meets its single-hop
throughput target, while the post-remediation iPhone 15 recipient-confirmed
series now finishes 5/5 on both passive peers: OPPO completed at
`14.50-17.09 KB/s` (average `15.57 KB/s`) and Samsung completed at
`27.85-33.56 KB/s` (average `30.64 KB/s`). A later deeper cross-platform
role-policy redesign now uses the shared discovery header to deterministically
prefer Android-initiated L2CAP links on mixed Android/iOS peers, allowing the
iPhone to host the inbound channel and request low connection latency. Fresh
final retained evidence on that deterministic path reached `52.03 KB/s` on
OPPO and `39.48 KB/s` on Samsung. Even those improved runs remain below the
required `>= 60 KB/s` iOS target. A proof-only native GATT prototype remains
feasible (`23.92 KB/s` to Samsung, `21.96 KB/s` to OPPO), but it is still
supporting evidence only and does not satisfy the normative iOS target.

The release path therefore remains binary, but the blocker has narrowed:
either keep release blocked until the MeshLink path satisfies the iOS half of
`SC-004`, or ship only under an explicit waiver that narrows iOS large-
transfer performance claims and acknowledges the retained throughput evidence.
The residual risk is now materially slower 64 KiB single-hop transfers on the
now-deterministic mixed-platform path. The earlier recipient-confirmed proof-
completion instability is retained as historical diagnostic evidence rather
than the current blocker state.

**Current closure-path selection (2026-05-14):** The active project
stakeholder selected the explicit waiver / known-limitation path for the
current release in this session on `2026-05-14`. The MeshLink iOS path remains
non-conformant to the throughput clause of `SC-004`, but release planning is
now closed under the documented waiver guardrails instead of remaining blocked
on another small app-layer tuning attempt.

**Selected-waiver guardrails for release work (2026-05-14):**

- Do not claim that iOS meets `SC-004` or achieves `>= 60 KB/s` single-hop
  64 KiB throughput on the MeshLink path.
- Do not claim Android/iOS parity for 64 KiB throughput; parity claims remain
  limited to API surface, trust, discovery, routing behavior, power
  diagnostics, and the measured 256-byte latency path.
- If release notes or public docs mention current iOS large-transfer behavior,
  cite the retained deterministic-path evidence: recipient-confirmed 64 KiB
  runs completed at `39.48-52.03 KB/s` depending on the passive reference peer.
- Continue to state that recipient-confirmed 64 KiB proof completion is
  restored on the current MeshLink path.
- Treat the proof-only GATT prototype as investigative evidence only; it is not
  a product-conformance fallback.

**Post-waiver future conformance branch (2026-05-14):** A future non-waived
release must now come from a materially different transport / platform branch,
not another small L2CAP tuning pass. The first chosen future branch is a
proof-only reverse-direction benchmark path that exercises a different iOS API
surface: iPhone-hosted GATT notifications from an iOS peripheral manager into
an Android central client while keeping recipient-confirmed completion
semantics. If this branch graduates into the product path, the intended future
shape is to keep same-platform peers on L2CAP while allowing mixed
Android/iOS peers to negotiate the new iPhone-hosted GATT-notify bearer.
Fresh retained physical proof now shows that this branch is promising enough to
justify product-path integration work: OPPO reached
`73.65 KB/s` on the first retained run (with one lower `43.13 KB/s` rerun and
one unsubscribe failure), while Samsung reached `65.98 KB/s` and `68.52 KB/s`
on two retained runs (with one lower `53.87 KB/s` rerun). The branch therefore
has a credible path to `>= 60 KB/s`, but it remains non-normative until the
product path itself is updated and rerun with fresh retained evidence. The
first shared-Kotlin product-path attempt is now concretely blocked on an iOS
bridge issue: safely converting shared Kotlin `ByteArray` chunks into `NSData`
for `CBPeripheralManager.updateValue` inside the MeshLink runtime.

**Normative evidence-gap status after follow-up coverage closure (2026-05-14):**

- `FR-016` deployed-wire backward-compatibility fixture validation is now
  covered by completed `T074`.
- `FR-015a` persistence-minimization validation is now covered by completed
  `T075`.
- `SC-001` timed quickstart reader-test evidence is now covered by completed
  `T077`.
- `SC-006` remains closed as an evidence gap: after the passive Android proof
  app restored the resolved direct peer handle from the inbound benchmark
  payload, the latest retained iPhone 15 -> OPPO LOW-power 256-byte rerun
  completed `Sent` in `239 ms`.

These follow-up closures do not change the underlying iOS `SC-004` throughput
non-conformance. The current release path now depends on the explicit waiver /
known-limitation record in the canonical docs; any future release-readiness or
full-conformance claim without that waiver MUST instead retain passing evidence
on reference hardware.

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
- Supporting artifacts such as `research.md`, `quickstart.md`,
  `release-decision.md`, `contracts/`, `benchmarks/README.md`, and generated
  checklists provide evidence, explanation, or review aids and must stay
  aligned with the canonical trio above; they do not override them.
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
├── release-decision.md
├── contracts/
│   ├── discovery-advertisement.md
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