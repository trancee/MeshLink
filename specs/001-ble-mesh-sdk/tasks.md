---
description: "Task list for MeshLink Offline BLE Mesh SDK"
---

# Tasks: MeshLink Offline BLE Mesh SDK

**Input**: Design documents from `/specs/001-ble-mesh-sdk/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/, quickstart.md

**Validation**: Every user story MUST include validation tasks. When work changes code, contracts, or data models, include automated tests or contract/integration checks. For documentation-only work, include manual verification or reader-test tasks. When applicable, add explicit work for the root `constitution.md` obligations: formatting, static analysis, API compatibility, cross-platform parity, Wycheproof or harness coverage, benchmarks, and compatibility validation.

**Skill Use**: Before starting implementation or best-practice-heavy work, read the relevant skills listed in `specs/001-ble-mesh-sdk/plan.md` or otherwise applicable to the task. Completion summaries MUST include a `Skills Used` section or explicitly state `None`.

**Organization**: Tasks are grouped by user story to enable independent implementation and validation of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Kotlin Multiplatform library**: `meshlink/src/commonMain/`, `meshlink/src/commonTest/`, `meshlink/src/androidMain/`, `meshlink/src/iosMain/`, `meshlink/build.gradle.kts`, `benchmarks/`
- **Docs & contracts**: `specs/001-ble-mesh-sdk/`, `docs/explanation/`, `meshlink-sample/android/`, `meshlink-sample/ios/`

## Artifact Roles & Ledger Semantics

- `spec.md` is normative for product requirements, assumptions, success
  criteria, release blocking conditions, and accepted exception wording.
- `plan.md` is normative for implementation constraints, architecture,
  artifact-governance rules, and review surfaces that interpret but do not
  weaken `spec.md`.
- `tasks.md` is the canonical execution plan plus append-only historical
  ledger. It defines work sequencing, traceability, and completion state, but
  task text does not override `spec.md` or `plan.md`.
- Supporting artifacts such as `research.md`, `quickstart.md`, `contracts/`,
  `benchmarks/README.md`, and generated checklists provide evidence or review
  support and must align with the canonical trio above.
- If new remediation work is discovered after a task is complete, append a new
  task ID or follow-up phase rather than reopening or deleting the completed
  line, except to fix obvious clerical mistakes.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the KMP project skeleton, module layout, and baseline build files.

- [X] T001 Create the multi-module Gradle skeleton in `settings.gradle.kts`, `gradle/libs.versions.toml`, and `gradle.properties`
- [X] T002 Create the KMP runtime and benchmark module build files in `meshlink/build.gradle.kts` and `benchmarks/build.gradle.kts`
- [X] T003 [P] Scaffold source-set directories under `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/`, `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/`, `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/`, `meshlink/src/androidUnitTest/kotlin/ch/trancee/meshlink/platform/android/`, `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/`, and `meshlink/src/iosTest/kotlin/ch/trancee/meshlink/platform/ios/`
- [X] T004 [P] Scaffold runnable proof integration projects in `meshlink-sample/android/` and `meshlink-sample/ios/`, including README placeholders, minimal build wiring, and first-message quickstart entry points

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T005 Configure explicit API, ktfmt, Detekt, Kover, Power-assert, BCV, and benchmark plugin wiring in `meshlink/build.gradle.kts`, `benchmarks/build.gradle.kts`, and `gradle.properties`
- [X] T006 [P] Create the public API shell, configuration/result/state types, including `MeshLinkConfig.deliveryRetryDeadline`, and the sealed `MeshLinkException` hierarchy in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/api/` and `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/config/`
- [X] T007 [P] Create platform-neutral abstractions for `BleTransport`, `CryptoProvider`, `SecureStorage`, and `DiagnosticSink`, plus the shared 26-code `DiagnosticCode` catalog and stable diagnostic payload shapes in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transport/`, `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/crypto/`, `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/storage/`, and `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/diagnostics/`
- [X] T008 [P] Create the `MeshEngine` coordinator skeleton and factory entry points in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngine.kt` and `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/api/MeshLink.kt`
- [X] T009 [P] Create canonical harness infrastructure in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/test/MeshTestHarness.kt` and `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/test/VirtualMeshTransport.kt`
- [X] T010 [P] Create pure-Kotlin wire buffer primitives and envelope skeletons in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/wire/ReadBuffer.kt`, `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/wire/WriteBuffer.kt`, and `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/wire/WireEnvelope.kt`

