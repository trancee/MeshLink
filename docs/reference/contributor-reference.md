# Contributor build, test, and verification reference

This page describes the contributor-facing tooling, commands, verification
bundles, and repository rules for MeshLink changes.

Use [How to contribute to MeshLink](../../CONTRIBUTING.md) for the guided flow.
Use this page when you need exact commands, matrices, or policy details.

## Workstation requirements

| Need | Requirement | Notes |
|---|---|---|
| Gradle build and tests | JDK 17 | Required for the repository build. |
| Android compilation and unit tests | Android SDK for API 35 builds | Required for Android targets. |
| iOS compilation and tests | Xcode | Required for iOS build and test targets. |
| Documentation verification | Python 3 | Required by the documentation verification scripts. |
| iOS project regeneration after project-spec changes | `xcodegen` | Needed only when the committed iOS project spec changes. |
| Final full-library validation | macOS | Required for the Android + iOS contributor flow in one workstation. |

## Core contributor commands

| Purpose | Command | Notes |
|---|---|---|
| Warm the checkout | `./gradlew help` | Confirms the wrapper works and downloads plugins/toolchains. |
| Install repository Git hooks | `./scripts/install-git-hooks.sh` | Sets `core.hooksPath` to `.githooks` and enables the pre-commit checks. |
| Build the library | `./gradlew :meshlink:build` | Standard first build for the library module. |
| Compile without the full build | `./gradlew :meshlink:assemble` | Useful during early edit loops. |
| Format Kotlin | `./gradlew :meshlink:ktfmtFormat` | Repository formatting is ktfmt-based. |
| Run the full library test bundle | `./gradlew :meshlink:allTests` | Aggregates tests across the library targets. |
| Run JVM tests | `./gradlew :meshlink:jvmTest` | Fast shared-library feedback loop. |
| Run Android unit tests | `./gradlew :meshlink:testDebugUnitTest` | Default Android-side unit test task. |
| Run iOS tests on Apple Silicon | `./gradlew :meshlink:iosSimulatorArm64Test` | Use on Apple Silicon Macs. |
| Run iOS tests on Intel | `./gradlew :meshlink:iosX64Test` | Use on Intel Macs. |
| Run static analysis | `./gradlew :meshlink:detekt` | Production code must remain Detekt-clean. |
| Check API compatibility | `./gradlew :meshlink:apiCheck` | Verifies the tracked public API surface. |
| Verify coverage | `./gradlew :meshlink:koverVerify` | Coverage gate for the library module. |
| Run docs verification | `./gradlew verifyDocs` | Checks the generated API appendix and markdown links. |
| Run connected-device checks | `./gradlew :meshlink:connectedCheck` | Uses currently connected devices. |
| Refresh the checked-in API dump | `./gradlew :meshlink:apiDump` | Required for intentional public API changes. |
| Run JVM smoke benchmarks | `./gradlew :benchmarks:jvmSmokeBenchmark` | Fast development-time performance check. |
| Run retained JVM benchmarks | `./gradlew :benchmarks:jvmBenchmark` | Use when retained benchmark evidence is required. |

## Verification bundles by change type

| Change type | Expected verification |
|---|---|
| Docs-only change | `./gradlew verifyDocs` |
| Shared-library logic change | `./gradlew :meshlink:allTests :meshlink:detekt :meshlink:koverVerify` |
| Android-specific library glue | `./gradlew :meshlink:testDebugUnitTest :meshlink:detekt :meshlink:koverVerify` plus `:meshlink:allTests` when parity or shared behavior is affected |
| iOS-specific library glue | `./gradlew :meshlink:iosSimulatorArm64Test` or `:meshlink:iosX64Test`, plus `:meshlink:detekt :meshlink:koverVerify`; use `:meshlink:allTests` when parity or shared behavior is affected |
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
| Runtime dependency budget | `:meshlink` keeps the current runtime dependency budget unless governance changes. |
| Public behavior parity | Android and iOS public behavior stays aligned. |
| Documentation parity | Android and iOS public workflow/API docs update together. |
| Documentation structure | Each user-facing page stays in one Diátaxis type. |
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
| `.githooks/pre-commit` | Runs ktfmt formatting for the touched Gradle modules, aborts if formatting changed a staged file, then runs the relevant Gradle verification tasks for the staged paths. |

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
- [Benchmark and validation baselines](../../benchmarks/README.md)
- [How to use the MeshLink reference app](../../meshlink-reference/README.md)
- [How to run the Android proof app](../../meshlink-proof/android/README.md)
- [How to build and run the iOS proof app](../../meshlink-proof/ios/README.md)
