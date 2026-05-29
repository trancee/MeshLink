# Contributor build, test, and verification reference

This page lists the contributor-facing tooling, commands, verification bundles,
and repository rules for MeshLink changes.

Use [How to contribute to MeshLink](../../CONTRIBUTING.md) for the guided flow.
Use this page when you need exact commands, matrices, or policy details.

## Quick lookup

| If your change affects... | Use... |
|---|---|
| module ownership or host location | [Repository layout reference](repository-layout.md) |
| docs only | `./gradlew verifyDocs` |
| shared-library logic | `./gradlew :meshlink:allTests :meshlink:detekt :meshlink:koverVerify` |
| Android-specific library glue | `./gradlew :meshlink:testDebugUnitTest :meshlink:detekt :meshlink:koverVerify` plus `:meshlink:allTests` when shared behavior or parity is affected |
| iOS-specific library glue | `./gradlew :meshlink:iosSimulatorArm64Test :meshlink:detekt :meshlink:koverVerify` plus `:meshlink:allTests` when shared behavior or parity is affected |
| reference-app shared UI, Android glue, or shared state | `./scripts/run-reference-local-check.sh` |
| Android proof-app host or benchmark-only harness logic | `./gradlew :meshlink-proof:android:meshlink-proof-android-app:assembleDebug` |
| iOS proof-app host or benchmark-only harness logic | `xcodebuild -project meshlink-proof/ios/ProofApp.xcodeproj -scheme ProofApp -destination 'generic/platform=iOS Simulator' build` |
| reference-app release artifact or framework export | `./gradlew :meshlink-reference:build` |
| build tooling or module shape | `./scripts/run-agp9-verification.sh` or `./gradlew checkAgp9Invariants` plus the relevant narrower module checks |
| public API | `./gradlew :meshlink:allTests :meshlink:detekt :meshlink:apiCheck :meshlink:koverVerify verifyDocs`, plus API dump and appendix refresh |
| performance-sensitive paths | the relevant checks above plus `./gradlew :benchmarks:jvmSmokeBenchmark`; use `:benchmarks:jvmBenchmark` when retained evidence is required |
| Android or iOS physical transport or proof behavior | the relevant checks above plus the matching proof-app or reference-app validation flow |

## Workstation requirements

| Need | Requirement | Notes |
|---|---|---|
| Gradle build and tests | JDK 17 | Required for the repository build. |
| Android compilation and unit tests | Android SDK for API 36 builds | Required for Android targets. |
| iOS compilation and tests | Xcode | Required for iOS build and test targets. |
| Documentation verification | Python 3 | Required by the documentation verification scripts. |
| YAML validation | `yamllint` | Required when staged YAML files are part of the change. |
| iOS project regeneration after project-spec changes | `xcodegen` | Needed only when the committed iOS project spec changes. |
| Final full-library validation on one workstation | macOS | Required for the Android + iOS contributor flow in one place. |

## Core contributor commands

### Setup and bootstrap

| Purpose | Command | Notes |
|---|---|---|
| Warm the checkout | `./gradlew help` | Confirms the wrapper works and downloads plugins and toolchains. |
| Install repository Git hooks | `./scripts/install-git-hooks.sh` | Sets `core.hooksPath` to `.githooks` and enables the repository hook suite. |
| Run the pre-push hook manually | `.githooks/pre-push <remote> <url>` | Feed it the standard Git pre-push ref lines on stdin when you want to simulate a push locally. |

### Build, test, and quality

