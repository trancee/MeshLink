# Gate the build with Kover, detekt, Spotless, ABI validation and Dokka

Status: ready-for-agent
Type: AFK
Blocked by: 01

## Behavior

`./gradlew check` enforces coverage, static analysis, formatting and the public ABI dump, and `./gradlew dokkaGenerate` builds the API docs without a warning. Every later ticket inherits these gates. There is no product behavior yet.

## Spec and ADR references

- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) "Version tuple" (Kover 0.9.11, Spotless 8.11.0 with ktlint 1.8.0, detekt 2.0.0-alpha.6, Dokka 2.3.0), "Tools" (the E2 holds and their wiring) and "Coverage" (`commonMain` and `androidMain` at 100% line and branch; `iosMain` thin adapters only).
- [ADR 0019](../../../docs/adr/0019-release-versioning-and-api-compatibility.md) decision 4 (committed KGP ABI dumps, `explicitApi()` strict) and decision 6 (`CHANGELOG.md` and `checkChangelog` in `check`).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 3 (Kover merges `:meshlink` and `:meshlink-testing`) and decision 13 (no retries; every test has a timeout).
- [ADR 0006](../../../docs/adr/0006-documentation-architecture.md) decision 8 (Dokka HTML API reference).
- [`KOTLIN.md`](../../../KOTLIN.md) tool reference for Dokka, Kover, Spotless, ABI and detekt; Constitution Q1, Q2, T3, E2.

## Acceptance criteria

- [ ] Kover 0.9.11 measures `commonMain` and `androidMain` of every module through the Android host tests, merged into one report, and `koverVerify` (wired into `check`) requires 100% line and branch coverage. The Kover profile records modules, source sets, test tasks, exclusions, engine, thresholds and unmeasured targets (`iosMain`), as `KOTLIN.md` requires.
- [ ] Shown in the PR, not committed: a seeded uncovered branch makes `./gradlew check` fail, and a test for it makes it pass again.
- [ ] detekt 2.0.0-alpha.6 runs per KMP source set, wired into `check`, because the plain task finds no sources. No baseline file and no production suppression (Q2).
- [ ] Spotless 8.11.0 with ktlint 1.8.0 checks Kotlin and Gradle Kotlin scripts; `spotlessCheck` is in `check`; `spotlessApply` run twice gives a zero diff the second time.
- [ ] KGP built-in `abiValidation` (the ADR 0005 choice, under `@OptIn(ExperimentalAbiValidation::class)`, not the legacy binary-compatibility-validator) runs for every published module. Its check task is wired into `check`, and the dumps are committed. Shown in the PR: a public-API change fails the check until the dump is updated.
- [ ] `CHANGELOG.md` exists in Keep a Changelog style with an "Unreleased" section. A `checkChangelog` task in `build-logic/` (no new dependency) is in `check` and fails when an ABI dump changes without a changelog change. A TestKit test proves the failure (T1).
- [ ] Dokka 2.3.0 (DGP v2) applies to every documentable module and aggregates them; `reportUndocumented` and `failOnWarning` are on; `./gradlew dokkaGenerate` finishes with zero warnings.
- [ ] Every `Test` task has a per-test timeout, and no task retries a failed test.
- [ ] Each E2 hold (detekt alpha, experimental ABI validation, Kover Beta) keeps its blocker and removal condition in the dependency register.
- [ ] `./gradlew check`, `./gradlew iosSimulatorArm64Test` and `./gradlew dokkaGenerate` pass on a clean checkout.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew dokkaGenerate
npx --yes markdownlint-cli2@0.24.1
typos
lychee --offline .
```

## Docs to update

- `PROJECT.md`: commands (`check` now includes the gates, `dokkaGenerate`), the Kover profile, and proof-matrix rows for coverage and ABI proof.
- `docs/reference/dependencies.md`: confirm the Kover, Spotless, ktlint, detekt and Dokka rows match the catalog.
- `CHANGELOG.md`: created.

## Devices needed

None.
