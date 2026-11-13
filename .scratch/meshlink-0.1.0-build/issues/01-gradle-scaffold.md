# Build the four modules with `build-logic` and the dependency-direction check

Status: ready-for-agent
Type: AFK

## Behavior

The repository gains its first Gradle build: four empty Kotlin Multiplatform modules, an included `build-logic/` build with the convention plugins, and a version catalog. `./gradlew check` fails when a module depends on a project outside its allow-list. There is no product behavior yet.

## Spec and ADR references

- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md) decision 2 (modules and their allowed dependencies), decision 6 (`build-logic/`), and the 2026-11-13 amendments "coordinates and names" (packages, Android `namespace`) and "toolchain decision" (the direction check, its allow-list and its forbidden-edge test).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) "Version tuple" and "Targets" (Kotlin 2.4.21, Gradle 9.7.1, AGP 9.3.3 with `com.android.kotlin.multiplatform.library`, daemon JDK 25, compile toolchain and test runtime 21, compileSdk 37, minSdk 26, `iosArm64` and `iosSimulatorArm64`, no `iosX64`), and "Dependency register".
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) context: there is no `jvm()` target; Android host tests run `commonTest` on the JVM.
- [ADR 0019](../../../docs/adr/0019-release-versioning-and-api-compatibility.md) decision 4 (`explicitApi()` strict).
- [`KOTLIN.md`](../../../KOTLIN.md) "Build" and "KMP proof"; Constitution Q4, E1, E2, E3.

## Acceptance criteria

- [ ] `settings.gradle.kts` includes `:meshlink-core`, `:meshlink-crypto-sodium`, `:meshlink` and `:meshlink-testing`, and includes `build-logic/` as an included build.
- [ ] The Gradle wrapper pins 9.7.1; the daemon JDK is declared as 25, separately from the JDK 21 compile toolchain and test runtime.
- [ ] `gradle/libs.versions.toml` is the only version source and holds exactly the ADR 0005 pins that this ticket uses. Later tools are added by the tickets that wire them.
- [ ] A convention plugin configures every module with the Android library target (compileSdk 37, minSdk 26), `iosArm64` and `iosSimulatorArm64`, typed `compilerOptions {}` and `explicitApi()` strict. The Android `namespace` of `:meshlink` is `ch.trancee.meshlink`; the other modules get unique namespaces under it.
- [ ] Host tests run: every module enables Android-KMP library host tests with `withHostTest {}`, so `commonTest` runs on the JVM.
- [ ] The direction check is a convention plugin in `build-logic/` with no new dependency. It reads `ProjectDependency.path` from every configuration, including KMP source-set and test configurations, and is wired into `check`. Allow-list: `:meshlink-core` → none; `:meshlink-crypto-sodium` → `:meshlink-core`; `:meshlink` → both; `:meshlink-testing` → `:meshlink`.
- [ ] A Gradle TestKit test in `build-logic/` proves that a forbidden edge (for example `:meshlink-core` → `:meshlink`) fails `check` with a message naming both modules, and that the allowed graph passes (T1: the test is red before the check exists). Root `check` runs the `build-logic` tests.
- [ ] `./gradlew check` and `./gradlew iosSimulatorArm64Test` pass on a clean checkout with the configuration cache on.
- [ ] Focused test command: the spine fixes it as `./gradlew :<module>:testAndroidHostTest --tests '<pattern>'` (Android-KMP library plugin host tests; there is no `jvm()` target, ADR 0017). Verify the exact task name that `withHostTest {}` creates and that `--tests` filters it. If the name differs, update the spine's proof-command table and every ticket that cites it in this PR (spine rename rule, O1).
- [ ] `PROJECT.md` and `docs/reference/dependencies.md` agree with the build (see below).

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew :meshlink-core:testAndroidHostTest --tests '*'   # name verified by this ticket; see the criterion above
npx --yes markdownlint-cli2@0.24.1
typos
lychee --offline .
python3 tools/check_private.py
```

## Docs to update

- `PROJECT.md`: state (phase leaves "design"), layout table (`build-logic/`, `gradle/`, the four modules), the command table, and the first rows of the `{module,target,host,prerequisites,task,proof,limitations}` proof matrix that `KOTLIN.md` requires (Android host tests on Linux and macOS; `iosSimulatorArm64Test` on macOS only, as compilation and runtime proof; `iosArm64` as compilation proof only).
- `docs/reference/dependencies.md`: one E3 row for every catalog entry this ticket adds that has no row yet.
- `.scratch/meshlink-0.1.0-build/spec.md` and every citing ticket: the focused-test command, only if the verified task name differs from `testAndroidHostTest`.

## Devices needed

None.