| Purpose | Command | Notes |
|---|---|---|
| Build the library | `./gradlew :meshlink:build` | Standard first build for the library module. |
| Compile without the full build | `./gradlew :meshlink:assemble` | Useful during early edit loops. |
| Run the full library test bundle | `./gradlew :meshlink:allTests` | Aggregates tests across the library targets. |
| Run JVM tests | `./gradlew :meshlink:jvmTest` | Fast shared-library feedback loop. |
| Run Android unit tests | `./gradlew :meshlink:testDebugUnitTest` | Default Android-side unit test task. |
| Run iOS tests on Apple Silicon | `./gradlew :meshlink:iosSimulatorArm64Test` | Supported iOS simulator target for local verification. |
| Run static analysis | `./gradlew :meshlink:detekt` | Production code must remain Detekt-clean. |
| Verify coverage | `./gradlew :meshlink:koverVerify` | Coverage gate for the library module. |
| Run connected-device checks | `./gradlew :meshlink:connectedCheck` | Uses currently connected devices. |

### Reference app, docs, and API

| Purpose | Command | Notes |
|---|---|---|
| Run fast reference-app verification | `./scripts/run-reference-local-check.sh` | Runs `:meshlink-reference:localCheck` on the current AGP 9 toolchain. |
| Run the AGP 9 build-surface verification bundle | `./scripts/run-agp9-verification.sh` | Runs the invariant check plus the cross-module verification bundle for Gradle, AGP, Kotlin, or module-shape changes. |
| Build the reference app | `./gradlew :meshlink-reference:build` | Slower because it links release frameworks for all iOS targets. |
| Check API compatibility | `./gradlew :meshlink:apiCheck` | Verifies the tracked public API surface. |
| Refresh the checked-in API dump | `./gradlew :meshlink:apiDump` | Required for intentional public API changes. |
| Run docs verification | `./gradlew verifyDocs` | Checks the generated API appendix and markdown links. |
| Format Kotlin | `./gradlew :meshlink:ktfmtFormat` | Repository formatting is ktfmt-based. |
| Check AGP 9 build invariants | `./gradlew checkAgp9Invariants` | Confirms the post-migration module and plugin shape and preserved reference-app compatibility tasks. |

### Dokka and benchmarks

| Purpose | Command | Notes |
|---|---|---|
| Generate all useful Dokka HTML | `./gradlew dokkaGenerateAllHtml` | Generates contributor-useful Dokka output for `:meshlink` and `:meshlink-reference` without using deprecated per-project `dokkaHtml` tasks. |
| Generate Dokka HTML for the SDK | `./gradlew :meshlink:dokkaGenerateHtml` | Writes supplemental Kotlin API reference output to `meshlink/build/dokka/html/`. |
| Generate Dokka HTML for the shared reference app module | `./gradlew :meshlink-reference:dokkaGenerateHtml` | Writes contributor-facing shared-module output to `meshlink-reference/build/dokka/html/`. |
| Run JVM smoke benchmarks | `./gradlew :benchmarks:jvmSmokeBenchmark` | Fast development-time performance check. |
| Run retained JVM benchmarks | `./gradlew :benchmarks:jvmBenchmark` | Use when retained benchmark evidence is required. |

## Test surfaces

| Surface | Purpose | Notes |
|---|---|---|
| `commonTest` | Shared protocol, routing, crypto, and API behavior | First stop for `commonMain` changes. |
| `jvmTest` | Fast shared-library validation | Good for tight local loops. |
| `androidUnitTest` | Android-specific library behavior | Use before connected-device checks. |
| `iosTest` | iOS-specific library behavior | Run via the matching simulator target for the workstation. |
| `allTests` | Aggregated library tests | Use for cross-target, parity, and pre-review checks. |
| Virtual mesh integration harness | Multi-node integration behavior | Preferred over ad-hoc BLE hardware tests. |
| `connectedCheck` | Device-backed Android checks | Uses currently connected devices. |
| Proof apps and reference app | Physical validation and retained evidence | Evidence surface, not a replacement for automated library tests. |
| JVM benchmark suite | Benchmark-covered operations | Covers retained JVM performance evidence. |

## Reference-app architecture landmarks

Use [Repository layout reference](repository-layout.md) when you need the
repo-level module map.
Use these landmarks when a change touches `meshlink-reference` and you need to
find the shared runtime split quickly.