**Checkpoint**: Build, API, transport, diagnostics, and test harness foundations are ready.

---

## Phase 3: User Story 1 - Exchange offline encrypted messages (Priority: P1) 🎯 MVP

**Goal**: Deliver direct offline encrypted messaging with TOFU trust, lifecycle controls, and the same direct-message public API behavior on Android and iOS.

**Independent Validation**: Run a two-peer harness test and both runnable proof integrations to verify first-contact TOFU, direct-message success, restart recovery, and matching direct-message lifecycle, result, and diagnostic behavior on Android and iOS.

### Validation for User Story 1 (REQUIRED) ⚠️

- [X] T011 [P] [US1] Add API contract tests for lifecycle, TOFU pinning, `deliveryRetryDeadline` validation, `MeshLinkException` wrapping, shared `DiagnosticCode` contract stability, trust-failure outcomes, and direct-message Android/iOS parity expectations in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/api/MeshLinkApiContractTest.kt`
- [X] T012 [P] [US1] Add two-peer offline direct messaging integration tests for direct send/receive, restart recovery, and end-to-end payload confidentiality in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/DirectMessagingIntegrationTest.kt`
- [X] T013 [US1] Validate the first-message flow, including default `deliveryRetryDeadline` guidance and proof-integration behavior, against `specs/001-ble-mesh-sdk/quickstart.md`, `meshlink-sample/android/`, and `meshlink-sample/ios/`, then record any corrections directly in the affected docs

### Implementation for User Story 1

- [X] T014 [P] [US1] Implement `LocalIdentity`, `TrustRecord`, advertisement key-hash derivation support, and the TOFU trust store in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/identity/` and `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/trust/`
- [X] T015 [P] [US1] Implement the Noise XX hop-handshake manager, Noise K end-to-end payload sealing/opening, and trust-failure diagnostic emission in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/crypto/` and `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/diagnostics/`
- [X] T016 [P] [US1] Implement Android direct BLE transport, secure storage, and the Android factory in `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/AndroidBleTransport.kt`, `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/AndroidSecureStorage.kt`, and `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/AndroidMeshLinkFactory.kt`
- [X] T017 [P] [US1] Implement iOS direct BLE transport, secure storage, and the iOS factory in `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt`, `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosSecureStorage.kt`, and `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosMeshLinkFactory.kt`
- [X] T018 [US1] Integrate direct send/receive lifecycle flows with hop-to-hop and end-to-end security layering in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngine.kt`, `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/api/MeshLink.kt`, and `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transport/`, then implement and document the proof flow in `meshlink-sample/android/` and `meshlink-sample/ios/`

**Checkpoint**: User Story 1 should deliver first-contact trust, direct offline messaging, restart recovery, trust-failure visibility, two-layer security, and direct-message parity evidence without servers or accounts.

---

## Phase 4: User Story 2 - Relay large messages across multiple hops (Priority: P2)

**Goal**: Add proactive routing, configurable no-route retry deadlines, and bounded large-transfer delivery so messages can cross multi-hop topologies beyond direct BLE range.

**Independent Validation**: Run a three-node harness that proves route
propagation, reconvergence, configurable no-route retry deadline behavior,
bounded, jittered exponential backoff while no route exists, immediate retry on
route availability, and successful 64 KiB transfer with selective
retransmission semantics.

### Validation for User Story 2 (REQUIRED) ⚠️

- [X] T019 [P] [US2] Add wire contract tests driven by `specs/001-ble-mesh-sdk/contracts/wire-envelope.md` in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/wire/WireEnvelopeContractTest.kt`
- [X] T020 [P] [US2] Add multi-node routing, reconvergence, and relay-confidentiality integration tests in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/MeshRoutingIntegrationTest.kt`
- [X] T021 [P] [US2] Add bounded large-transfer, route-change resume, duplicate/out-of-order chunk, partial-ACK, and 64 KiB limit integration tests in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/LargeTransferIntegrationTest.kt`
- [X] T022 [US2] Validate multi-hop send, configured no-route retry deadline behavior, bounded exponential-backoff retry scheduling, immediate retry on route availability, and size-limit behavior against `specs/001-ble-mesh-sdk/quickstart.md` and `specs/001-ble-mesh-sdk/contracts/wire-envelope.md`

