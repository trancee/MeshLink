# Pre-release refactor roadmap for MeshLink

Status: Proposed

## Release posture

MeshLink has **not been released yet**.

This roadmap therefore optimizes for **a better first release**, not for preserving
pre-release compatibility.

That means the refactor stream may introduce **intentional breaking changes** when
those changes:

- deepen a shallow module
- reduce duplicated policy or orchestration
- improve locality for future fixes and tests
- remove transitional seams that would otherwise become permanent baggage
- make the unreleased SDK, reference app, or proof surfaces easier to maintain

Breaking changes are still a deliberate tool, not a free-for-all.
High-risk surfaces such as wire format, crypto posture, routing behavior, and
physical-proof workflows should change only in slices that name that risk
explicitly and run the fuller verification bundle.

## What this roadmap is for

Use this roadmap when you want to improve the repository before the first public
release by making the code:

- faster where the extra efficiency is real and measurable
- easier to understand through smaller, deeper modules
- easier for an AI agent to change safely in one focused round
- better commented at the seams where invariants matter
- better documented so contributor guidance stays in sync with the codebase

## Refactor rules for every slice

### One AI round equals one seam

Every slice should stay small enough to finish in one focused agent round:

- one primary seam
- usually two to eight edited files
- one verification bundle
- one Conventional Commit

If a slice grows beyond that, split it again.

### Comments explain invariants, not narration

Add comments only where they increase leverage for future readers:

- state-transition rules
- transport or platform workarounds
- wire or payload layout invariants
- retry or ordering constraints
- why a design exists

Avoid comments that merely restate the code.

### Docs move with the seam

If a slice changes contributor landmarks, architecture vocabulary, or module
ownership, update the matching docs in the same slice.

### Verification is mandatory

Every slice ends with fresh verification output generated after the last edit.
Do not mark a slice complete on code inspection alone.

## Verification bundles

| Surface touched | Default verification |
|---|---|
| `:meshlink-reference` shared UI, session logic, or shared state | `./scripts/run-reference-local-check.sh` |
| `:meshlink` shared runtime or common code | `./gradlew :meshlink:allTests :meshlink:detekt :meshlink:koverVerify` |
| Android-specific `:meshlink` transport or platform glue | `./gradlew :meshlink:testDebugUnitTest :meshlink:detekt :meshlink:koverVerify` |
| iOS-specific `:meshlink` transport or platform glue | `./gradlew :meshlink:iosSimulatorArm64Test :meshlink:detekt :meshlink:koverVerify` |
| Performance-sensitive `:meshlink` paths | the matching bundle above plus `./gradlew :benchmarks:jvmSmokeBenchmark` |
| Docs only | `./gradlew verifyDocs` |
| Android proof app host refactor | `./gradlew :meshlink-proof:android:meshlink-proof-android-app:assembleDebug` |
| iOS proof app host refactor | `xcodebuild -project meshlink-proof/ios/ProofApp.xcodeproj -scheme ProofApp -destination 'generic/platform=iOS Simulator' build` |

## Roadmap overview

### Phase 1 — Reference-app architecture deepening

- [x] **R01: Land the session transition seam** `risk:medium` `depends:[]` `breaking:allowed`
  > After this: supported, ended, solo, and lab session transitions run through one explicit transition module instead of being split across navigation and evidence code.
  - Scope: introduce the planned `SessionTransitionService` and the pure `chooseSessionSurfaceChoice(...)` decision table.
  - Primary modules: `SessionTransitionService`, `chooseSessionSurfaceChoice(...)`, `ReferenceSessionController`, technical-timeline transition callers.
  - Why this first: it removes duplicated transition logic and gives later reference-app slices a clearer seam.
  - Verify: `./scripts/run-reference-local-check.sh`

- [x] **R02: Finish turning the technical timeline into an evidence-state module** `risk:medium` `depends:[R01]` `breaking:allowed`
  > After this: the technical timeline owns evidence projection, export visibility, and retention visibility, not broad session-transition orchestration.
  - Scope: keep `TechnicalTimelineStore` focused on evidence state and move remaining orchestration behind the transition seam or narrower helpers.
  - Primary modules: `TechnicalTimelineStore`, export helpers, retention helpers, live-snapshot sync helpers.
  - Why this next: it improves locality for timeline bugs and makes the evidence surface easier to reason about.
  - Verify: `./scripts/run-reference-local-check.sh`