| Concern | Primary module | Notes |
|---|---|---|
| Route selection and shell wiring | `ReferenceNavHost` | Owns active surface state and route callbacks; does not own session-boundary semantics. |
| Session-boundary execution | `SessionTransitionService` | Owns supported and alternative session boundary sequencing, export-before-boundary completion, and follow-up supported-session starts. |
| Session boundaries and session-state publication | `ReferenceSessionController` | Owns supported live vs ended vs Solo exploration vs Lab session state and publishes the currently active session snapshot. |
| Supported runtime lifecycle and binding | `SupportedControllerRuntime` | Owns supported controller creation, restart, closure, and snapshot binding. |
| Evidence-surface state | `TechnicalTimelineStore` | Owns live and retained evidence state, visible timeline entries, retained-session loading, and export or retention state. |
| Live SDK-backed controller behavior | `LiveReferenceMeshLinkController` + `LiveReferenceMeshRuntime` | The controller stays app-facing; the runtime owns MeshLink API creation, binding, and command delegation. |
| Scripted automation controller behavior | `ScriptedReferenceMeshLinkController` + `ScriptedReferenceMeshRuntime` | Mirrors the live split for deterministic UI automation and proof scripting. |
| Reference-app automation orchestration | `LiveProofAutomationDriver` + `LiveProofAutomationActions` | The driver owns proof-flow progression; the actions bridge automation intents into the current reference-app seams. |

Quick rule of thumb:

- if the change is about **which surface the operator sees**, start in navigation
- if it is about **when one session ends and another begins**, start in `SessionTransitionService`, `chooseSessionSurfaceChoice(...)`, and `ReferenceSessionController`
- if it is about **what evidence the operator can inspect or export**, start in `TechnicalTimelineStore`
- if it is about **how the live or scripted controller talks to MeshLink or scripted state**, start in the matching runtime or controller pair

## Proof-harness architecture landmarks

Use these landmarks when a change touches the retained proof surfaces and you
need to find the right seam quickly.

| Concern | Primary module | Notes |
|---|---|---|
| Android proof host surface | Android proof `MainActivity` | Owns the minimal host UI, permission request loop, and start or stop button wiring. |
| Android proof launch parsing | `ProofLaunchConfig` | Owns intent-extra decoding for mesh domain, power mode, benchmark payload size, and proof transport selection. |
| Android proof runtime ownership | `MeshLinkProofRuntime` | Owns MeshLink lifecycle, peer and diagnostic collection, benchmark receipts, auto-send policy, and persisted proof logs. |
| Android proof benchmark framing | `BenchmarkPayloadEnvelope` + `BenchmarkReceipt` | Own the retained MeshLink benchmark payload and receipt shape used by the Android proof harness. |
| Android proof permission contract | `ProofPermissionContract` | Owns the Bluetooth permission list and permission-result interpretation for the proof host. |
| iOS proof launch parsing | `ProofLaunchConfig` | Owns environment-variable decoding for mesh domain, power mode, benchmark payload size, telemetry, and proof transport selection. |
| iOS proof benchmark-only mode switching | `ProofBenchmarkModeController` | Owns the GATT prototype and GATT-notify prototype start or stop validation so the view model stays focused on host-facing state. |
| iOS proof transport-log capture | `ProofTransportLogCapture` | Owns stdout capture for transport telemetry and forwards only MeshLink transport lines back into the proof log. |
| iOS proof benchmark framing | `BenchmarkPayloadEnvelope` + `BenchmarkReceiptEnvelope` | Own the retained MeshLink benchmark payload and receipt shape used by the iPhone proof harness. |

Quick rule of thumb:

- if the change is about **launch extras or environment variables**, start in `ProofLaunchConfig`
- if it is about **Android proof runtime behavior after the host is already on screen**, start in `MeshLinkProofRuntime`
- if it is about **which non-normative benchmark mode the iPhone host starts**, start in `ProofBenchmarkModeController`
- if it is about **proof-only benchmark payload or receipt framing**, start in the benchmark envelope types for that host