### Implementation for User Story 2

- [X] T023 [P] [US2] Implement route tables, seqno freshness, feasibility checks, route digests, and differential updates in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/routing/`
- [X] T024 [P] [US2] Implement peer lifecycle coordination and route cleanup in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/presence/` and `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/`
- [X] T025 [P] [US2] Implement FlatBuffers-compatible message, routing, and transfer codecs in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/wire/`
- [X] T026 [P] [US2] Implement `TransferSession`, ACK scoreboard, configurable delivery-deadline plumbing, and pre-transfer size-limit rejection in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transfer/`
- [X] T027 [US2] Implement no-route retry scheduling with bounded, jittered exponential backoff and immediate retry on route availability in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/DeliveryRetryScheduler.kt`, integrate it through `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngine.kt`, `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transport/`, and `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/diagnostics/`, then update `meshlink-sample/android/README.md` and `meshlink-sample/ios/README.md` for multi-hop evidence

**Checkpoint**: User Story 2 should deliver proactive routing, explicit unreachable/expired outcomes, and bounded large-payload delivery across at least one relay hop.

---

## Phase 5: User Story 3 - Adapt to battery state while preserving predictable SDK behavior (Priority: P3)

**Goal**: Add shared power policy and enforce Android/iOS parity for lifecycle, diagnostics, and public behavior under changing battery conditions.

**Independent Validation**: Simulate battery-state transitions and cross-platform SDK lifecycle flows, then confirm the same public states, diagnostics, and low-power behavior on Android and iOS.

### Validation for User Story 3 (REQUIRED) ⚠️

- [X] T028 [P] [US3] Add shared power-tier, hysteresis, bootstrap, and region-clamp tests in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/power/PowerPolicyTest.kt`
- [X] T029 [P] [US3] Add parity tests for lifecycle states, the 26-code diagnostic catalog, severity tiers, payload shapes, `MeshLinkException` categories, and error categories in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/api/CrossPlatformParityTest.kt`
- [X] T030 [US3] Validate Android/iOS parity and power-mode expectations against `specs/001-ble-mesh-sdk/quickstart.md` and `specs/001-ble-mesh-sdk/contracts/meshlink-api.md`

### Implementation for User Story 3

- [X] T031 [P] [US3] Implement shared `PowerPolicy`, hysteresis, bootstrap, and regulatory clamp logic in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/power/`
- [X] T032 [P] [US3] Implement Android battery and transport tuning hooks in `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/AndroidPowerMonitor.kt` and `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/AndroidBleTransport.kt`
- [X] T033 [P] [US3] Implement iOS battery and transport tuning hooks in `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosPowerMonitor.kt` and `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt`
- [X] T034 [US3] Normalize diagnostics, the 26-code diagnostic catalog, `MeshLinkException` KDoc, `specs/001-ble-mesh-sdk/contracts/meshlink-api.md`, and parity docs in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/api/`, `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/diagnostics/`, `meshlink-sample/android/README.md`, `meshlink-sample/ios/README.md`, and `specs/001-ble-mesh-sdk/quickstart.md`

**Checkpoint**: User Story 3 should deliver shared power behavior and the same developer-visible lifecycle and diagnostic semantics on Android and iOS.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Close the constitutions’ quality gates, benchmark obligations, and documentation sync work.

- [X] T035 [P] Sync `specs/001-ble-mesh-sdk/spec.md`, `specs/001-ble-mesh-sdk/plan.md`, `specs/001-ble-mesh-sdk/tasks.md`, and `specs/001-ble-mesh-sdk/quickstart.md` with the implemented behavior
- [X] T036 Run format, static analysis, coverage, and API compatibility gates against `settings.gradle.kts`, `meshlink/build.gradle.kts`, `benchmarks/build.gradle.kts`, and update `meshlink/api/meshlink.api` as needed
- [X] T037 [P] Add remaining Wycheproof vectors and regression coverage in `meshlink/src/commonTest/resources/wycheproof/` and `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/crypto/WycheproofRegressionTest.kt`
- [X] T038 [P] Implement JVM benchmark suites in `benchmarks/src/jvmMain/kotlin/ch/trancee/meshlink/benchmarks/CryptoBenchmark.kt`, `benchmarks/src/jvmMain/kotlin/ch/trancee/meshlink/benchmarks/RoutingBenchmark.kt`, `benchmarks/src/jvmMain/kotlin/ch/trancee/meshlink/benchmarks/WireCodecBenchmark.kt`, and `benchmarks/src/jvmMain/kotlin/ch/trancee/meshlink/benchmarks/ConvergenceBenchmark.kt`
- [X] T039 [P] Add Android automated proof-app benchmarks for throughput, latency, LOW-power scan duty, and cold start in `meshlink-sample/android/app/src/androidTest/kotlin/ch/trancee/meshlink/proof/android/TransportPerformanceBenchmark.kt`, `meshlink-sample/android/app/src/androidTest/kotlin/ch/trancee/meshlink/proof/android/PowerProfileBenchmark.kt`, and `meshlink-sample/android/app/src/androidTest/kotlin/ch/trancee/meshlink/proof/android/ColdStartBenchmark.kt`
- [X] T040 [P] Add iOS automated proof-app benchmarks for throughput, latency, LOW-power scan duty, and cold start in `meshlink-sample/ios/ProofBenchmarks/TransportPerformanceBenchmark.swift`, `meshlink-sample/ios/ProofBenchmarks/PowerProfileBenchmark.swift`, and `meshlink-sample/ios/ProofBenchmarks/ColdStartBenchmark.swift`
- [X] T041 [P] Add 8-peer steady-state memory-budget validation and allocation measurement in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/MemoryBudgetIntegrationTest.kt` and `benchmarks/src/jvmMain/kotlin/ch/trancee/meshlink/benchmarks/MemoryBudgetBenchmark.kt`
- [X] T042 [P] Record JVM, Android, iOS, convergence, cold-start, power, and memory baselines in `benchmarks/README.md` and `specs/001-ble-mesh-sdk/research.md`
- [X] T043 [P] Update Android/iOS public API and workflow docs parity in `meshlink-sample/android/README.md`, `meshlink-sample/ios/README.md`, and `docs/explanation/trust-model.md`
- [X] T044 Run the full two-device quickstart validation using `specs/001-ble-mesh-sdk/quickstart.md`, `meshlink-sample/android/`, and `meshlink-sample/ios/`

