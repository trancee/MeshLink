# Tasks: MeshLink Reference App

**Input**: Design documents from `/specs/002-meshlink-reference-app/`  
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

This file is the execution ledger for the reference app work. It groups the
implementation by user story so each slice stays independently testable and
reviewable.

**Validation**: Every user story MUST include validation tasks. Because this feature changes executable mobile app behavior, each story below includes automated coverage and manual or quickstart validation. If implementation touches `:meshlink`, also run the applicable API-compatibility, platform-test, and benchmark gates before completion.

**Skill Use**: Before starting implementation or best-practice-heavy work, read the relevant skills listed in `specs/002-meshlink-reference-app/plan.md`: kotlin-multiplatform, compose-multiplatform, kmp-ios-integration, gradle-build-tool, frontend-design, make-interfaces-feel-better, write-docs. Completion summaries MUST include a `Skills Used` section.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- Shared KMP module: `meshlink-reference/src/commonMain/`, `meshlink-reference/src/commonTest/`, `meshlink-reference/src/androidMain/`, `meshlink-reference/src/iosMain/`, `meshlink-reference/src/iosTest/`
- Nested Android host module: `meshlink-reference/android/src/main/`, `meshlink-reference/android/src/androidTest/`
- iOS host project: `meshlink-reference/ios/`
- Existing SDK and benchmark modules: `meshlink/`, `benchmarks/`
- Feature artifacts: `specs/002-meshlink-reference-app/`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the new root app module, register its dependencies, and establish Android/iOS host scaffolding.

- [X] T001 Add Compose Multiplatform, Compose resources, navigation, lifecycle, and serialization versions to `gradle/libs.versions.toml`
- [X] T002 Register the `:meshlink-reference` module and its project directory in `settings.gradle.kts`
- [X] T003 Add root plugin and Kover aggregation wiring for `:meshlink-reference` in `build.gradle.kts`
- [X] T004 Create the Kotlin Multiplatform app build script in `meshlink-reference/build.gradle.kts`
- [X] T005 [P] Create Android app scaffold files in `meshlink-reference/android/src/main/AndroidManifest.xml` and `meshlink-reference/android/src/main/res/values/strings.xml`
- [X] T006 [P] Create the iOS host project spec and support files in `meshlink-reference/ios/project.yml` and `meshlink-reference/ios/ReferenceApp/Support/Info.plist`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Build the shared app shell, domain models, and platform integration seams that every story relies on.

**⚠️ CRITICAL**: No user story work should start until this phase is complete.

- [X] T007 [P] Create workflow catalog, session, and peer model files in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/navigation/ReferenceWorkflowCatalog.kt`, `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/model/ReferenceSession.kt`, and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/model/PeerSnapshot.kt`
- [X] T008 [P] Create timeline, artifact, and retained-history model files in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/model/TimelineEntry.kt`, `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/model/SessionArtifact.kt`, and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/model/RecentSessionHistory.kt`
- [X] T009 [P] Create JSON serialization and repository interfaces in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/session/ReferenceJson.kt`, `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/session/SessionHistoryRepository.kt`, and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/session/SessionArtifactSerializer.kt`
- [X] T010 [P] Create the shared MeshLink coordinator and platform service contracts in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/meshlink/ReferenceMeshLinkController.kt` and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/platform/PlatformServices.kt`
- [X] T011 [P] Create the editorial design system and shared resource catalog in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/design/ReferenceTheme.kt` and `meshlink-reference/src/commonMain/composeResources/values/strings.xml`
- [X] T012 Create the shared app shell and platform bootstraps in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/app/ReferenceApp.kt`, `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/navigation/ReferenceNavHost.kt`, `meshlink-reference/android/src/main/kotlin/ch/trancee/meshlink/reference/MainActivity.kt`, `meshlink-reference/src/iosMain/kotlin/ch/trancee/meshlink/reference/ReferenceViewController.kt`, and `meshlink-reference/ios/ReferenceApp/ReferenceApp.swift`

