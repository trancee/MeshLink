---
description: "Task list for MeshLink Offline BLE Mesh SDK"
---

# Tasks: MeshLink Offline BLE Mesh SDK

**Input**: Design documents from `/specs/001-ble-mesh-sdk/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/, quickstart.md

**Validation**: Every user story MUST include validation tasks. When work changes code, contracts, or data models, include automated tests or contract/integration checks. For documentation-only work, include manual verification or reader-test tasks. When applicable, add explicit work for the root `constitution.md` obligations: formatting, static analysis, coverage, API compatibility, required Android/iOS platform tests, cross-platform parity, Wycheproof or harness coverage, benchmarks, public-API KDoc, documentation parity, and compatibility validation. When YAML or workflow files change, add `yamllint` validation as well.

**Skill Use**: Before starting implementation or best-practice-heavy work, read the relevant skills listed in `specs/001-ble-mesh-sdk/plan.md` or otherwise applicable to the task. Completion summaries MUST include a `Skills Used` section or explicitly state `None`.

**Organization**: Tasks are grouped by user story to enable independent implementation and validation of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can usually run in parallel when the primary implementation or evidence work is independent; any eventual shared-doc sync still needs serialized edits or a later sync task
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Kotlin Multiplatform library**: `meshlink/src/commonMain/`, `meshlink/src/commonTest/`, `meshlink/src/androidMain/`, `meshlink/src/iosMain/`, `meshlink/build.gradle.kts`, `benchmarks/`
- **Docs & contracts**: `specs/001-ble-mesh-sdk/`, `docs/explanation/`, `meshlink-proof/android/`, `meshlink-proof/ios/`

## Artifact Roles & Ledger Semantics

- `spec.md` is normative for product requirements, assumptions, success
  criteria, release blocking conditions, and accepted exception wording.
- `plan.md` is normative for implementation constraints, architecture,
  artifact-governance rules, and review surfaces that interpret but do not
  weaken `spec.md`.
- `tasks.md` is the canonical execution plan plus append-only historical
  ledger. It defines work sequencing, traceability, and completion state, but
  task text does not override `spec.md` or `plan.md`.
- Supporting artifacts such as `research.md`, `quickstart.md`,
  `release-decision.md`, `contracts/`, `benchmarks/README.md`, and generated
  checklists provide evidence or review support and must align with the
  canonical trio above.
- If new remediation work is discovered after a task is complete, append a new
  task ID or follow-up phase rather than reopening or deleting the completed
  line, except to fix obvious clerical mistakes.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the KMP project skeleton, module layout, and baseline build files.

- [X] T001 Create the multi-module Gradle skeleton in `settings.gradle.kts`, `gradle/libs.versions.toml`, and `gradle.properties`
- [X] T002 Create the KMP runtime and benchmark module build files in `meshlink/build.gradle.kts` and `benchmarks/build.gradle.kts`
- [X] T003 [P] Scaffold source-set directories under `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/`, `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/`, `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/`, `meshlink/src/androidUnitTest/kotlin/ch/trancee/meshlink/platform/android/`, `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/`, and `meshlink/src/iosTest/kotlin/ch/trancee/meshlink/platform/ios/`
- [X] T004 [P] Scaffold runnable proof integration projects in `meshlink-proof/android/` and `meshlink-proof/ios/`, including README placeholders, minimal build wiring, and first-message quickstart entry points

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
- [X] T013 [US1] Validate the first-message flow, including default `deliveryRetryDeadline` guidance and proof-integration behavior, against `specs/001-ble-mesh-sdk/quickstart.md`, `meshlink-proof/android/`, and `meshlink-proof/ios/`, then record any corrections directly in the affected docs

### Implementation for User Story 1

- [X] T014 [P] [US1] Implement `LocalIdentity`, `TrustRecord`, advertisement key-hash derivation support, and the TOFU trust store in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/identity/` and `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/trust/`
- [X] T015 [P] [US1] Implement the Noise XX hop-handshake manager, Noise K end-to-end payload sealing/opening, and trust-failure diagnostic emission in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/crypto/` and `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/diagnostics/`
- [X] T016 [P] [US1] Implement Android direct BLE transport, secure storage, and the Android factory in `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/AndroidBleTransport.kt`, `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/AndroidSecureStorage.kt`, and `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/AndroidMeshLinkFactory.kt`
- [X] T017 [P] [US1] Implement iOS direct BLE transport, secure storage, and the iOS factory in `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt`, `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosSecureStorage.kt`, and `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosMeshLinkFactory.kt`
- [X] T018 [US1] Integrate direct send/receive lifecycle flows with hop-to-hop and end-to-end security layering in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngine.kt`, `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/api/MeshLink.kt`, and `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transport/`, then implement and document the proof flow in `meshlink-proof/android/` and `meshlink-proof/ios/`