---

## Phase 7: Follow-up - iOS Throughput Blocker Remediation

**Purpose**: Resolve the remaining iPhone 15 64 KiB single-hop throughput shortfall with instrumented experiments and fresh physical evidence.

- [X] T045 [P] Add iOS large-transfer telemetry for chunk pacing, stream readiness, ACK cadence, and backpressure in `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt` and `meshlink-sample/ios/ProofBenchmarks/BenchmarkTestSupport.swift`
- [X] T046 [P] Add regression coverage for transfer pacing and settlement heuristics in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/LargeTransferIntegrationTest.kt` and `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/transfer/TransferSessionTest.kt`
- [X] T047 Implement one bounded iOS throughput remediation path for L2CAP stream draining and write batching in `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt` and `meshlink-sample/ios/ProofBenchmarks/TransportPerformanceBenchmark.swift`
- [X] T048 Validate the iPhone 15 64 KiB proof benchmark on reference hardware and record pass/fail evidence in `benchmarks/README.md`, `specs/001-ble-mesh-sdk/research.md`, and `specs/001-ble-mesh-sdk/quickstart.md`
- [X] T049 If `specs/001-ble-mesh-sdk/spec.md` SC-004 remains unmet, record the explicit release-decision framing and residual risk in `specs/001-ble-mesh-sdk/spec.md` and `specs/001-ble-mesh-sdk/plan.md`

---

## Phase 8: Follow-up - Cross-Artifact Requirements Remediation

**Purpose**: Close the broad cross-artifact requirement gaps captured in `specs/001-ble-mesh-sdk/checklists/artifacts.md` without reopening completed delivery history.

- [X] T050 [P] Clarify source-of-truth precedence, rerun preservation, and append-only task-ledger rules in `specs/001-ble-mesh-sdk/plan.md` and `specs/001-ble-mesh-sdk/tasks.md`
- [X] T051 [P] Separate benchmark baselines from normative acceptance thresholds and align the iOS throughput risk wording in `specs/001-ble-mesh-sdk/spec.md`, `specs/001-ble-mesh-sdk/plan.md`, and `benchmarks/README.md`
- [X] T052 [P] Clarify proof-integration role, environmental blocker handling, and reviewer evidence expectations in `specs/001-ble-mesh-sdk/spec.md`, `specs/001-ble-mesh-sdk/quickstart.md`, and `specs/001-ble-mesh-sdk/research.md`
- [X] T053 Re-run and resolve the cross-artifact checklist against `specs/001-ble-mesh-sdk/spec.md`, `specs/001-ble-mesh-sdk/plan.md`, `specs/001-ble-mesh-sdk/tasks.md`, and `specs/001-ble-mesh-sdk/checklists/artifacts.md`

---

## Phase 9: Follow-up - iOS CoC Pipeline Remediation

**Purpose**: Reduce iOS L2CAP hot-path overhead and keep the CoreBluetooth CoC transmit pipeline saturated with bounded backpressure instead of synchronous frame-by-frame writes.

- [X] T054 [P] Add a bounded pending-frame window helper and automated coverage in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transport/PendingFrameWindow.kt` and `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/transport/PendingFrameWindowTest.kt`
- [X] T055 Implement iOS transport log-throttling plus a queued CoC writer with bounded frame/byte backpressure in `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt`
- [X] T056 Validate the iOS CoC remediation with fresh Kotlin/Xcode verification output using `./gradlew :meshlink:jvmTest --tests 'ch.trancee.meshlink.transport.PendingFrameWindowTest' :meshlink:compileKotlinIosSimulatorArm64 :meshlink:compileKotlinIosArm64 :meshlink:ktfmtCheck --console=plain` and `xcodebuild -project meshlink-sample/ios/ProofApp.xcodeproj -scheme ProofApp -destination 'id=6C7DD73A-EC9C-46F9-B0B9-DD136F748621' test`

