# Implementation Plan: MeshLink Reference App

**Branch**: `002-meshlink-reference-app` | **Date**: 2026-05-18 | **Spec**: [spec.md](./spec.md)  
**Input**: Feature specification from `/specs/002-meshlink-reference-app/spec.md`

This plan records the build shape, design constraints, and verification posture
for the reference app work.

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

Build a new root-level `meshlink-reference` mobile app that showcases the
existing `:meshlink` SDK through one shared Compose Multiplatform experience on
Android and iOS.

The app is organized around four slices:

- a guided first exchange
- an advanced capability explorer
- a live technical timeline with retained history and exports
- a clearly separated lab for proof-only and benchmark-only behavior

Android ships from the KMP app module, while iOS uses a direct-integrated Xcode
host project under `meshlink-reference/ios`. The plan preserves the current
MeshLink SDK API, runtime dependency budget, wire format, and proof/benchmark
governance while adding an operator-friendly reference surface.

## Technical Context

**Language/Version**: Kotlin 2.3.10 with JVM toolchain 17 for shared and Android code; Swift 5.0 for the iOS host project  
**Primary Dependencies**: `:meshlink`; Compose Multiplatform UI stack pinned compatibly with Kotlin 2.3.10; AndroidX lifecycle runtime compose `2.10.0`; AndroidX lifecycle viewmodel compose `2.10.0`; AndroidX navigation compose `2.9.2`; Compose resources; `kotlinx-coroutines-core` `1.10.2`; `kotlinx-serialization-json` for app-local session history and export artifacts  
**Storage**: App-local JSON files for recent session history and exported session artifacts; redacted previews by default; no backend or cloud sync  
**Testing**: `kotlin-test` + Power-assert for shared state and model logic; Compose Multiplatform UI tests for common flows; Android instrumented smoke/UI tests; iOS simulator tests; targeted two-device quickstart validation; existing `:meshlink` regression suite and JVM benchmarks rerun whenever integration touches library behavior  
**Target Platform**: Android API 29+, iOS 15+  
**Project Type**: Kotlin Multiplatform mobile reference app with shared Compose UI and a native iOS host wrapper  
**Performance Goals**: Guided first-exchange evidence reachable within 5 minutes; timeline filter/search refresh within 1 second for sessions up to 2,000 entries; redacted session export completes within 60 seconds; routine timeline scrolling and control interaction remain visually smooth on supported devices  
**Constraints**: Offline-only; app lives under root `meshlink-reference/`; same named workflows and terminology on Android/iOS; main guided experience separate from advanced controls and separate from lab-only behavior; solo exploration mode is non-authoritative; recent local history is capped at 20 sessions; full payload export requires explicit opt-in; existing proof apps and retained benchmarks remain the normative transport-validation path  
**Constitutional Constraints**: Detekt and ktfmt gates; zero lingering TODOs; exact dependency pinning in the version catalog; Conventional Commits; BCV and KDoc only if `:meshlink` public API changes; 100% line/branch coverage expectations for governed code; Power-assert diagnostics; documentation parity between Android and iOS; feature-branch-only workflow; no new runtime dependency leakage into `:meshlink`; no wire-format, crypto-provider, minimum-platform, or cross-platform event/error/state regressions; benchmark reruns or explicit non-applicability when performance-sensitive library paths change  
**Applicable Skills**: kotlin-multiplatform, compose-multiplatform, kmp-ios-integration, gradle-build-tool, frontend-design, make-interfaces-feel-better, write-docs  
**Scale/Scope**: One new root app module plus one iOS host project; four independently valuable story slices; 6–8 core screens/workflows; 20-session bounded local history; session timelines sized for up to 2,000 entries per retained session

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
- [x] Quality gates are translated: feature-branch workflow, formatting,
      static analysis, coverage, API compatibility, required platform tests,
      benchmark evidence, public-API KDoc, documentation parity, and
      conditional YAML linting are represented in the plan or explicitly marked
      non-applicable.
- [x] Story slices remain independently valuable: each planned story can be
      implemented, validated, and demonstrated on its own; shared app-shell and
      session-model work are called out only because they unblock multiple
      slices without changing their acceptance intent.
- [x] All unknowns are surfaced: all material design and storage decisions are
      resolved in `research.md`; no `NEEDS CLARIFICATION` markers remain.
- [x] Validation and evidence are defined: each story has a verification path,
      and the plan identifies applicable automated tests, parity checks,
      documentation work, quickstart validation, and benchmark rerun
      expectations when library-sensitive paths are touched.
- [x] Relevant skills are identified: the plan lists the project/global skills
      that MUST be consulted before implementation or best-practice-heavy work.
- [x] Commit discipline is planned: file-modifying governed work will be
      checkpointed with Conventional Commits between workflow steps, and the
      optional Speckit git hook can satisfy that requirement when used.
- [x] High-risk MeshLink changes are called out: this feature is planned to
      avoid `:meshlink` public API, crypto-provider, wire-format, and runtime
      dependency changes; if implementation later requires any of them, that
      work becomes an explicit high-risk review surface.
- [x] Artifact sync is planned: `research.md`, `data-model.md`, `quickstart.md`,
      `contracts/`, `tasks.md`, and `AGENTS.md` will stay synchronized with the
      reference-app plan and avoid assistant-vendor-specific instructions.

**Post-design re-evaluation (2026-05-18):** `research.md`, `data-model.md`,
`quickstart.md`, and `contracts/` now resolve the planning unknowns for app
structure, iOS integration, local retention, export format, and workflow
separation. The current design keeps normative MeshLink behavior in the main
and advanced surfaces, isolates proof-only and benchmark-only behavior in a lab
surface, and preserves the existing SDK as the source of truth. No unresolved
clarifications remain for `/speckit.tasks`.

## Project Structure

### Documentation (this feature)

```text
specs/002-meshlink-reference-app/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── reference-ui.md
│   └── session-artifact.md
└── tasks.md
```

### Source Code (repository root)

```text
meshlink/
├── build.gradle.kts
├── api/
└── src/

benchmarks/
├── build.gradle.kts
└── src/

meshlink-proof/
├── android/app/
└── ios/

meshlink-reference/
├── build.gradle.kts
├── src/
│   ├── commonMain/
│   │   ├── kotlin/
│   │   └── composeResources/
│   ├── commonTest/
│   ├── androidMain/
│   ├── androidUnitTest/
│   ├── iosMain/
│   └── iosTest/
└── ios/
    ├── project.yml
    ├── ReferenceApp/
    └── ReferenceAppTests/
```

**Structure Decision**: Use a single root-level Kotlin Multiplatform app module
at `meshlink-reference/` so Android and iOS share one UI/state implementation,
resource catalog, and test surface while still giving iOS a native host project
for signing and device workflows. This honors the user's requested repository
placement and keeps the new reference app clearly separate from the existing
proof integrations.

## Complexity Tracking

No constitutional violations currently require justification. If implementation
later requires a new `:meshlink` public API, a new runtime dependency inside the
library artifact, or a wire-format / diagnostic-catalog change, this section
must be updated before implementation proceeds.