**Checkpoint**: User Story 1 should deliver first-contact trust, direct offline messaging, restart recovery, trust-failure visibility, two-layer security, and direct-message parity evidence without servers or accounts.

---

## Phase 4: User Story 2 - Relay large messages across multiple hops (Priority: P2)

**Goal**: Add proactive routing, configurable no-route retry deadlines, and bounded large-transfer delivery so messages can cross multi-hop topologies beyond direct BLE range.

**Independent Validation**: Run a three-node harness that proves route
propagation, reconvergence, configurable no-route retry deadline behavior,
bounded, jittered exponential backoff while no route exists, immediate retry on
route availability, explicit loss of pending retry state across app or SDK
restart, and successful 64 KiB transfer with selective retransmission
semantics.

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
- [X] T027 [US2] Implement no-route retry scheduling with bounded, jittered exponential backoff and immediate retry on route availability in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/DeliveryRetryScheduler.kt`, integrate it through `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngine.kt`, `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transport/`, and `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/diagnostics/`, then update `meshlink-proof/android/README.md` and `meshlink-proof/ios/README.md` for multi-hop evidence

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
- [X] T034 [US3] Normalize diagnostics, the 26-code diagnostic catalog, `MeshLinkException` KDoc, `specs/001-ble-mesh-sdk/contracts/meshlink-api.md`, and parity docs in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/api/`, `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/diagnostics/`, `meshlink-proof/android/README.md`, `meshlink-proof/ios/README.md`, and `specs/001-ble-mesh-sdk/quickstart.md`

**Checkpoint**: User Story 3 should deliver shared power behavior and the same developer-visible lifecycle and diagnostic semantics on Android and iOS.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Close the constitutions’ quality gates, benchmark obligations, and documentation sync work.