---

## Phase 10: Follow-up - Queued-Writer Physical Evidence Refresh

**Purpose**: Retain fresh post-queued-writer iPhone 15 physical evidence and telemetry findings without reopening the earlier throughput-remediation history.

- [X] T057 [P] Run fresh isolated iPhone 15 64 KiB proof reruns against the Samsung and OPPO reference peers and capture the resulting success/failure evidence
- [X] T058 [P] Run a telemetry-enabled iPhone 15 -> Samsung diagnostic rerun and retain queued-writer/backpressure observations from the proof log
- [X] T059 Update `benchmarks/README.md`, `specs/001-ble-mesh-sdk/research.md`, `specs/001-ble-mesh-sdk/quickstart.md`, and `meshlink-sample/ios/README.md` with the fresh queued-writer physical evidence and current blocker framing

---

## Phase 11: Follow-up - Samsung Soak and Benchmark Semantics

**Purpose**: Separate Samsung handshake instability from bulk-transfer throughput and make proof-app benchmark numbers reflect recipient-confirmed completion instead of sender-side completion timing.

- [X] T060 [P] Run a 10-run iPhone 15 -> Samsung physical soak for warmup-only, 16-byte, 256-byte, and 64 KiB payloads using isolated transient `appId` values and retain the success/failure matrix
- [X] T061 [P] Update the Android and iOS proof apps so benchmark throughput logs are emitted only after recipient-confirmed proof receipts rather than sender-side completion timing
- [X] T062 Re-run the physical OPPO and Samsung benchmark matrix with the recipient-confirmed benchmark protocol and retain the resulting evidence in the durable docs
- [X] T063 Investigate and, if feasible, prototype an Android GATT server + iOS GATT client bulk fallback path for proof benchmarking when the L2CAP path remains capped around ~20 KB/s

