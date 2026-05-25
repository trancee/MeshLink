# Contributor build, test, and verification reference

This reference lists the contributor-facing tooling, commands, verification
bundles, and repository rules for MeshLink changes.

Use [How to contribute to MeshLink](../../CONTRIBUTING.md) for the guided flow.
Use this page when you need exact commands, matrices, or policy details.

## Workstation requirements

| Need | Requirement | Notes |
|---|---|---|
| Gradle build and tests | JDK 17 | Required for the repository build. |
| Android compilation and unit tests | Android SDK for API 36 builds | Required for Android targets. |
| iOS compilation and tests | Xcode | Required for iOS build and test targets. |
| Documentation verification | Python 3 | Required by the documentation verification scripts. |
| YAML validation | `yamllint` | Required when staged YAML files are part of the change. |
| iOS project regeneration after project-spec changes | `xcodegen` | Needed only when the committed iOS project spec changes. |
| Final full-library validation | macOS | Required for the Android + iOS contributor flow in one workstation. |

## Core contributor commands

| Purpose | Command | Notes |
|---|---|---|
| Warm the checkout | `./gradlew help` | Confirms the wrapper works and downloads plugins/toolchains. |
| Install repository Git hooks | `./scripts/install-git-hooks.sh` | Sets `core.hooksPath` to `.githooks` and enables the repository hook suite. |
| Run the pre-push hook manually | `.githooks/pre-push <remote> <url>` | Feed it the standard Git pre-push ref lines on stdin when you want to simulate a push locally. |
| Build the library | `./gradlew :meshlink:build` | Standard first build for the library module. |
| Compile without the full build | `./gradlew :meshlink:assemble` | Useful during early edit loops. |
| Run fast reference-app verification | `./scripts/run-reference-local-check.sh` | Runs `:meshlink-reference:localCheck` on the current AGP 9 toolchain. |
| Run the AGP 9 build-surface verification bundle | `./scripts/run-agp9-verification.sh` | Runs the post-migration invariant check plus the cross-module verification bundle used for Gradle, AGP, Kotlin, or module-shape changes. |
| Build the reference app | `./gradlew :meshlink-reference:build` | Use when you need release APK or iOS framework artifacts; slower because it links release frameworks for all iOS targets. |
| Format Kotlin | `./gradlew :meshlink:ktfmtFormat` | Repository formatting is ktfmt-based. |
| Run the full library test bundle | `./gradlew :meshlink:allTests` | Aggregates tests across the library targets. |
| Run JVM tests | `./gradlew :meshlink:jvmTest` | Fast shared-library feedback loop. |
| Run Android unit tests | `./gradlew :meshlink:testDebugUnitTest` | Default Android-side unit test task. |
| Run iOS tests on Apple Silicon | `./gradlew :meshlink:iosSimulatorArm64Test` | Supported iOS simulator target for local verification. |
| Run static analysis | `./gradlew :meshlink:detekt` | Production code must remain Detekt-clean. |
| Check API compatibility | `./gradlew :meshlink:apiCheck` | Verifies the tracked public API surface. |
| Verify coverage | `./gradlew :meshlink:koverVerify` | Coverage gate for the library module. |
| Check AGP 9 build invariants | `./gradlew checkAgp9Invariants` | Confirms the post-migration module/plugin shape and preserved reference-app compatibility tasks stay intact. |
| Run docs verification | `./gradlew verifyDocs` | Checks the generated API appendix and markdown links. |
| Generate all useful Dokka HTML | `./gradlew dokkaGenerateAllHtml` | Generates contributor-useful Dokka output for `:meshlink` and `:meshlink-reference` without selecting Dokka's deprecated per-project `dokkaHtml` tasks. |
| Generate Dokka HTML for the SDK | `./gradlew :meshlink:dokkaGenerateHtml` | Writes supplemental Kotlin API reference output to `meshlink/build/dokka/html/`. |
| Generate Dokka HTML for the shared reference app module | `./gradlew :meshlink-reference:dokkaGenerateHtml` | Writes contributor-facing shared-module output to `meshlink-reference/build/dokka/html/`. |
| Run connected-device checks | `./gradlew :meshlink:connectedCheck` | Uses currently connected devices. |
| Refresh the checked-in API dump | `./gradlew :meshlink:apiDump` | Required for intentional public API changes. |
| Run JVM smoke benchmarks | `./gradlew :benchmarks:jvmSmokeBenchmark` | Fast development-time performance check. |
| Run retained JVM benchmarks | `./gradlew :benchmarks:jvmBenchmark` | Use when retained benchmark evidence is required. |

## Verification bundles by change type

| Change type | Expected verification |
|---|---|
| Docs-only change | `./gradlew verifyDocs` |
| Build-tooling or module-shape change | `./scripts/run-agp9-verification.sh` for the full bundle, or `./gradlew checkAgp9Invariants` plus the relevant module builds or checks when you want a narrower loop |
| Shared-library logic change | `./gradlew :meshlink:allTests :meshlink:detekt :meshlink:koverVerify` |
| Android-specific library glue | `./gradlew :meshlink:testDebugUnitTest :meshlink:detekt :meshlink:koverVerify` plus `:meshlink:allTests` when parity or shared behavior is affected |
| iOS-specific library glue | `./gradlew :meshlink:iosSimulatorArm64Test`, plus `:meshlink:detekt :meshlink:koverVerify`; use `:meshlink:allTests` when parity or shared behavior is affected |
| Reference-app shared UI, Android glue, or shared state change | `./scripts/run-reference-local-check.sh` |
| Reference-app release artifact or framework export change | `./gradlew :meshlink-reference:build` |
| Public API change | `./gradlew :meshlink:allTests :meshlink:detekt :meshlink:apiCheck :meshlink:koverVerify verifyDocs`, plus API dump and appendix refresh |
| Performance-sensitive path | the relevant automated verification above plus `./gradlew :benchmarks:jvmSmokeBenchmark`; use `:benchmarks:jvmBenchmark` when retained evidence is required |
| Android/iOS physical transport or proof behavior | the relevant automated verification above plus the appropriate proof-app or reference-app validation flow |

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