- [X] T035 [P] Sync `specs/001-ble-mesh-sdk/spec.md`, `specs/001-ble-mesh-sdk/plan.md`, `specs/001-ble-mesh-sdk/tasks.md`, and `specs/001-ble-mesh-sdk/quickstart.md` with the implemented behavior
- [X] T036 Run format, static analysis, coverage, and API compatibility gates against `settings.gradle.kts`, `meshlink/build.gradle.kts`, and `benchmarks/build.gradle.kts`, and update the BCV API dump files under `meshlink/api/android/` and `meshlink/api/jvm/` as needed
- [X] T037 [P] Add remaining Wycheproof vectors and regression coverage in `meshlink/src/commonTest/resources/wycheproof/` and `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/crypto/WycheproofRegressionTest.kt`
- [X] T038 [P] Implement JVM benchmark suites in `benchmarks/src/jvmMain/kotlin/ch/trancee/meshlink/benchmarks/CryptoBenchmark.kt`, `benchmarks/src/jvmMain/kotlin/ch/trancee/meshlink/benchmarks/RoutingBenchmark.kt`, `benchmarks/src/jvmMain/kotlin/ch/trancee/meshlink/benchmarks/WireCodecBenchmark.kt`, and `benchmarks/src/jvmMain/kotlin/ch/trancee/meshlink/benchmarks/ConvergenceBenchmark.kt`
- [X] T039 [P] Add Android automated proof-app benchmarks for throughput, latency, LOW-power scan duty, and cold start in `meshlink-proof/android/app/src/androidTest/kotlin/ch/trancee/meshlink/proof/android/TransportPerformanceBenchmark.kt`, `meshlink-proof/android/app/src/androidTest/kotlin/ch/trancee/meshlink/proof/android/PowerProfileBenchmark.kt`, and `meshlink-proof/android/app/src/androidTest/kotlin/ch/trancee/meshlink/proof/android/ColdStartBenchmark.kt`
- [X] T040 [P] Add iOS automated proof-app benchmarks for throughput, latency, LOW-power scan duty, and cold start in `meshlink-proof/ios/ProofBenchmarks/TransportPerformanceBenchmark.swift`, `meshlink-proof/ios/ProofBenchmarks/PowerProfileBenchmark.swift`, and `meshlink-proof/ios/ProofBenchmarks/ColdStartBenchmark.swift`
- [X] T041 [P] Add 8-peer steady-state memory-budget validation and allocation measurement in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/MemoryBudgetIntegrationTest.kt` and `benchmarks/src/jvmMain/kotlin/ch/trancee/meshlink/benchmarks/MemoryBudgetBenchmark.kt`
- [X] T042 [P] Record JVM, Android, iOS, convergence, cold-start, power, and memory baselines in `benchmarks/README.md` and `specs/001-ble-mesh-sdk/research.md`
- [X] T043 [P] Update Android/iOS public API and workflow docs parity in `meshlink-proof/android/README.md`, `meshlink-proof/ios/README.md`, and `docs/explanation/trust-model.md`
- [X] T044 Run the full two-device quickstart validation using `specs/001-ble-mesh-sdk/quickstart.md`, `meshlink-proof/android/`, and `meshlink-proof/ios/`

---

## Phase 7: Follow-up - iOS Throughput Blocker Remediation

**Purpose**: Resolve the remaining iPhone 15 64 KiB single-hop throughput shortfall with instrumented experiments and fresh physical evidence.

- [X] T045 [P] Add iOS large-transfer telemetry for chunk pacing, stream readiness, ACK cadence, and backpressure in `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt` and `meshlink-proof/ios/ProofBenchmarks/BenchmarkTestSupport.swift`
- [X] T046 [P] Add regression coverage for transfer pacing and settlement heuristics in `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/LargeTransferIntegrationTest.kt` and `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/transfer/TransferSessionTest.kt`
- [X] T047 Implement one bounded iOS throughput remediation path for L2CAP stream draining and write batching in `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt` and `meshlink-proof/ios/ProofBenchmarks/TransportPerformanceBenchmark.swift`
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
- [X] T056 Validate the iOS CoC remediation with fresh Kotlin/Xcode verification output using `./gradlew :meshlink:jvmTest --tests 'ch.trancee.meshlink.transport.PendingFrameWindowTest' :meshlink:compileKotlinIosSimulatorArm64 :meshlink:compileKotlinIosArm64 :meshlink:ktfmtCheck --console=plain` and `xcodebuild -project meshlink-proof/ios/ProofApp.xcodeproj -scheme ProofApp -destination 'id=6C7DD73A-EC9C-46F9-B0B9-DD136F748621' test`

---

## Phase 10: Follow-up - Queued-Writer Physical Evidence Refresh

**Purpose**: Retain fresh post-queued-writer iPhone 15 physical evidence and telemetry findings without reopening the earlier throughput-remediation history.

- [X] T057 [P] Run fresh isolated iPhone 15 64 KiB proof reruns against the Samsung and OPPO reference peers and capture the resulting success/failure evidence
- [X] T058 [P] Run a telemetry-enabled iPhone 15 -> Samsung diagnostic rerun and retain queued-writer/backpressure observations from the proof log
- [X] T059 Update `benchmarks/README.md`, `specs/001-ble-mesh-sdk/research.md`, `specs/001-ble-mesh-sdk/quickstart.md`, and `meshlink-proof/ios/README.md` with the fresh queued-writer physical evidence and current blocker framing

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

- [X] T065 Add token-correlated proof-receipt diagnostics in `meshlink-proof/android/app/src/main/kotlin/ch/trancee/meshlink/proof/android/MainActivity.kt` and `meshlink-proof/ios/ProofApp/ProofViewModel.swift` so sender/passive logs capture token, peer, current state, known peers, and recent peer/diagnostic context at receipt send / receipt timeout boundaries
- [X] T066 Re-run one minimal recipient-confirmed physical repro (iPhone 15 -> Samsung, 256-byte MeshLink path) with the new token-correlated diagnostics and retain the resulting sender/passive evidence in `benchmarks/README.md` and `specs/001-ble-mesh-sdk/research.md`
- [X] T067 Investigate and implement one bounded return-path stability remediation for passive-peer proof receipts on the MeshLink path
- [X] T068 Re-run the recipient-confirmed Samsung/OPPO matrix after the bounded remediation and retain the resulting evidence in the durable docs

---

## Phase 14: Follow-up - Reverse-Path Peer Reappearance Investigation

**Purpose**: Determine whether the remaining recipient-confirmed failures are primarily caused by missing passive-peer rediscovery, route non-reappearance, or reverse-direction handshake collapse after the forward payload leg completes.

- [X] T069 Add reverse-path peer reappearance / route-availability timeline diagnostics in the shared runtime, relevant Android/iOS transport files, and both proof apps so receipt-window logs show when the passive peer rediscovers the sender, when a usable route for that peer reappears or expires, and which state transition immediately precedes `ReceiptTimeout` / `UNREACHABLE`
- [X] T070 [P] Re-run a minimal iPhone 15 -> Samsung 256-byte recipient-confirmed repro with the new reverse-path diagnostics and retain the paired sender/passive evidence in `benchmarks/README.md` and `specs/001-ble-mesh-sdk/research.md`
- [X] T071 [P] Re-run a minimal iPhone 15 -> OPPO 256-byte recipient-confirmed repro with the same diagnostics and retain the paired sender/passive evidence in `benchmarks/README.md` and `specs/001-ble-mesh-sdk/research.md`
- [X] T072 Compare the Samsung and OPPO reverse-path timelines, document the narrowed blocker hypothesis in `specs/001-ble-mesh-sdk/research.md`, and identify the next bounded remediation target if the evidence isolates a concrete peer-reappearance or route-availability failure mode

---

## Phase 15: Follow-up - Compatibility and Acceptance-Criteria Clarification

**Purpose**: Close the remaining normative implementation and evidence work from
the analysis follow-up: deployed-wire compatibility validation, trust/redaction
automated coverage, LOW-power proof-benchmark coverage, and retained quickstart
timing evidence, without reopening completed delivery history.

**Release-readiness note**: This phase is blocking for any full-conformance or
release-readiness claim.

- [X] T073 [P] Clarify the normative transport scope in `specs/001-ble-mesh-sdk/spec.md` and `specs/001-ble-mesh-sdk/plan.md` so the current release path remains MeshLink L2CAP-first and the retained GATT prototype is explicitly proof-only / non-conformance evidence unless a later spec amendment promotes it.
- [X] T074 Add explicit deployed-wire backward-compatibility validation for
  `FR-016` by checking prior-version FlatBuffers-compatible fixtures into
  `meshlink/src/commonTest/resources/wire-compat/`, running them from
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/wire/WireEnvelopeContractTest.kt`,
  updating the supported compatibility expectations in
  `specs/001-ble-mesh-sdk/contracts/wire-envelope.md`, and retaining pass/fail
  evidence in `specs/001-ble-mesh-sdk/research.md`.