**Checkpoint**: Foundation ready — guided, advanced, timeline, and parity slices can now proceed.

---

## Phase 3: User Story 1 - Complete a guided first exchange (Priority: P1) 🎯 MVP

**Goal**: Deliver a polished first-run experience that guides an operator through readiness, discovery, first send proof, and solo exploration fallback.

**Independent Test**: Install the app on two supported devices, follow the guided flow, complete a first send, and confirm that the timeline shows discovery, trust, and delivery proof. Repeat on one device only and confirm the clearly labeled solo exploration fallback does not claim live proof.

### Validation for User Story 1 (REQUIRED) ⚠️

- [X] T013 [P] [US1] Add guided first-exchange state tests in `meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/guided/GuidedFirstExchangeViewModelTest.kt`
- [X] T014 [P] [US1] Add reference-ui contract and Compose UI tests for guided and solo surfaces in `meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/guided/GuidedFirstExchangeScreenTest.kt`
- [X] T015 [US1] Validate the guided first exchange and solo fallback against `specs/002-meshlink-reference-app/quickstart.md` and `specs/002-meshlink-reference-app/contracts/reference-ui.md`, including stopwatch-based evidence that the live first-exchange proof path completes within 5 minutes on Android and iOS.

### Implementation for User Story 1

- [X] T016 [P] [US1] Implement readiness and blocker state models in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/guided/ReadinessState.kt` and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/guided/ReadinessChecker.kt`
- [X] T017 [P] [US1] Implement guided first-exchange orchestration in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/guided/GuidedFirstExchangeViewModel.kt`
- [X] T018 [P] [US1] Implement guided and solo surface composables in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/guided/GuidedFirstExchangeScreen.kt` and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/solo/SoloExplorationScreen.kt`
- [X] T019 [P] [US1] Implement platform-specific readiness explainers in `meshlink-reference/src/androidMain/kotlin/ch/trancee/meshlink/reference/platform/ReadinessExplainer.kt` and `meshlink-reference/src/iosMain/kotlin/ch/trancee/meshlink/reference/platform/ReadinessExplainer.kt`
- [X] T020 [US1] Connect live peer discovery, default send, and timeline proof capture in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/guided/GuidedFirstExchangeViewModel.kt` and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/meshlink/ReferenceMeshLinkController.kt`
- [X] T021 [US1] Wire the `main-guided` and `solo-exploration` surfaces into `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/navigation/ReferenceNavHost.kt` and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/app/ReferenceApp.kt`

**Checkpoint**: User Story 1 is complete when a first-time evaluator can reach live first-exchange proof or the clearly labeled solo exploration fallback without hidden setup.

---

## Phase 4: User Story 2 - Explore MeshLink capabilities from one place (Priority: P2)

**Goal**: Expose full public SDK controls, richer peer actions, and a clearly separated lab surface without degrading the guided experience.

**Independent Test**: From the advanced area, change lifecycle state, inspect peers, send a short payload, start a large transfer, reset trust, and open the lab surface while confirming the main experience stays focused on supported behavior.

### Validation for User Story 2 (REQUIRED) ⚠️

- [X] T022 [P] [US2] Add advanced control state tests in `meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/advanced/AdvancedControlsViewModelTest.kt`
- [X] T023 [P] [US2] Add reference-ui contract and Compose UI tests for advanced and lab surfaces in `meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/advanced/AdvancedControlsScreenTest.kt`
- [X] T024 [US2] Validate lifecycle, power, trust-reset, trust-failure or identity-change, unreachable-route, oversized-payload, paused-runtime, and lab flows against `specs/002-meshlink-reference-app/quickstart.md` and `specs/002-meshlink-reference-app/contracts/reference-ui.md`.

### Implementation for User Story 2