- [x] **R03: Unify live controller command execution** `risk:low` `depends:[]` `breaking:allowed`
  > After this: start, pause, resume, stop, send, and forget-peer follow one command-execution shape instead of repeating binding and result-handling patterns.
  - Scope: centralize live runtime command execution, projection, and error handling.
  - Primary modules: `LiveReferenceMeshLinkController`, `LiveReferenceMeshRuntime`, live session projector helpers.
  - Why this slice: it is small, low risk, and removes repetitive logic before larger refactors touch the same area.
  - Verify: `./scripts/run-reference-local-check.sh`

- [x] **R04: Simplify retained-history and export document flow** `risk:low` `depends:[R02]` `breaking:allowed`
  > After this: history and export persistence use a smaller, clearer document path with less redundant read-modify-write logic.
  - Scope: reduce document churn and duplicate serialization flow without changing export policy semantics.
  - Primary modules: `JsonSessionHistoryRepository`, `JsonSessionArtifactSerializer`, document-store abstractions.
  - Why this slice: it improves efficiency and makes later evidence-surface changes less error-prone.
  - Verify: `./scripts/run-reference-local-check.sh`

### Phase 2 — SDK core depth and efficiency

- [x] **R05: Document and simplify discovery and wire invariants** `risk:medium` `depends:[]` `breaking:allowed`
  > After this: discovery payload layout, UUID encoding, and wire-shape assumptions are understandable without reverse-engineering the implementation.
  - Scope: add KDoc and concise invariant comments; extract only the helpers that clearly reduce mental load.
  - Primary modules: BLE discovery contract, wire codecs, payload codecs, FlatBuffer table helpers.
  - Why this slice: it raises understanding quickly and reduces future refactor risk in high-signal code.
  - Verify: `./gradlew :meshlink:jvmTest :meshlink:detekt :meshlink:koverVerify`

- [x] **R06: Extract route advertisement planning from route selection** `risk:medium` `depends:[R05]` `breaking:allowed`
  > After this: route selection remains one concern and advertisement planning becomes another, making routing mutations easier to test and optimize.
  - Scope: separate route advertisement building, digest reuse, and fan-out planning from core route selection.
  - Primary modules: `RouteCoordinator` and its routing tests.
  - Why this slice: it improves locality in one of the most complex shared-runtime seams.
  - Verify: `./gradlew :meshlink:allTests :meshlink:detekt :meshlink:koverVerify`

- [x] **R07: Separate engine assembly from runtime policy modules** `risk:medium` `depends:[R05]` `breaking:allowed`
  > After this: assembly reads as composition, while retry, TTL, and runtime policy decisions live in smaller modules with clearer interfaces.
  - Scope: pull policy-shaped decisions out of assembly wiring without widening the public runtime interface.
  - Primary modules: `MeshEngineRuntime`, foundation assembly, session assembly, transfer assembly.
  - Why this slice: it deepens the coordinator pattern instead of letting assembly files become policy grab-bags.
  - Verify: `./gradlew :meshlink:allTests :meshlink:detekt :meshlink:koverVerify`

- [x] **R08: Concentrate session and transfer registry mutations** `risk:high` `depends:[R07]` `breaking:allowed`
  > After this: session and transfer lifecycle mutations live behind one tighter seam instead of being spread across many support modules.
  - Scope: reduce cross-file mutation sprawl for session ownership, transfer lifecycle, and registry bookkeeping.
  - Primary modules: session registry, session support, transfer support, inbound-transfer support, relay-transfer support.
  - Why this slice: it is a high-leverage structural cleanup, but it should happen only after the lower-risk assembly work settles.
  - Verify: `./gradlew :meshlink:allTests :meshlink:detekt :meshlink:koverVerify :benchmarks:jvmSmokeBenchmark`

### Phase 3 — Platform transport seam cleanup

- [x] **R09: Split Android transport discovery lifecycle from the adapter** `risk:high` `depends:[R05]` `breaking:allowed`
  > After this: the Android BLE transport adapter coordinates transport concerns instead of directly owning every advertise, scan, and discovery-resume detail.
  - Scope: isolate discovery and advertising lifecycle behavior behind a narrower seam.
  - Primary modules: Android BLE transport adapter, scan support, discovery helpers, advertising helpers.
  - Why this slice: it removes one of the largest transport-level shallow modules.
  - Verify: `./gradlew :meshlink:testDebugUnitTest :meshlink:detekt :meshlink:koverVerify`