- [X] T075 [P] Add automated coverage for `FR-015a` by asserting that the SDK
  persists only the pinned TOFU identity material plus
  `firstSeenAtEpochMillis` / `lastVerifiedAtEpochMillis`, and does not persist
  developer-visible diagnostics, plaintext payloads, decrypted message content,
  or other disallowed runtime data, using
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/api/MeshLinkApiContractTest.kt`,
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/DirectMessagingIntegrationTest.kt`,
  and the affected storage / trust code paths.
- [X] T076 [P] Add Android/iOS proof-benchmark coverage for `SC-006` by
  measuring the 1-hop, 256-byte LOW-power delivery target after peer discovery
  and connection establishment, retaining the scored runs from
  `meshlink-proof/android/app/src/androidTest/kotlin/ch/trancee/meshlink/proof/android/PowerProfileBenchmark.kt`
  and `meshlink-proof/ios/ProofBenchmarks/PowerProfileBenchmark.swift`, and
  recording the resulting evidence in `specs/001-ble-mesh-sdk/research.md`.
- [X] T077 Mirror the `SC-001` timed measurement method in
  `specs/001-ble-mesh-sdk/quickstart.md`, then run and retain a timed
  quickstart reader-test in `specs/001-ble-mesh-sdk/research.md` with start
  timestamp, end timestamp, elapsed duration, and observer note.
- [X] T078 Refresh `specs/001-ble-mesh-sdk/tasks.md` dependency and incremental-delivery guidance so it reflects appended follow-up phases through Phase 15 while preserving the append-only ledger rules.

---

## Phase 16: Follow-up - Explicit Edge-Case and Trust-Reset Coverage Closure

**Purpose**: Close the remaining requirement and edge-case coverage gaps that
are currently only implicit in broader tasks, without rewriting completed
delivery history.

- [X] T079 [P] Add explicit `FR-003a` reset/forget/revoke coverage by
  exercising `forgetPeer` / trust-reset flows, then proving the same peer is
  treated as a fresh TOFU candidate on recontact in
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/api/MeshLinkApiContractTest.kt`,
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/DirectMessagingIntegrationTest.kt`,
  and the affected trust-store code paths.
- [X] T080 [P] Add explicit missing/revoked BLE permission coverage for the
  blocked-validation edge case by asserting
  `MeshLinkException.PermissionDenied` behavior in
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/api/MeshLinkApiContractTest.kt`,
  adding Android/iOS platform-specific permission-denial tests in
  `meshlink/src/androidUnitTest/kotlin/ch/trancee/meshlink/platform/android/AndroidPermissionContractTest.kt`
  and
  `meshlink/src/iosTest/kotlin/ch/trancee/meshlink/platform/ios/IosPermissionContractTest.kt`,
  and documenting blocked-run evidence expectations in
  `specs/001-ble-mesh-sdk/quickstart.md` and
  `specs/001-ble-mesh-sdk/research.md`.
- [X] T081 [P] Add explicit incompatible transport / protocol-version rejection
  coverage for the compatibility edge case in
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/wire/WireEnvelopeContractTest.kt`,
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/MeshRoutingIntegrationTest.kt`,
  and `specs/001-ble-mesh-sdk/contracts/wire-envelope.md`, retaining one
  documented unsupported-version failure example in
  `specs/001-ble-mesh-sdk/research.md`.

---

## Phase 17: Follow-up - Recipient-Confirmed 64 KiB Large-Transfer Stabilization

**Purpose**: Restore clean recipient-confirmed 64 KiB physical proof completion
on the iPhone 15 MeshLink path, then retain the resulting Samsung / OPPO
stability evidence without weakening the separate iOS throughput blocker.

- [X] T082 [P] Add bounded large-transfer stabilization diagnostics and
  receiver-side ACK/progress evidence for the failing iPhone 15 64 KiB proof
  path in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngine.kt`,
  `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transfer/TransferSession.kt`,
  `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transport/BleTransport.kt`,
  `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/AndroidBleTransport.kt`,
  and `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt`.