- [X] T025 [P] [US2] Implement advanced configuration and action models in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/advanced/AdvancedConfigState.kt` and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/advanced/LifecycleActionState.kt`
- [X] T026 [P] [US2] Implement advanced controls orchestration in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/advanced/AdvancedControlsViewModel.kt`
- [X] T027 [P] [US2] Implement the advanced controls screen in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/advanced/AdvancedControlsScreen.kt`
- [X] T028 [P] [US2] Implement peer detail and send composer components in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/advanced/PeerDetailCard.kt` and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/advanced/SendComposer.kt`, including inline oversized-payload guidance using the supported payload limit.
- [X] T029 [P] [US2] Implement the lab scenario catalog and lab screen shell in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/lab/LabScenarioCatalog.kt` and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/lab/LabScreen.kt`
- [X] T030 [US2] Route lifecycle, power, send, large-transfer, trust-reset, unreachable-route, and paused-runtime recovery states through `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/meshlink/ReferenceMeshLinkController.kt`.
- [X] T031 [US2] Wire the `advanced-controls` and `lab` surfaces into `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/navigation/ReferenceNavHost.kt` and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/app/ReferenceApp.kt`

**Checkpoint**: User Story 2 is complete when integrators can exercise the supported SDK control surface and intentionally enter a separately labeled lab without confusing the product path.

---

## Phase 5: User Story 3 - Inspect and share a technical session timeline (Priority: P3)

**Goal**: Capture a live technical timeline, retain recent local sessions, and export redacted session artifacts with explicit full-payload opt-in.

**Independent Test**: Run a live session, filter the timeline by peer or event family, end the session, reopen it from recent history, and export a redacted artifact that matches the session-artifact contract.

### Validation for User Story 3 (REQUIRED) ⚠️

- [X] T032 [P] [US3] Add timeline filter/search correctness and ≤1-second 2,000-entry performance tests in `meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/timeline/TimelineFilterTest.kt`.
- [X] T033 [P] [US3] Add retained-history pruning tests in `meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/session/JsonSessionHistoryRepositoryTest.kt`
- [X] T034 [P] [US3] Add session-artifact contract serialization tests in `meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/session/SessionArtifactContractTest.kt`
- [X] T035 [US3] Validate recent history retention, per-session delete, clear-all, and redacted export behavior against `specs/002-meshlink-reference-app/quickstart.md` and `specs/002-meshlink-reference-app/contracts/session-artifact.md`.

### Implementation for User Story 3

- [X] T036 [P] [US3] Implement the technical timeline store and filter state in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/timeline/TechnicalTimelineStore.kt` and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/timeline/TimelineFilters.kt`
- [X] T037 [P] [US3] Implement JSON-backed recent-session retention in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/session/JsonSessionHistoryRepository.kt`
- [X] T038 [P] [US3] Implement redacted export and full-payload opt-in serialization in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/session/JsonSessionArtifactSerializer.kt` and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/session/ExportPayloadPolicy.kt`
- [X] T039 [P] [US3] Implement timeline, recent-history, and export UI surfaces in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/timeline/TechnicalTimelineScreen.kt`, `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/history/RecentSessionHistoryScreen.kt`, and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/export/ExportSessionDialog.kt`
- [X] T040 [US3] Connect live session capture, retained-session reopen, and clear/delete actions in `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/timeline/TechnicalTimelineStore.kt`, `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/history/RecentSessionHistoryScreen.kt`, and `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/navigation/ReferenceNavHost.kt`.

**Checkpoint**: User Story 3 is complete when QA/support users can inspect live and retained sessions, then export a contract-compliant redacted artifact without exposing full payloads by default.

---

## Phase 6: User Story 4 - Compare the same reference flows on Android and iOS (Priority: P4)

**Goal**: Make the named workflows, labels, blockers, and navigation entry points behave the same way on Android and iOS.