- [x] **R10: Split Android link and peer-session ownership from the adapter** `risk:high` `depends:[R09]` `breaking:allowed`
  > After this: temporary-peer promotion, active-link ownership, and pending-connect bookkeeping stop living in the same broad adapter implementation.
  - Scope: isolate link ownership, peer rebinding, and pending-connection state.
  - Primary modules: Android BLE transport adapter, GATT side-link coordinator, peer registry, notify-session helpers.
  - Why this slice: it improves locality for the hardest Android transport bugs.
  - Verify: `./gradlew :meshlink:testDebugUnitTest :meshlink:detekt :meshlink:koverVerify`

- [ ] **R11: Split iOS transport pump and fallback policy seams** `risk:high` `depends:[R05]` `breaking:allowed`
  > After this: iOS packet-pump mechanics and mixed-platform bearer policy are independently understandable and testable.
  - Scope: separate write-pump mechanics, GATT-notify fallback policy, and discovery-driven connection rules.
  - Primary modules: iOS L2CAP write pump, iOS transport support helpers, iOS peer registry.
  - Why this slice: it reduces platform-specific hidden knowledge in the iOS transport path.
  - Verify: `./gradlew :meshlink:iosSimulatorArm64Test :meshlink:detekt :meshlink:koverVerify`

### Phase 4 — Proof harness maintainability

- [ ] **R12: Decompose the Android proof host and runtime** `risk:medium` `depends:[]` `breaking:allowed`
  > After this: the Android proof activity is a host surface, not a 1,000-line runtime, permission, benchmark, and logging bundle.
  - Scope: split launch parsing, runtime ownership, benchmark payload helpers, receipt handling, and UI host responsibilities.
  - Primary modules: Android proof activity and the proof runtime helpers it currently contains.
  - Why this slice: it is self-contained and yields immediate maintainability gains without touching the normative reference-app surface.
  - Verify: `./gradlew :meshlink-proof:android:meshlink-proof-android-app:assembleDebug`

- [ ] **R13: Decompose the iOS proof view model by mode** `risk:medium` `depends:[]` `breaking:allowed`
  > After this: MeshLink mode, GATT benchmark modes, transport-log capture, and benchmark receipt flow stop competing inside one large view model.
  - Scope: split the current proof view model by runtime mode and responsibility.
  - Primary modules: iOS proof view model and its benchmark-mode helpers.
  - Why this slice: it makes the proof harness easier to evolve before release and reduces platform-specific cognitive load.
  - Verify: `xcodebuild -project meshlink-proof/ios/ProofApp.xcodeproj -scheme ProofApp -destination 'generic/platform=iOS Simulator' build`

### Phase 5 — Documentation and contributor guidance sync

- [ ] **R14: Refresh architecture and contributor docs after the seam changes** `risk:low` `depends:[R02,R03,R04,R06,R07,R08,R09,R10,R11,R12,R13]` `breaking:allowed`
  > After this: contributors can still find the right module, seam, and verification bundle without carrying conversation-only context.
  - Scope: update architecture, repository-layout, contributor, reference-app, and proof-app guidance to match the landed seams.
  - Primary docs: repository architecture explanation, MeshEngine explanation, contributor reference, repository layout reference, reference-app overview, proof-app guides.
  - Why this final slice: docs should settle after the seam map stabilizes.
  - Verify: `./gradlew verifyDocs`

## Recommended execution order

If the goal is the best early leverage with controlled risk, use this order:

1. `R01`, `R03`, `R05`, `R12`, `R13`
2. `R02`, `R04`, `R06`, `R07`
3. `R08`, `R09`, `R11`
4. `R10`
5. `R14`

This order front-loads:

- low-risk maintainability wins
- high-value comments and invariant docs
- proof-harness cleanup that does not redefine the product-facing path
- deeper reference-app seams before the riskier transport work

## Definition of done for a slice

A slice is done only when all of the following are true:

- the target seam is smaller or deeper than before
- comments were added only where they explain real invariants or design intent
- any affected contributor or architecture docs were updated in the same slice
- the matching verification bundle passed after the last edit
- the slice landed in a Conventional Commit

## Out of scope unless a slice says otherwise

These changes should stay out of incidental refactor slices:

- casual wire-format churn
- silent crypto-posture changes
- benchmark-threshold changes without fresh evidence
- documentation drift that is deferred to a later cleanup
- broad renames with no depth or locality payoff

If one of those changes is necessary, make it explicit in the slice title,
verification plan, and commit message.
