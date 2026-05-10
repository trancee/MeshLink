# Implementation Plan: MeshLink Offline BLE Mesh SDK

**Branch**: `001-ble-mesh-sdk` | **Date**: 2026-05-10 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-ble-mesh-sdk/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

Implement MeshLink as a Kotlin Multiplatform, library-first SDK centered on a
single shared `meshlink` module. The public API remains a thin `MeshLinkApi` /
`MeshLink` shell over an internal `MeshEngine` coordinator. Routing, transfer,
trust, diagnostics, power policy, and the wire codec live in `commonMain`.
Android and iOS source sets provide BLE transport and secure-storage glue only.
To satisfy the user's “no external dependencies” request, the runtime ships no
additional third-party libraries beyond the constitutionally allowed
`kotlinx-coroutines-core`; codec, routing, and crypto-provider logic remain
in-repo.

## Technical Context

**Language/Version**: Kotlin 2.3.x, Kotlin Multiplatform, JVM toolchain 17  
**Primary Dependencies**: Runtime: Kotlin stdlib + `kotlinx-coroutines-core` only; Build/Test: `kotlin-test`, Binary Compatibility Validator, Kover, Power-assert, kotlinx-benchmark  
**Storage**: Platform secure storage for pinned trust material and local identity; in-memory route, transfer, retry, and presence state  
**Testing**: `kotlin-test` across targets, JVM/Android unit tests, iOS tests, canonical `MeshTestHarness` + `VirtualMeshTransport`, Wycheproof vectors, API checks, benchmarks via kotlinx-benchmark  
**Target Platform**: Android API 29+, iOS 15+, JVM benchmark/reference target  
**Project Type**: Kotlin Multiplatform library SDK with benchmark and proof/sample companions  
**Performance Goals**: ≥80 KB/s Android L2CAP, ≥60 KB/s iOS L2CAP, 50 ms p95 for 1-hop 256 B message, 8 MB steady-state heap at 8 peers, ≤5% scan duty in LOW mode, 3 s route convergence for 10-node topology, <1 µs JVM codec encode/decode  
**Constraints**: Offline-only; no servers or accounts; TOFU trust pinning; 64 KiB release payload limit; bounded in-memory retry window; no retry persistence across restart; L2CAP-first with GATT fallback; no additional third-party runtime dependencies  
**Constitutional Constraints**: `explicitApi()` required; Detekt + ktfmt gates; BCV-tracked public API; 100% line/branch coverage; Power-assert diagnostics; Wycheproof validation; canonical virtual harness for integration tests; Android/iOS parity for API, docs, state, and diagnostics; benchmark evidence for crypto/routing/codec paths; all shared logic in `commonMain`; no external crypto library; FlatBuffers wire compatibility; runtime dependency budget limited to `kotlinx-coroutines-core`  
**Applicable Skills**: kotlin-multiplatform, kotlin-gradle-plugin, gradle-build-tool, kotlin-api-guidelines, android-ble, core-bluetooth, kmp-ios-integration, flatbuffers, babel-rfc8966, noise-protocol-framework, tcp-sack-rfc2018  
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
- [x] All unknowns are surfaced: every open question is resolved in `research.md`,
      and the user constraint on KMP plus no new runtime dependencies has been
      translated into concrete architectural decisions.
- [x] Validation and evidence are defined: each user story has a clear
      verification approach, and the plan identifies applicable automated tests,
      compatibility checks, documentation-parity work, and benchmark evidence.
- [x] Relevant skills are identified: the plan lists the project/global skills
      that MUST be consulted before implementation or best-practice-heavy work,
      or explicitly records that no specialized skill applies.
- [x] High-risk MeshLink changes are called out: public API, crypto-provider,
      wire-format, runtime-dependency, minimum-platform, and cross-platform
      event/error/state changes are explicitly marked when touched.
- [x] Artifact sync is planned: `research.md`, `data-model.md`, `quickstart.md`,
      `contracts/`, tasks, and agent guidance updates are listed where
      applicable, and the plan avoids assistant-vendor-specific instructions.

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
    ├── commonMain/kotlin/com/meshlink/
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
    │   ├── kotlin/com/meshlink/
    │   └── resources/wycheproof/
    ├── androidMain/kotlin/com/meshlink/platform/android/
    ├── androidUnitTest/kotlin/com/meshlink/platform/android/
    ├── iosMain/kotlin/com/meshlink/platform/ios/
    └── iosTest/kotlin/com/meshlink/platform/ios/

benchmarks/
├── build.gradle.kts
└── src/jvmMain/kotlin/com/meshlink/benchmarks/

samples/
├── proof-android/
└── proof-ios/
```

**Structure Decision**: Use one KMP runtime module (`meshlink`) plus one JVM-only
benchmark module and two proof/sample integrations. Keep all protocol, routing,
transfer, trust, diagnostics, and configuration logic in `commonMain`. Restrict
`androidMain` and `iosMain` to BLE transport, secure storage, and other platform
APIs. Keep runtime dependencies to the constitutional minimum and isolate all
build/test tooling outside the shipped runtime artifact.

## Complexity Tracking

No constitutional violations currently require justification. The user request
for “no external dependencies” is implemented as “no additional third-party
runtime dependencies beyond the constitutionally allowed
`kotlinx-coroutines-core`.”