**Independent Test**: Open the same guided, advanced, lab, timeline, and retained-history flows on Android and iOS and confirm that the app uses the same surface IDs, operator copy, blocker meaning, and navigation structure.

### Validation for User Story 4 (REQUIRED) ⚠️

- [X] T041 [P] [US4] Add shared workflow catalog and diagnostic-category parity tests in `meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/parity/WorkflowCatalogParityTest.kt`
- [X] T042 [P] [US4] Add Android and iOS parity smoke tests in `meshlink-reference/android/src/androidTest/kotlin/ch/trancee/meshlink/reference/ReferenceAppSmokeTest.kt` and `meshlink-reference/src/iosTest/kotlin/ch/trancee/meshlink/reference/ReferenceAppSmokeTest.kt`
- [X] T043 [US4] Validate named workflow, label, blocker, and diagnostic-category parity against `specs/002-meshlink-reference-app/quickstart.md` and `specs/002-meshlink-reference-app/contracts/reference-ui.md`

### Implementation for User Story 4

- [X] T044 [P] [US4] Normalize shared workflow labels and operator copy in `meshlink-reference/src/commonMain/composeResources/values/strings.xml`
- [X] T045 [P] [US4] Commit the iOS host project and host-side tests in `meshlink-reference/ios/ReferenceApp.xcodeproj/project.pbxproj` and `meshlink-reference/ios/ReferenceAppTests/ReferenceAppParityTests.swift`
- [X] T046 [P] [US4] Align Android platform services and blocker messaging in `meshlink-reference/src/androidMain/kotlin/ch/trancee/meshlink/reference/platform/PlatformServices.kt`
- [X] T047 [P] [US4] Align iOS platform services and blocker messaging in `meshlink-reference/src/iosMain/kotlin/ch/trancee/meshlink/reference/platform/PlatformServices.kt`
- [X] T048 [US4] Wire identical surface IDs and entry routes across `meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/navigation/ReferenceNavHost.kt`, `meshlink-reference/android/src/main/kotlin/ch/trancee/meshlink/reference/MainActivity.kt`, and `meshlink-reference/src/iosMain/kotlin/ch/trancee/meshlink/reference/ReferenceViewController.kt`

**Checkpoint**: User Story 4 is complete when Android and iOS present the same named reference experience, with platform differences limited to clearly explained setup constraints.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Finish documentation, regenerate committed host artifacts, and run the full gate set.

- [X] T049 [P] Create the operator-facing run guide in `meshlink-reference/README.md` and link it from `docs/README.md` and `README.md`
- [X] T050 Sync delivered behavior across `specs/002-meshlink-reference-app/spec.md`, `specs/002-meshlink-reference-app/plan.md`, `specs/002-meshlink-reference-app/tasks.md`, `specs/002-meshlink-reference-app/quickstart.md`, and `specs/002-meshlink-reference-app/contracts/`
- [X] T051 Run formatting, static analysis, coverage verification, shared tests, and platform smoke suites for `meshlink-reference/build.gradle.kts`, `meshlink-reference/src/**`, and `meshlink-reference/ios/**`
- [X] T052 [P] Regenerate the committed iOS host project from `meshlink-reference/ios/project.yml`, validate `meshlink-reference/ios/project.yml` with `yamllint`, and verify `meshlink-reference/ios/ReferenceApp.xcodeproj/project.pbxproj`
- [X] T053 [P] If implementation touched `:meshlink`, run `apiCheck`, `:meshlink:jvmTest`, `:meshlink:iosSimulatorArm64Test`, and `:benchmarks:jvmBenchmark`, then update `benchmarks/README.md` only if new benchmark evidence is required
- [X] T054 [P] Update Android/iOS workflow guidance in `docs/README.md` and `meshlink-reference/README.md` to preserve documentation parity
- [x] T055 Run the full two-device guided quickstart and redacted-export validation in `specs/002-meshlink-reference-app/quickstart.md`, recording pass/fail evidence for SC-001 (≤5 minutes) and SC-004 (≤1 second filter/search and ≤60 seconds export for a session containing 2,000 timeline entries).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — blocks all story work
- **User Story 1 (Phase 3)**: Depends on Foundational completion
- **User Story 2 (Phase 4)**: Depends on Foundational completion
- **User Story 3 (Phase 5)**: Depends on User Story 1 for live-session proof inputs and on Foundational completion for shared storage/event infrastructure
- **User Story 4 (Phase 6)**: Depends on User Stories 1–3 because it proves parity across the named guided, advanced, timeline, history, and lab flows
- **Polish (Phase 7)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: First deliverable and MVP slice; no dependency on later stories
- **User Story 2 (P2)**: Shares the same app shell and controller but can start independently after Foundational
- **User Story 3 (P3)**: Needs a functioning live session to capture/export, so it should begin after User Story 1 is stable
- **User Story 4 (P4)**: Final parity slice; verifies the cross-platform experience after the prior stories exist

