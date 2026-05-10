# Implementation Plan: [FEATURE]

**Branch**: `[###-feature-name]` | **Date**: [DATE] | **Spec**: [link]
**Input**: Feature specification from `/specs/[###-feature-name]/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

[Extract from feature spec: primary requirement + technical approach from research]

## Technical Context

<!--
  ACTION REQUIRED: Replace the content in this section with the technical details
  for the project. The structure here is presented in advisory capacity to guide
  the iteration process.
-->

**Language/Version**: [e.g., Python 3.11, Swift 5.9, Rust 1.75 or NEEDS CLARIFICATION]  
**Primary Dependencies**: [e.g., FastAPI, UIKit, LLVM or NEEDS CLARIFICATION]  
**Storage**: [if applicable, e.g., PostgreSQL, CoreData, files or N/A]  
**Testing**: [e.g., pytest, XCTest, cargo test or NEEDS CLARIFICATION]  
**Target Platform**: [e.g., Linux server, iOS 15+, WASM or NEEDS CLARIFICATION]
**Project Type**: [e.g., library/cli/web-service/mobile-app/compiler/desktop-app or NEEDS CLARIFICATION]  
**Performance Goals**: [domain-specific, e.g., 1000 req/s, 10k lines/sec, 60 fps or NEEDS CLARIFICATION]  
**Constraints**: [domain-specific, e.g., <200ms p95, <100MB memory, offline-capable or NEEDS CLARIFICATION]  
**Constitutional Constraints**: [applicable rules from root `constitution.md`, e.g., API parity, 100% coverage, benchmark evidence, offline-only, exact dependency budget or NEEDS CLARIFICATION]  
**Applicable Skills**: [relevant project/global skills to consult before implementation or best-practice work, or None]  
**Scale/Scope**: [domain-specific, e.g., 10k users, 1M LOC, 50 screens or NEEDS CLARIFICATION]

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- [ ] Spec-first evidence exists: the plan references an approved `spec.md` with
      prioritized user stories, acceptance scenarios, measurable requirements,
      assumptions, explicit out-of-scope boundaries, and a Constitutional
      Alignment section.
- [ ] Root-constitution impact is mapped: applicable obligations from
      `constitution.md` are listed in Technical Context and carried into the
      design, especially code quality, testing, parity, performance, and
      compatibility constraints.
- [ ] Story slices remain independently valuable: each planned user story can be
      implemented, validated, and demonstrated on its own, or any dependency is
      explicitly justified in Complexity Tracking.
- [ ] All unknowns are surfaced: every open question is marked `NEEDS CLARIFICATION`
      in Technical Context or resolved in `research.md`; no silent assumptions remain.
- [ ] Validation and evidence are defined: each user story has a clear
      verification approach, and the plan identifies applicable automated tests,
      compatibility checks, documentation-parity work, and benchmark evidence.
- [ ] Relevant skills are identified: the plan lists the project/global skills
      that MUST be consulted before implementation or best-practice-heavy work,
      or explicitly records that no specialized skill applies.
- [ ] High-risk MeshLink changes are called out: public API, crypto-provider,
      wire-format, runtime-dependency, minimum-platform, and cross-platform
      event/error/state changes are explicitly marked when touched.
- [ ] Artifact sync is planned: `research.md`, `data-model.md`, `quickstart.md`,
      `contracts/`, tasks, and agent guidance updates are listed where
      applicable, and the plan avoids assistant-vendor-specific instructions.

## Project Structure

### Documentation (this feature)

```text
specs/[###-feature]/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)
<!--
  ACTION REQUIRED: Replace the placeholder tree below with the concrete layout
  for this feature. Delete unused options and expand the chosen structure with
  real paths (e.g., apps/admin, packages/something). The delivered plan must
  not include Option labels.
-->

```text
# [REMOVE IF UNUSED] Option 1: Single project (DEFAULT)
src/
├── models/
├── services/
├── cli/
└── lib/

tests/
├── contract/
├── integration/
└── unit/

# [REMOVE IF UNUSED] Option 2: Web application (when "frontend" + "backend" detected)
backend/
├── src/
│   ├── models/
│   ├── services/
│   └── api/
└── tests/

frontend/
├── src/
│   ├── components/
│   ├── pages/
│   └── services/
└── tests/

# [REMOVE IF UNUSED] Option 3: Mobile + API (when "iOS/Android" detected)
api/
└── [same as backend above]

ios/ or android/
└── [platform-specific structure: feature modules, UI flows, platform tests]

# [REMOVE IF UNUSED] Option 4: Kotlin Multiplatform library (MeshLink-style)
meshlink/
├── build.gradle.kts
├── src/
│   ├── commonMain/
│   ├── commonTest/
│   ├── androidMain/
│   ├── androidUnitTest/
│   ├── iosMain/
│   └── iosTest/
└── api/

benchmarks/
└── [benchmark suites and baselines]
```

**Structure Decision**: [Document the selected structure and reference the real
directories captured above]

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., 4th project] | [current need] | [why 3 projects insufficient] |
| [e.g., Repository pattern] | [specific problem] | [why direct DB access insufficient] |