---

## Phase 12: Follow-up - Release Blocker Framing Tightening

**Purpose**: Tighten the spec/plan release-blocker framing so reviewers and stakeholders distinguish normative iOS throughput non-conformance from the newer recipient-confirmed proof-completion failure and from proof-only fallback evidence.

- [X] T064 Tighten the explicit blocker framing in `specs/001-ble-mesh-sdk/spec.md` and `specs/001-ble-mesh-sdk/plan.md` to separate `SC-004` throughput non-conformance, recipient-confirmed MeshLink proof-completion failure, and proof-only GATT fallback evidence

---

## Phase 13: Follow-up - Recipient-Confirmed Return-Path Stabilization

**Purpose**: Isolate why passive-peer proof receipts still expire `UNREACHABLE` on the return path even when the sender-to-passive leg appears healthy enough to trigger receipt logic.

- [X] T065 Add token-correlated proof-receipt diagnostics in `meshlink-sample/android/app/src/main/kotlin/ch/trancee/meshlink/proof/android/MainActivity.kt` and `meshlink-sample/ios/ProofApp/ProofViewModel.swift` so sender/passive logs capture token, peer, current state, known peers, and recent peer/diagnostic context at receipt send / receipt timeout boundaries
- [X] T066 Re-run one minimal recipient-confirmed physical repro (iPhone 15 -> Samsung, 256-byte MeshLink path) with the new token-correlated diagnostics and retain the resulting sender/passive evidence in `benchmarks/README.md` and `specs/001-ble-mesh-sdk/research.md`
- [X] T067 Investigate and implement one bounded return-path stability remediation for passive-peer proof receipts on the MeshLink path
- [X] T068 Re-run the recipient-confirmed Samsung/OPPO matrix after the bounded remediation and retain the resulting evidence in the durable docs

---

## Phase 14: Follow-up - Reverse-Path Peer Reappearance Investigation

**Purpose**: Determine whether the remaining recipient-confirmed failures are primarily caused by missing passive-peer rediscovery, route non-reappearance, or reverse-direction handshake collapse after the forward payload leg completes.

- [X] T069 Add reverse-path peer reappearance / route-availability timeline diagnostics in the shared runtime, relevant Android/iOS transport files, and both proof apps so receipt-window logs show when the passive peer rediscovers the sender, when a usable route for that peer reappears or expires, and which state transition immediately precedes `ReceiptTimeout` / `UNREACHABLE`
- [ ] T070 [P] Re-run a minimal iPhone 15 -> Samsung 256-byte recipient-confirmed repro with the new reverse-path diagnostics and retain the paired sender/passive evidence in `benchmarks/README.md` and `specs/001-ble-mesh-sdk/research.md`
- [ ] T071 [P] Re-run a minimal iPhone 15 -> OPPO 256-byte recipient-confirmed repro with the same diagnostics and retain the paired sender/passive evidence in `benchmarks/README.md` and `specs/001-ble-mesh-sdk/research.md`
- [ ] T072 Compare the Samsung and OPPO reverse-path timelines, document the narrowed blocker hypothesis in `specs/001-ble-mesh-sdk/research.md`, and identify the next bounded remediation target if the evidence isolates a concrete peer-reappearance or route-availability failure mode

---

## Phase 15: Follow-up - Compatibility and Acceptance-Criteria Clarification

**Purpose**: Close the remaining analysis findings on transport-scope wording, deployed-wire compatibility validation, storage/redaction specificity, measurable LOW-power delivery, quickstart timing evidence, and stale sequencing guidance without reopening completed delivery history.