### Within Each User Story

- Validation tasks come before implementation tasks
- Applicable skills from `plan.md` must be consulted before implementation or best-practice-heavy work begins
- Automated tests or contract checks are required because each story changes executable behavior
- When feasible, observe automated tests failing before implementing the story
- Any file-modifying work batch must end with a Conventional Commit before the next sequential task, phase, or governed command continues unless an enabled auto-commit hook performs that commit
- Shared models/state before view models, view models before screens, screens before platform wiring, and platform wiring before manual validation

### Parallel Opportunities

- Setup tasks T005 and T006 can run in parallel once T001–T004 are complete
- Foundational tasks T007–T011 can run in parallel after setup because they touch distinct shared files
- After Foundational, User Story 1 and User Story 2 can begin in parallel if staffing allows
- User Story 3 validation and serialization tasks can run in parallel once the shared session models are stable
- User Story 4 parity smoke tests and platform-alignment tasks can run in parallel once the main surfaces exist
- Polish tasks T049, T052, T053, and T054 can run in parallel while the final quickstart validation is prepared

---

## Parallel Example: User Story 1

```bash
# Validation tasks
Task: "Add guided first-exchange state tests in meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/guided/GuidedFirstExchangeViewModelTest.kt"
Task: "Add reference-ui contract and Compose UI tests for guided and solo surfaces in meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/guided/GuidedFirstExchangeScreenTest.kt"

# Implementation tasks
Task: "Implement readiness and blocker state models in meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/guided/ReadinessState.kt and meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/guided/ReadinessChecker.kt"
Task: "Implement guided and solo surface composables in meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/guided/GuidedFirstExchangeScreen.kt and meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/solo/SoloExplorationScreen.kt"
Task: "Implement platform-specific readiness explainers in meshlink-reference/src/androidMain/kotlin/ch/trancee/meshlink/reference/platform/ReadinessExplainer.kt and meshlink-reference/src/iosMain/kotlin/ch/trancee/meshlink/reference/platform/ReadinessExplainer.kt"
```

---

## Parallel Example: User Story 2

```bash
# Validation tasks
Task: "Add advanced control state tests in meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/advanced/AdvancedControlsViewModelTest.kt"
Task: "Add reference-ui contract and Compose UI tests for advanced and lab surfaces in meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/advanced/AdvancedControlsScreenTest.kt"

# Implementation tasks
Task: "Implement advanced configuration and action models in meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/advanced/AdvancedConfigState.kt and meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/advanced/LifecycleActionState.kt"
Task: "Implement advanced controls screen in meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/advanced/AdvancedControlsScreen.kt"
Task: "Implement the lab scenario catalog and lab screen shell in meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/lab/LabScenarioCatalog.kt and meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/lab/LabScreen.kt"
```

---

## Parallel Example: User Story 3