- [X] T083 Implement one bounded remediation path for recipient-confirmed 64 KiB
  proof completion on the MeshLink path in
  `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngine.kt`,
  `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transfer/TransferSession.kt`,
  `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transport/BleTransport.kt`,
  and `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt`,
  including full-chunk completion delivery and queued-frame flush handling on
  the iOS sender path.
- [X] T084 [P] Re-run a 5-run iPhone 15 -> OPPO 64 KiB recipient-confirmed
  MeshLink series on the stabilized path and retain the resulting evidence in
  `benchmarks/README.md` and `specs/001-ble-mesh-sdk/research.md`.
- [X] T085 [P] Re-run a 5-run iPhone 15 -> Samsung 64 KiB recipient-confirmed
  MeshLink series on the same stabilized path and retain the resulting evidence
  in `benchmarks/README.md` and `specs/001-ble-mesh-sdk/research.md`.
- [X] T086 Sync `benchmarks/README.md`, `specs/001-ble-mesh-sdk/research.md`,
  `specs/001-ble-mesh-sdk/plan.md`, `specs/001-ble-mesh-sdk/spec.md`, and
  `specs/001-ble-mesh-sdk/tasks.md` so they reflect that recipient-confirmed
  64 KiB proof completion is restored on Samsung/OPPO while iOS `SC-004`
  throughput remains the active blocker.

---

## Phase 18: Follow-up - Configuration, Discovery, and LOW-Power Contract Closure

**Purpose**: Close the remaining constitution-traceability gaps for the shared
configuration builder, discovery advertisement contract, and LOW-tier
connection-interval floor without rewriting completed delivery history.

- [X] T087 [P] Add API contract coverage for the shared `meshLinkConfig`
  builder, platform-factory injection boundaries, and Android/iOS
  configuration parity in
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/api/MeshLinkApiContractTest.kt`
  and `specs/001-ble-mesh-sdk/contracts/meshlink-api.md`.
- [X] T088 [P] Add discovery-advertisement contract coverage for the fixed
  `4d455348` discovery UUID, 16-byte payload UUID, single-advertisement /
  no-scan-response rule, and reserved proof-only UUID block in
  `specs/001-ble-mesh-sdk/contracts/discovery-advertisement.md`,
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/transport/BleDiscoveryContractTest.kt`,
  `meshlink-proof/android/README.md`, and `meshlink-proof/ios/README.md`.