## Repository rules and PR evidence

| Rule or requirement | Current expectation |
|---|---|
| Formatting | All Kotlin code is formatted with ktfmt. |
| Static analysis | Production code is Detekt-clean with zero suppressed issues. Test suppressions require inline justification. |
| Coverage | Line and branch coverage remain at 100% for governed code. |
| Assertion style | Power-assert is the default assertion style for diagnostic value. |
| Public declarations | Explicit visibility modifiers and return types remain required. |
| Unfinished work markers | Merged code does not contain `TODO` comments. |
| Dependency versions | Versions are pinned exactly. |
| Shared logic placement | Shared logic stays in `commonMain`; platform source sets are for `actual` implementations and platform glue. |
| Runtime dependency budget | The shipped `:meshlink` library artifact keeps the current runtime dependency budget unless governance changes; app and host modules must keep their dependencies from leaking into `:meshlink`. |
| Public behavior parity | Android and iOS public behavior stays aligned. |
| Documentation parity | Android and iOS public workflow or API docs update together. |
| Documentation structure | Each user-facing page stays in one Diataxis type. |
| Branching | Governed work happens on feature branches rather than `main`. |
| Commit format | Commits use Conventional Commit messages. |
| Verification evidence | Include the commands you ran in the PR description or handoff. |
| Benchmark evidence | Include it when the change affects benchmarked or performance-sensitive paths. |
| Physical proof evidence | Include it when the change affects transport, discovery, power, or proof behavior on devices. |

## Public API change checklist

A public API change currently requires all of the following:

| Artifact or check | Requirement |
|---|---|
| KDoc | Updated in the same change set as the API change |
| API dump | `./gradlew :meshlink:apiDump` |
| Human-readable API appendix | `python3 scripts/generate_public_api_reference.py` |
| API compatibility check | `./gradlew :meshlink:apiCheck` |
| Documentation verification | `./gradlew verifyDocs` after the appendix and doc updates |
| Android and iOS docs parity | Matching public workflow or API docs updated together |
| PR rationale | Version-bump rationale recorded for the API diff |

## Git hook behavior

| Hook | Behavior |
|---|---|
| `.githooks/pre-commit` | Runs ktfmt formatting for the touched Gradle modules, aborts if formatting changed a staged file, runs `yamllint` for staged YAML files, then runs the relevant Gradle verification tasks for the staged paths. Android or Gradle build-surface changes also run `checkAgp9Invariants`. |
| `.githooks/commit-msg` | Rejects commit messages that do not follow the repository Conventional Commit policy. |
| `.githooks/pre-push` | Inspects the paths and commit subjects in the outgoing push, blocks direct pushes to `main`, validates shell hooks, runs `yamllint` for pushed YAML files, runs benchmark smoke checks for benchmark-sensitive MeshLink paths, and runs the heavier Gradle verification bundle for the affected modules before the push is allowed. Android or Gradle build-surface changes also run `checkAgp9Invariants`. Reference-app paths use the fast local-check bundle instead of the slower full release build. |

## Related docs

- [How to contribute to MeshLink](../../CONTRIBUTING.md)
- [MeshLink Constitution](../../constitution.md)
- [MeshLink documentation map](../README.md)
- [Repository layout reference](repository-layout.md)
- [About the repository architecture](../explanation/about-the-repository-architecture.md)
- [About proof validation surfaces](../explanation/about-proof-validation-surfaces.md)
- [AGP 9 migration plan](../tooling/agp-9-migration-plan.md)
- [Current Dokka and SKIE posture for MeshLink](../tooling/dokka-and-skie-options.md)
- [Glossary and acronym reference](glossary.md)
- [Benchmark and validation baselines](../../benchmarks/README.md)
- [MeshLink reference app overview](../../meshlink-reference/README.md)
- [How to run the Android proof app](../../meshlink-proof/android/README.md)
- [How to build and run the iOS proof app](../../meshlink-proof/ios/README.md)