Use these landmarks when a change touches `meshlink-reference` and you need to
find the right module quickly.

| Concern | Primary module | Notes |
|---|---|---|
| Route selection and shell wiring | `ReferenceNavHost` | Owns active surface state and route callbacks; does not own session-boundary semantics. |
| Session-boundary execution | `SessionBoundaryCoordinator` | Owns supported/alternative session boundary sequencing, export-before-boundary completion, and follow-up supported-session starts. |
| Session boundaries and session-state publication | `ReferenceSessionController` | Owns supported live vs ended vs solo vs lab session state and publishes the currently active session snapshot. |
| Supported runtime lifecycle and binding | `SupportedControllerRuntime` | Owns supported controller creation, restart, closure, and snapshot binding. |
| Evidence-surface state | `TechnicalTimelineStore` | Owns live/retained evidence state, visible timeline entries, retained-session loading, and export/retention state. |
| Live SDK-backed controller behavior | `LiveReferenceMeshLinkController` + `LiveReferenceMeshRuntime` | The controller stays app-facing; the runtime owns MeshLink API creation, binding, and command delegation. |
| Scripted automation controller behavior | `ScriptedReferenceMeshLinkController` + `ScriptedReferenceMeshRuntime` | Mirrors the live split for deterministic UI automation and proof scripting. |
| Reference-app automation orchestration | `LiveProofAutomationDriver` + `LiveProofAutomationActions` | The driver owns proof-flow progression; the actions bridge automation intents into the current reference-app seams. |

A quick rule of thumb:

- If the change is about **which surface the operator sees**, start in navigation.
- If it is about **when one session ends and another begins**, start in `SessionBoundaryCoordinator`, `SurfaceSelectionPolicy`, and `ReferenceSessionController`.
- If it is about **what evidence the operator can inspect or export**, start in `TechnicalTimelineStore`.
- If it is about **how the live or scripted controller talks to MeshLink or scripted state**, start in the matching runtime/controller pair.

## Coding and documentation rules

| Rule | Current requirement |
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
| Documentation parity | Android and iOS public workflow/API docs update together. |
| Documentation structure | Each user-facing page stays in one Diataxis type. |
| Branching | Governed work happens on feature branches rather than `main`. |
| Commit format | Commits use Conventional Commit messages. |

## Public API change requirements

A public API change currently requires all of the following:

| Artifact or check | Requirement |
|---|---|
| KDoc | Updated in the same change set as the API change |
| API dump | `./gradlew :meshlink:apiDump` |
| Human-readable API appendix | `python3 scripts/generate_public_api_reference.py` |
| API compatibility check | `./gradlew :meshlink:apiCheck` |
| Documentation verification | `./gradlew verifyDocs` after the appendix and doc updates |
| Android/iOS docs parity | Matching public workflow or API docs updated together |
| PR rationale | Version-bump rationale recorded for the API diff |

## Git hook behavior

| Hook | Behavior |
|---|---|
| `.githooks/pre-commit` | Runs ktfmt formatting for the touched Gradle modules, aborts if formatting changed a staged file, runs `yamllint` for staged YAML files, then runs the relevant Gradle verification tasks for the staged paths. Android/Gradle build-surface changes also run `checkAgp9Invariants`. |
| `.githooks/commit-msg` | Rejects commit messages that do not follow the repository Conventional Commit policy. |
| `.githooks/pre-push` | Inspects the paths and commit subjects in the outgoing push, blocks direct pushes to `main`, validates shell hooks, runs `yamllint` for pushed YAML files, runs benchmark smoke checks for benchmark-sensitive MeshLink paths, and runs the heavier Gradle verification bundle for the affected modules before the push is allowed. Android/Gradle build-surface changes also run `checkAgp9Invariants`. Reference-app paths use the fast local-check bundle instead of the slower full release build. |

## PR and evidence requirements

| Requirement | Details |
|---|---|
| Branch | Use a feature branch. |
| Commit message format | Use Conventional Commits such as `fix:`, `test:`, or `docs:`. |
| Verification evidence | Include the commands you ran in the PR description or handoff. |
| Benchmark evidence | Include it when the change affects benchmarked or performance-sensitive paths. |
| Physical proof evidence | Include it when the change affects transport, discovery, power, or proof behavior on devices. |

## Related documents

- [How to contribute to MeshLink](../../CONTRIBUTING.md)
- [MeshLink Constitution](../../constitution.md)
- [MeshLink documentation map](../README.md)
- [AGP 9 migration plan](../tooling/agp-9-migration-plan.md)
- [Current Dokka and SKIE posture for MeshLink](../tooling/dokka-and-skie-options.md)
- [Benchmark and validation baselines](../../benchmarks/README.md)
- [MeshLink reference app overview](../../meshlink-reference/README.md)
- [How to run the Android proof app](../../meshlink-proof/android/README.md)
- [How to build and run the iOS proof app](../../meshlink-proof/ios/README.md)