- [ ] T073 [P] Clarify the normative transport scope in `specs/001-ble-mesh-sdk/spec.md` and `specs/001-ble-mesh-sdk/plan.md` so the current release path remains MeshLink L2CAP-first and the retained GATT prototype is explicitly proof-only / non-conformance evidence unless a later spec amendment promotes it.
- [ ] T074 Add explicit deployed-wire backward-compatibility validation for `FR-016` in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/wire/WireEnvelopeContractTest.kt`, `specs/001-ble-mesh-sdk/contracts/wire-envelope.md`, and `specs/001-ble-mesh-sdk/research.md`, then retain the compatibility evidence.
- [ ] T075 [P] Tighten trust-record timestamp and persisted-diagnostic redaction requirements in `specs/001-ble-mesh-sdk/spec.md` and `specs/001-ble-mesh-sdk/plan.md`, then add automated coverage in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/api/MeshLinkApiContractTest.kt`, `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/DirectMessagingIntegrationTest.kt`, and the affected storage / trust code paths.
- [ ] T076 [P] Replace the vague LOW-power delivery clause in `specs/001-ble-mesh-sdk/spec.md` with a measurable target, mirror it in `specs/001-ble-mesh-sdk/plan.md`, and add Android/iOS proof-benchmark coverage in `meshlink-sample/android/app/src/androidTest/kotlin/ch/trancee/meshlink/proof/android/PowerProfileBenchmark.kt` and `meshlink-sample/ios/ProofBenchmarks/PowerProfileBenchmark.swift`.
- [ ] T077 Define the timed measurement method for `SC-001` in `specs/001-ble-mesh-sdk/spec.md` and `specs/001-ble-mesh-sdk/quickstart.md`, then run and retain a timed quickstart reader-test in `specs/001-ble-mesh-sdk/research.md`.
- [ ] T078 Refresh `specs/001-ble-mesh-sdk/tasks.md` dependency and incremental-delivery guidance so it reflects appended follow-up phases through Phase 15 while preserving the append-only ledger rules.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies; start immediately.
- **Foundational (Phase 2)**: Depends on Setup and blocks all user stories.
- **User Story 1 (Phase 3)**: Starts after Foundational and delivers the MVP.
- **User Story 2 (Phase 4)**: Depends on User Story 1 runtime skeleton and adds routing/transfer.
- **User Story 3 (Phase 5)**: Depends on the shared runtime from User Story 1 and may reuse routing/diagnostic work from User Story 2.
- **Polish (Phase 6)**: Runs after the desired user stories are complete.
- **Follow-up Throughput Remediation (Phase 7)**: Runs after Phase 6 when `SC-004` still lacks passing iPhone-class evidence.
- **Follow-up Artifact Remediation (Phase 8)**: Runs after Phase 7 to keep the canonical artifacts aligned with the latest benchmark outcome and blocker framing.
- **Follow-up iOS CoC Pipeline Remediation (Phase 9)**: Runs after Phase 8 when the iOS L2CAP path still needs bounded hot-path remediation.
- **Follow-up Queued-Writer Physical Evidence Refresh (Phase 10)**: Runs after Phase 9 to retain fresh physical evidence and telemetry for the queued-writer path.
- **Follow-up Samsung Soak and Benchmark Semantics (Phase 11)**: Runs after Phase 10 to separate handshake instability from bulk throughput and enforce recipient-confirmed proof semantics.
- **Follow-up Release Blocker Framing Tightening (Phase 12)**: Runs after Phase 11 when the spec/plan blocker language must distinguish throughput non-conformance, proof-completion failure, and proof-only fallback evidence.
- **Follow-up Recipient-Confirmed Return-Path Stabilization (Phase 13)**: Runs after Phase 12 to diagnose and remediate passive-peer proof-receipt failures.
- **Follow-up Reverse-Path Peer Reappearance Investigation (Phase 14)**: Runs after Phase 13 and consumes the passive-retry matrix evidence to distinguish missing peer rediscovery from reverse-path route / handshake non-reappearance.
- **Follow-up Compatibility and Acceptance-Criteria Clarification (Phase 15)**: Runs after Phase 14, or in parallel with `T070`–`T072` where only artifact clarification / offline validation work is involved.