- [X] T089 [P] Extend shared power-policy tests and Android/iOS proof
  benchmarks to assert LOW-tier maintained connection intervals of `>= 500 ms`,
  alongside the existing scan-duty and 256-byte latency checks, in
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/power/PowerPolicyTest.kt`,
  `meshlink-proof/android/app/src/androidTest/kotlin/ch/trancee/meshlink/proof/android/PowerProfileBenchmark.kt`,
  `meshlink-proof/ios/ProofBenchmarks/PowerProfileBenchmark.swift`, and
  `specs/001-ble-mesh-sdk/research.md`.
- [X] T090 Sync `specs/001-ble-mesh-sdk/spec.md`,
  `specs/001-ble-mesh-sdk/plan.md`,
  `specs/001-ble-mesh-sdk/tasks.md`,
  `specs/001-ble-mesh-sdk/contracts/meshlink-api.md`,
  `specs/001-ble-mesh-sdk/contracts/discovery-advertisement.md`,
  `specs/001-ble-mesh-sdk/quickstart.md`,
  `meshlink-proof/android/README.md`, and `meshlink-proof/ios/README.md`
  with the shared builder, discovery-advertisement, and LOW-tier
  connection-interval contract.

---

## Phase 19: Follow-up - SC-004 Release Closure

**Purpose**: Close the remaining iOS `SC-004` release blocker by either meeting
the normative throughput target or recording the explicit waiver /
known-limitation path already allowed by `spec.md`.

**Release-readiness note**: This phase was blocking until either the
conformance path or the waiver path was explicitly completed. It is now closed
via the explicit waiver / known-limitation path recorded in the canonical docs.

- [X] T091 Record the then-current `SC-004` closure framing in
  `specs/001-ble-mesh-sdk/spec.md`,
  `specs/001-ble-mesh-sdk/plan.md`, and
  `specs/001-ble-mesh-sdk/release-decision.md`, preserving the active
  pre-waiver conformance-track interpretation while keeping the waiver /
  known-limitation alternative available for final release-path selection.
  Later superseded for the released baseline by `T093`; future-branch
  non-waived conformance evidence was retained later by `T108`.
- [X] T092 If the release remains blocked on conformance, execute one bounded
  iOS MeshLink-path throughput remediation experiment and rerun the
  recipient-confirmed reference-hardware benchmark, retaining raw
  sender/recipient evidence in `benchmarks/README.md` and
  `specs/001-ble-mesh-sdk/research.md`.
- [X] T093 If the waiver path is chosen, record the accepted iOS
  large-transfer limitation, stakeholder approval, and public-claim guardrails
  in `specs/001-ble-mesh-sdk/spec.md`,
  `specs/001-ble-mesh-sdk/plan.md`,
  `specs/001-ble-mesh-sdk/quickstart.md`,
  `meshlink-proof/ios/README.md`, and
  `specs/001-ble-mesh-sdk/release-decision.md`.
- [X] T094 Retain the additional rejected iOS Samsung follow-up matrix
  (run-loop scheduling, shorter sender ACK settlement, wider transport
  coalescing, re-enabled 64 KiB inline path, and inline path + 16 KiB inner
  batch) in `benchmarks/README.md`,
  `specs/001-ble-mesh-sdk/research.md`, and
  `specs/001-ble-mesh-sdk/release-decision.md`, recording that none exceeded
  the current `33.56 KB/s` Samsung best case or closed `SC-004`.
- [X] T095 Implement the more invasive iOS-only inbound-L2CAP connection-
  latency design change, verify it on fresh Samsung reference-peer reruns, and
  retain both the improved best-case evidence (`38.28 KB/s`, refreshed at
  `34.33 KB/s`) and the rejected low-latency + inline combination in
  `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt`,
  `benchmarks/README.md`,
  `specs/001-ble-mesh-sdk/research.md`,
  `specs/001-ble-mesh-sdk/release-decision.md`,
  `specs/001-ble-mesh-sdk/spec.md`, and
  `specs/001-ble-mesh-sdk/plan.md`.
- [X] T096 Implement the deeper cross-platform mixed Android/iOS initiator-
  policy redesign by encoding platform-family hints in the shared discovery
  payload, make Android the deterministic L2CAP initiator for mixed-platform
  peers while keeping same-platform / legacy peers on key-hash ordering,
  verify the final clean-build recipient-confirmed Samsung and OPPO reruns, and
  retain the resulting `39.48 KB/s` Samsung and `52.03 KB/s` OPPO evidence in
  `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transport/BleDiscoveryContract.kt`,
  `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/AndroidBleTransport.kt`,
  `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt`,
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/transport/BleDiscoveryContractTest.kt`,
  `specs/001-ble-mesh-sdk/contracts/discovery-advertisement.md`,
  `benchmarks/README.md`,
  `specs/001-ble-mesh-sdk/research.md`,
  `specs/001-ble-mesh-sdk/release-decision.md`,
  `specs/001-ble-mesh-sdk/spec.md`,
  `specs/001-ble-mesh-sdk/plan.md`,
  `specs/001-ble-mesh-sdk/quickstart.md`, and
  `meshlink-proof/ios/README.md`.
- [X] T097 Retain the rejected post-role-policy follow-up matrix (8-frame iOS
  coalescing, deterministic-path inline rerun, and ACK-batch=`32` rerun) in
  `benchmarks/README.md`,
  `specs/001-ble-mesh-sdk/research.md`,
  `specs/001-ble-mesh-sdk/release-decision.md`,
  `specs/001-ble-mesh-sdk/spec.md`, and
  `specs/001-ble-mesh-sdk/plan.md`, recording that the remaining blocker now
  looks platform-limited inside the current public MeshLink L2CAP product path.

---

## Phase 20: Follow-up - Post-waiver future SC-004 redesign

**Purpose**: Explore a materially different transport / platform branch for a
future non-waived release while preserving the current waived release framing.
The current release guardrails stay unchanged until fresh retained evidence
shows that a new branch can plausibly close iOS `SC-004`.

- [X] T098 Compare at least three materially different future iOS `SC-004`
  closure designs in `specs/001-ble-mesh-sdk/research.md` and
  `specs/001-ble-mesh-sdk/release-decision.md`, then record the chosen next
  proof branch in this task ledger without changing the current waived release
  posture.
