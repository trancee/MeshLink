# PROJECT

Verified project profile (required by [`AGENTS.md`](AGENTS.md) and [`KOTLIN.md`](KOTLIN.md)). It records only current, checked state; each decided fact lives in its ADR and is linked, not copied.

## State (2026-11-13)

- **Phase:** build. `main` holds the Gradle scaffold (four empty modules, `build-logic/`) and no product code yet.
- **Decided:** [ADRs 0001–0021](docs/README.md).
- **Open:** the [0.1.0 implementation backlog](.scratch/meshlink-0.1.0-build/spec.md) and its tickets.
- **Prototypes:** throwaway local branches, never merged:
  - `prototype/bearer-bakeoff`: GATT vs L2CAP CoC and PHY comparison on devices.
  - `prototype/routing-sim`: the routing and topology simulator.
  - `prototype/public-api`: the Kotlin and Swift API shape.
  - `prototype/codec`: Kompact vs hand-written codec.
  - `prototype/discovery`: advertising and rotation on devices.
  - `prototype/se2016-bench`: Swift interop and libsodium speed on the iPhone SE (2016).

## Stack and layout

- **Toolchain and targets:** [ADR 0005](docs/adr/0005-toolchain-and-dependency-baseline.md). Built so far: Gradle 9.7.1 (wrapper, distribution sha256 pinned), daemon JDK 25 (`gradle/gradle-daemon-jvm.properties`), compile toolchain and test runtime JDK 21, Kotlin 2.4.21, AGP 9.3.3 with `com.android.kotlin.multiplatform.library` (compileSdk 37, minSdk 26), `iosArm64` and `iosSimulatorArm64`. The configuration cache is on. Coverage, formatting, static analysis and API checks arrive with their backlog tickets.
- **Toolchain discovery:** JDK auto-download is off and no toolchain repository is configured, so JDK 21 and JDK 25 must be installed locally where Gradle can detect them (for example SDKMAN or `/Library/Java/JavaVirtualMachines`).
- **Dependencies:** `gradle/libs.versions.toml` is the only version source; every dependency and CI tool is in the [dependency register](docs/reference/dependencies.md).
- **Modules** ([ADR 0011](docs/adr/0011-modules-toggles-and-presets.md) decision 2). Every module applies the `meshlink.kmp-library` convention plugin, which derives the Android `namespace` from the module path:

  | Module | Android namespace | May depend on |
  |---|---|---|
  | `:meshlink-core` | `ch.trancee.meshlink.core` | nothing |
  | `:meshlink-crypto-sodium` | `ch.trancee.meshlink.crypto.sodium` | `:meshlink-core` |
  | `:meshlink` | `ch.trancee.meshlink` | `:meshlink-core`, `:meshlink-crypto-sodium` |
  | `:meshlink-testing` | `ch.trancee.meshlink.testing` | `:meshlink` |

  `checkDependencyDirection`, wired into `check`, fails on any project dependency outside this allow-list, in every configuration including tests (except the aggregate configuration Kotlin 2.4 creates for SwiftPM lock files), and fails closed on a module with no entry.
- **Layout:**

  | Path | Holds |
  |---|---|
  | `build-logic/` | Included build: the `meshlink.kmp-library` and `meshlink.dependency-direction` convention plugins and their TestKit tests |
  | `meshlink-core/`, `meshlink-crypto-sodium/`, `meshlink/`, `meshlink-testing/` | The four Gradle modules |
  | `gradle/` | Version catalog, wrapper and daemon JDK declaration |
  | `docs/` | Diátaxis docs, ADRs and research ([index](docs/README.md)) |
  | `tools/` | Spec vectors and checks, privacy scan, ADR check, lab tools |
  | `.scratch/` | Local issue tracker ([conventions](docs/agents/issue-tracker.md)) |
  | `.github/workflows/` | Docs, privacy and CodeQL checks |
  | `GLOSSARY.md` | Domain terms |

## Commands

Docs tools verified locally on 2026-11-01 (macOS, Homebrew lychee 0.24.2 and typos-cli 1.51.1, Node 24); CI runs the same tools at the same versions from [`docs.yml`](.github/workflows/docs.yml). Gradle commands verified on 2026-11-13 (macOS 26, JDK 21 and 25 installed, Android SDK platform 37).

| Check | Command |
|---|---|
| Build checks, all modules and `build-logic` tests | `./gradlew check` |
| iOS simulator tests | `./gradlew iosSimulatorArm64Test` (macOS only) |
| One focused Android host test | `./gradlew :<module>:testAndroidHostTest --tests '<pattern>'` |
| `build-logic` tests only | `./gradlew -p build-logic test` |
| Markdown lint | `npx -y markdownlint-cli2@0.24.0` |
| Spelling (US English) | `typos` |
| Links, repository only | `lychee --offline .` |
| Links, including external | `lychee .` (set `GITHUB_TOKEN` to avoid GitHub rate limits) |
| Workflow lint | `actionlint` |

## Proof matrix

The `{module,target,host,prerequisites,task,proof,limitations}` matrix that `KOTLIN.md` requires. The modules hold no sources yet, so each row proves the build wiring, not product behavior.

| Module | Target | Host | Prerequisites | Task | Proof | Limitations |
|---|---|---|---|---|---|---|
| all four | Android (host tests) | Linux, macOS | JDK 21 and 25, Android SDK platform 37 | `testAndroidHostTest` (via `check`) | Compilation and JVM host-test runtime | No device; Robolectric arrives with its ticket |
| all four | `iosSimulatorArm64` | macOS only | Xcode per ADR 0005 | `iosSimulatorArm64Test` | Compilation and simulator test runtime | Simulator, not hardware (T9) |
| all four | `iosArm64` | macOS only | Xcode per ADR 0005 | `compileKotlinIosArm64` | Compilation only | No device run in the build |
| `build-logic` | JVM | Linux, macOS | JDK 21 and 25 | `test` (via root `check`) | Gradle TestKit runs of the dependency-direction check | Fixture builds use a `jvm()` target only |

Device hosts, recorded for that matrix ([device lab](.scratch/meshlink-v1/issues/06-device-lab-readiness.md)):

- iOS 17 and later: CoreDevice (`xcrun devicectl`).
- iOS 15 and 16: libimobiledevice (`ideviceinstaller`, `idevicedebug`), because `devicectl` lists them by ECID only.
- Android: adb, local or remote.

## Limitations

- The build has no CI workflow yet (backlog ticket 05). Coverage, formatting, static analysis and API gates arrive with their backlog tickets.
- Android and iOS radio behavior is proved on hardware only (Constitution T9). The routing simulator results (ADR 0008) are not device evidence.