### User Story Dependencies

- **User Story 1 (P1)**: No dependency on later stories.
- **User Story 2 (P2)**: Builds on the public API, trust, diagnostics, and direct transport delivered in US1.
- **User Story 3 (P3)**: Builds on the public API and platform transports delivered in US1; it should not require US2 to be demonstrable.

### Within Each User Story

- Validation tasks come first.
- Relevant skills from `specs/001-ble-mesh-sdk/plan.md` MUST be consulted before implementation starts.
- Common models and state machines precede transport integration.
- Platform-specific work proceeds after common abstractions are stable.
- Story docs and quickstart updates happen before the story is considered complete.

### Parallel Opportunities

- `T003` and `T004` can run in parallel after build-file scaffolding exists.
- `T006`–`T010` can run in parallel after the root build is in place.
- In US1, `T014`–`T017` can run in parallel once validation tests exist.
- In US2, `T023`–`T026` can run in parallel because routing, presence, wire, and transfer live in separate packages.
- In US3, `T031`–`T033` can run in parallel once parity tests are written.
- In Polish, `T037`–`T043` can run in parallel before the final quickstart validation.
- In Follow-up Phase 7, `T045` and `T046` can run in parallel before `T047` and `T048`.
- In Follow-up Phase 8, `T050`–`T052` can run in parallel before `T053`.

---

## Parallel Example: User Story 2

```bash
# Launch story-2 validation work together:
Task: "Add multi-node routing and reconvergence integration tests in meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/MeshRoutingIntegrationTest.kt"
Task: "Add bounded large-transfer and 64 KiB limit integration tests in meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/LargeTransferIntegrationTest.kt"

# Launch story-2 subsystem implementation together:
Task: "Implement route tables, seqno freshness, feasibility checks, route digests, and differential updates in meshlink/src/commonMain/kotlin/ch/trancee/meshlink/routing/"
Task: "Implement `TransferSession`, ACK scoreboard, configurable delivery-deadline plumbing, and pre-transfer size-limit rejection in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transfer/`"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: run the two-peer harness test and the quickstart flow
5. Demo the direct offline SDK before expanding scope

### Incremental Delivery

1. Setup + Foundational → shared SDK skeleton ready
2. Add User Story 1 → validate direct offline messaging
3. Add User Story 2 → validate multi-hop and bounded large transfer
4. Add User Story 3 → validate power behavior and cross-platform parity
5. Finish with benchmark, BCV, docs, and quickstart gates
6. If `SC-004`, recipient-confirmed proof completion, or remaining artifact / acceptance-criteria gaps stay open, execute the appended follow-up phases in ledger order (currently Phases 7–15) before declaring release readiness.

### Parallel Team Strategy

With multiple developers:

1. One developer owns build/test infrastructure (Setup + Foundational)
2. After Foundational:
   - Developer A: direct trust + direct transport (US1)
   - Developer B: routing + transfer (US2)
   - Developer C: power + parity (US3)
3. Rejoin for Phase 6 gates, benchmarks, and documentation parity

---

## Notes

- [P] tasks = different files, no dependencies
- [US1]/[US2]/[US3] labels map each task to a specific user story
- Every user story includes automated validation plus a manual/quickstart check
- The task list assumes a KMP-first implementation with no extra third-party runtime dependencies beyond `kotlinx-coroutines-core`
- Completion reports MUST include verification evidence and a `Skills Used` summary
- Stop at each story checkpoint and validate independently before moving on
- `[ ]` entries are remaining obligations; `[X]` entries are accepted historical
  ledger markers and MUST NOT be silently reopened by reruns
- Phase purposes, dependency notes, and parallel examples are planning aids for
  reviewers and implementers; they are not themselves normative product
  requirements
- Repeated `/plan`, `/tasks`, and `/implement` reruns must preserve the current
  canonical `plan.md` + `tasks.md` state, including completed markers,
  follow-up phases, and release-risk framing, while allowing new work to be
  appended explicitly