- [X] T099 Implement the selected proof-only reverse-direction redesign in the
  runnable proof integrations so iPhone can benchmark a materially different
  platform API path while still retaining recipient-confirmed semantics. The
  first chosen branch is an iOS peripheral GATT notification host plus Android
  central GATT benchmark client in `meshlink-proof/ios/ProofApp` and
  `meshlink-proof/android/app/src/main/kotlin/ch/trancee/meshlink/proof/android`.
- [X] T100 Run fresh physical Samsung and OPPO reference-peer reruns for that
  new proof branch, retain raw sender / recipient evidence in
  `benchmarks/README.md`,
  `specs/001-ble-mesh-sdk/research.md`, and
  `specs/001-ble-mesh-sdk/release-decision.md`, and explicitly decide whether
  the branch is promising enough to justify product-path integration and a
  future spec amendment.

---

## Phase 21: Follow-up - Reverse GATT-notify product-path integration

**Purpose**: Promote the promising proof-only reverse GATT-notify branch into a
real MeshLink transport candidate for a future non-waived release.

- [X] T101 Amend the canonical product-path framing in
  `specs/001-ble-mesh-sdk/spec.md`,
  `specs/001-ble-mesh-sdk/plan.md`,
  `specs/001-ble-mesh-sdk/contracts/meshlink-api.md`, and
  `docs/explanation/why-l2cap-first.md` so the future conformance branch may
  evaluate an iOS-hosted GATT-notify bearer for large transfers without
  weakening the current waived release guardrails.
- [X] T102 Implement the product-path GATT-notify bearer candidate in
  `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt`,
  `meshlink/src/androidMain/kotlin/ch/trancee/meshlink/platform/android/AndroidBleTransport.kt`,
  `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngine.kt`,
  and the relevant transport tests so mixed Android/iOS peers can negotiate and
  use the new bearer while preserving the existing L2CAP path.
- [X] T103 Run fresh product-path physical Samsung and OPPO reruns, retain the
  evidence in `benchmarks/README.md`,
  `specs/001-ble-mesh-sdk/research.md`, and
  `specs/001-ble-mesh-sdk/release-decision.md`, and decide whether the new
  bearer actually closes iOS `SC-004` without the release waiver.
- [X] T104 Resolve the iOS product-path `CBPeripheralManager.updateValue`
  bridging blocker uncovered during the first shared-Kotlin integration attempt:
  the proof-only Swift host works, but the shared Kotlin/Native transport path
  currently lacks a verified `ByteArray -> NSData` bridge for GATT-notify frame
  sends. Record the chosen fix (supported Kotlin/Native bridge or minimal
  Swift/ObjC helper reachable from KMP) before reopening `T102`/`T103`
  physical reruns.
- [X] T105 Diagnose and resolve the next product-path blocker revealed by the
  reopened physical reruns: the optional mixed-platform GATT-notify side bearer
  now delivers real MeshLink frames on Android, but the direct route still
  expires before the 64 KiB transfer completes because reverse transfer /
  receipt traffic remains tied to the L2CAP path. Decide whether to move the
  reverse control plane onto GATT as well, keep L2CAP alive explicitly, or
  redesign route-presence semantics for the mixed bearer.
- [X] T106 Stabilize recipient-confirmed mixed-bearer proof closure after the
  first T105 remediations: retained Samsung evidence now reaches product-path
  64 KiB forward completion, but the passive Android proof receipt still
  degrades to `NotSent(reason=UNREACHABLE)`, while a fresh OPPO rerun on the
  same branch still times out before sender-side transfer completion. Capture
  whether the remaining problem is (a) stale duplicate post-completion traffic,
  (b) Android->iOS GATT-write reuse for direct receipts, or (c) OPPO-specific
  sender/queue stability before reopening any release-readiness claim.

---

## Phase 22: Follow-up - Product-path SC-004 Closure

**Purpose**: Close the future-branch iOS `SC-004` gap on the mixed-bearer
product path and retain canonical evidence once the remaining Samsung variance
and headless proof false negatives are resolved.

- [X] T107 Implement the final product-path throughput remediations in
  `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/engine/MeshEngine.kt`,
  `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosGattNotifyLink.kt`,
  `meshlink/src/iosMain/kotlin/ch/trancee/meshlink/platform/ios/IosBleTransport.kt`,
  `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/api/IosBleTransportBridge.kt`,
  `meshlink-proof/ios/ProofApp/MeshLinkTransportBridge.swift`, and the
  relevant test files so large inline mixed-bearer sends suspend discovery,
  the iOS GATT-notify path minimizes bridge overhead, and the headless proof
  app reschedules benchmark auto-send when a direct route recovers after a
  transient peer-loss event.