```bash
# Validation tasks
Task: "Add timeline filter/search correctness and ≤1-second 2,000-entry performance tests in meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/timeline/TimelineFilterTest.kt"
Task: "Add retained-history pruning tests in meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/session/JsonSessionHistoryRepositoryTest.kt"
Task: "Add session-artifact contract serialization tests in meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/session/SessionArtifactContractTest.kt"

# Implementation tasks
Task: "Implement JSON-backed recent-session retention in meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/session/JsonSessionHistoryRepository.kt"
Task: "Implement redacted export and full-payload opt-in serialization in meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/session/JsonSessionArtifactSerializer.kt and meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/session/ExportPayloadPolicy.kt"
Task: "Implement timeline, recent-history, and export UI surfaces in meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/timeline/TechnicalTimelineScreen.kt, meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/history/RecentSessionHistoryScreen.kt, and meshlink-reference/src/commonMain/kotlin/ch/trancee/meshlink/reference/export/ExportSessionDialog.kt"
```

---

## Parallel Example: User Story 4

```bash
# Validation tasks
Task: "Add shared workflow catalog and diagnostic-category parity tests in meshlink-reference/src/commonTest/kotlin/ch/trancee/meshlink/reference/parity/WorkflowCatalogParityTest.kt"
Task: "Add Android and iOS parity smoke tests in meshlink-reference/android/src/androidTest/kotlin/ch/trancee/meshlink/reference/ReferenceAppSmokeTest.kt and meshlink-reference/src/iosTest/kotlin/ch/trancee/meshlink/reference/ReferenceAppSmokeTest.kt"

# Implementation tasks
Task: "Commit the iOS host project and host-side tests in meshlink-reference/ios/ReferenceApp.xcodeproj/project.pbxproj and meshlink-reference/ios/ReferenceAppTests/ReferenceAppParityTests.swift"
Task: "Align Android platform services and blocker messaging in meshlink-reference/src/androidMain/kotlin/ch/trancee/meshlink/reference/platform/PlatformServices.kt"
Task: "Align iOS platform services and blocker messaging in meshlink-reference/src/iosMain/kotlin/ch/trancee/meshlink/reference/platform/PlatformServices.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: Run the guided first-exchange quickstart on real devices and confirm solo exploration remains non-authoritative
5. Demo the MVP before adding advanced controls, retained history, or parity polish

### Incremental Delivery

1. Finish Setup + Foundational to stabilize the shared app shell
2. Deliver User Story 1 as the first demoable slice
3. Add User Story 2 for the technical control surface and lab separation
4. Add User Story 3 for timeline, retained history, and export workflows
5. Add User Story 4 to finalize Android/iOS parity and committed iOS host behavior
6. Use Phase 7 to sync docs, rerun gates, and validate the complete quickstart

### Parallel Team Strategy

With multiple developers:

1. One developer handles Setup/Foundational Gradle and iOS-host scaffolding
2. After Foundational completes:
   - Developer A: User Story 1 guided flow
   - Developer B: User Story 2 advanced and lab surfaces
   - Developer C: User Story 3 timeline/history/export flows
3. Once the three core stories are stable, one developer finalizes User Story 4 parity while another handles polish and documentation updates

---

## Notes

- [P] tasks touch different files and can run in parallel once their dependencies are met
- Every user story includes automated coverage plus manual/quickstart validation because this feature changes executable app behavior
- The app must preserve the existing MeshLink SDK as the source of truth; if implementation requires `:meshlink` API, wire, diagnostic, or runtime-dependency changes, add the missing BCV, KDoc, benchmark, and compatibility tasks before implementation continues
- Keep proof-only and benchmark-only behavior isolated to the `lab` surface throughout implementation and validation
- Recent local history must remain capped at 20 retained sessions with explicit clear/delete controls
- Exported artifacts must default to payload metadata and redacted previews; full payload export always requires explicit operator opt-in
- Governed file changes require a Conventional Commit before the next task batch, phase, or command continues unless an enabled auto-commit hook performs that commit