- [X] T108 Re-run fresh headless Samsung and OPPO current-HEAD product-path
  benchmarks, retain the resulting evidence in `benchmarks/README.md`,
  `specs/001-ble-mesh-sdk/research.md`, `specs/001-ble-mesh-sdk/plan.md`,
  `specs/001-ble-mesh-sdk/spec.md`, `specs/001-ble-mesh-sdk/release-decision.md`,
  and `meshlink-proof/ios/README.md`, and record whether the future branch now
  closes `SC-004` on the reference matrix without relying on the historical
  release waiver.

---

## Phase 23: Follow-up - FR-008 Restart-Loss Validation Closure

**Purpose**: Close the remaining explicit `FR-008` coverage gap by proving that
pending no-route retries are in-memory only and are lost across app or SDK
restart.

- [X] T109 [P] Add explicit `FR-008` restart-loss integration coverage by
  creating a pending no-route delivery, restarting the runtime in the canonical
  harness, and asserting that retry/session state is gone until the host
  resubmits the message in
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/MeshRoutingIntegrationTest.kt`,
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/LargeTransferIntegrationTest.kt`,
  and any affected harness helpers.

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
- **Follow-up Explicit Edge-Case and Trust-Reset Coverage Closure (Phase 16)**: Runs after Phase 15 and historically blocked full-conformance / release-readiness claims until completed `T074`–`T081` closed those gaps.
- **Follow-up Recipient-Confirmed 64 KiB Large-Transfer Stabilization (Phase 17)**: Runs after Phase 16 to restore recipient-confirmed large-transfer stability and feed the current throughput-only blocker framing once Samsung/OPPO proof completion is restored.
- **Follow-up Configuration, Discovery, and LOW-Power Contract Closure (Phase 18)**: Runs after Phase 17 to close remaining constitution-traceability gaps before any fresh release-readiness claim.
- **Follow-up SC-004 Release Closure (Phase 19)**: Runs after Phase 18 and was blocking until either the normative conformance path or the explicit waiver path was explicitly completed; it is now closed via the explicit waiver / known-limitation path.
- **Follow-up Post-waiver Future SC-004 Redesign (Phase 20)**: Runs after Phase 19 when a future non-waived release needs a materially different transport / platform branch instead of another small app-layer tuning pass.
- **Follow-up Reverse GATT-notify Product-path Integration (Phase 21)**: Runs after Phase 20 once the proof-only reverse GATT-notify branch is promising enough to justify product-path integration work.
- **Follow-up Product-path SC-004 Closure (Phase 22)**: Runs after Phase 21 once the mixed-bearer product path is stable enough for fresh headless closure evidence on the reference matrix.
- **Follow-up FR-008 Restart-Loss Validation Closure (Phase 23)**: Runs after
  Phase 22, or earlier if the runtime already behaves correctly and only
  test/evidence work remains.
- **Follow-up SC-005 Durable Memory Artifact Retention (Phase 24)**: Runs after
  Phase 23, or earlier if the implementation already enforces the cap and only
  retained-evidence work remains.

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
6. If `SC-004` or constitution-traceability gaps remain open, execute the
   appended follow-up phases in ledger order (currently Phases 7–24) before
   declaring release readiness.
7. Do not make a release-readiness or full-conformance claim while Phase 19
   remains open; `SC-004` requires either retained passing evidence on
   reference hardware or the explicit waiver / known-limitation path recorded
   in the canonical docs.
8. After a waived release has been recorded, future non-waived closure work
   proceeds through Phases 20–22 rather than silently reopening the waived
   release framing.

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

- [P] tasks = primary implementation or evidence work is intended to proceed in
  parallel, even when a later serialized sync task is still required for shared
  docs or ledgers
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
- The Phase 19 `SC-004` closure path is now complete via the explicit waiver /
  known-limitation record in the canonical docs; historical blockers
  `T074`–`T081` are completed ledger entries and MUST NOT be described as open
  unless new follow-up work is appended.

---

## Phase 24: Follow-up - SC-005 Durable Memory Artifact Retention

**Purpose**: Close the remaining `SC-005` evidence-quality gap by retaining an
explicit measured 8-peer steady-state heap byte count in the canonical benchmark
artifacts instead of only a pass/fail budget statement.

- [X] T110 [P] Emit and retain a durable measured 8-peer steady-state heap byte
  count by updating
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/integration/MemoryBudgetIntegrationTest.kt`,
  rerunning the JVM memory-budget validation, and syncing the retained value into
  `benchmarks/README.md` and `specs/001-ble-mesh-sdk/alignment-audit.md`.
