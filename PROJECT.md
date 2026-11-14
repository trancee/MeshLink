# PROJECT

Verified project profile (required by [`AGENTS.md`](AGENTS.md) and [`KOTLIN.md`](KOTLIN.md)). It records only current, checked state; each decided fact lives in its ADR and is linked, not copied.

## State (2026-11-14)

- **Phase:** build. `main` holds the Gradle scaffold (four modules, `build-logic/`) with its quality gates, Maven publishing of `meshlink` and the `MeshLinkKit` XCFramework. `:meshlink` holds only the generated internal `BuildInfo`. `:meshlink-core` holds the internal `CryptoProvider` and the spec's hash derivations (NodeId, mesh identifier digest, epoch, mesh tag). `:meshlink-crypto-sodium` builds static libsodium 1.0.22 for every target from the verified upstream tarball and implements `CryptoProvider` over it: a JNI shim on Android and the JVM test hosts, cinterop on iOS. Nothing in `:meshlink` calls it yet.
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

- **Toolchain and targets:** [ADR 0005](docs/adr/0005-toolchain-and-dependency-baseline.md). Built so far: Gradle 9.7.1 (wrapper, distribution sha256 pinned), daemon JDK 25 (`gradle/gradle-daemon-jvm.properties`), compile toolchain and test runtime JDK 21, Kotlin 2.4.21, AGP 9.3.3 with `com.android.kotlin.multiplatform.library` (compileSdk 37, minSdk 26), `iosArm64` and `iosSimulatorArm64`. The configuration cache is on.
- **Toolchain discovery:** JDK auto-download is off and no toolchain repository is configured, so JDK 21 and JDK 25 must be installed locally where Gradle can detect them (for example SDKMAN or `/Library/Java/JavaVirtualMachines`).
- **License:** The Unlicense ([`LICENSE`](LICENSE)). Dependency licenses are in the dependency register.
- **Dependencies:** `gradle/libs.versions.toml` is the only version source; every dependency and CI tool is in the [dependency register](docs/reference/dependencies.md).
- **Modules** ([ADR 0011](docs/adr/0011-modules-toggles-and-presets.md) decision 2). Every module applies the `meshlink.kmp-library` convention plugin, which derives the Android `namespace` from the module path:

  | Module | Android namespace | May depend on |
  |---|---|---|
  | `:meshlink-core` | `ch.trancee.meshlink.core` | nothing |
  | `:meshlink-crypto-sodium` | `ch.trancee.meshlink.crypto.sodium` | `:meshlink-core` |
  | `:meshlink` | `ch.trancee.meshlink` | `:meshlink-core`, `:meshlink-crypto-sodium` |
  | `:meshlink-testing` | `ch.trancee.meshlink.testing` | `:meshlink` |

  `:meshlink` and `:meshlink-testing` also apply `meshlink.published-library`: they are the published, documented modules inside the ABI promise ([ADR 0019](docs/adr/0019-release-versioning-and-api-compatibility.md)). The root build applies `meshlink.root`.

  `:meshlink` also applies `meshlink.build-info`, which generates the internal `ch.trancee.meshlink.internal.BuildInfo` object holding the library version, and SKIE. It builds the `MeshLinkKit` XCFramework (task `:meshlink:assembleMeshLinkKitReleaseXCFramework`, output `meshlink/build/XCFrameworks/release/`).
- **Publishing:** `meshlink.published-library` configures vanniktech maven-publish: groupId `ch.trancee.meshlink`, the module name as artifact ID, one version from `meshlink.version` in `gradle.properties` (`0.1.0-SNAPSHOT` until the release PR), a POM under The Unlicense and the Dokka HTML as javadoc JAR. Signing and Maven Central upload are off (tickets 58 and 60). Only `:meshlink` publishes so far; see Limitations.

  `checkDependencyDirection`, wired into `check`, fails on any project dependency outside this allow-list, in every configuration including tests (except the aggregate configuration Kotlin 2.4 creates for SwiftPM lock files), and fails closed on a module with no entry.
- **Gates in `check`** (versions and E2 holds: [ADR 0005](docs/adr/0005-toolchain-and-dependency-baseline.md)):

  | Gate | Task | Fails when |
  |---|---|---|
  | Coverage | `koverVerify` (root) | Merged line or branch coverage is below 100% (see the Kover profile below) |
  | Static analysis | `detekt<SourceSet>SourceSet` in every module | Any detekt finding. Config: detekt defaults plus [`config/detekt/detekt.yml`](config/detekt/detekt.yml), which only widens the test-code excludes. No baseline, no suppressions |
  | Formatting | `spotlessCheck` (root) | A `.kt` or `.gradle.kts` file differs from ktlint 1.8.0 formatting; fix with `./gradlew spotlessApply` |
  | Public ABI | `checkKotlinAbi` in the published modules | The ABI differs from the committed dumps in `<module>/api/`; update with `./gradlew updateKotlinAbi` |
  | Changelog | `checkChangelog` (root) | An `api/**/*.api` dump changed since the merge base with `origin/main` (override: `-Pmeshlink.changelog.base=<ref>`) without a `CHANGELOG.md` change; fails closed if the base ref is missing |
  | Native symbols | `checkSodiumJniSymbols` in `:meshlink-crypto-sodium` | An Android JNI shim exports a `sodium_`, `crypto_` or `randombytes_` symbol, or exports or imports a symbol matching `aes`, `aegis` or `ipcrypt` ([ADR 0004](docs/adr/0004-crypto-provider-libsodium.md) 2026-11-14 amendment) |
  | Native size | `checkSodiumJniSize` in `:meshlink-crypto-sodium` | An Android JNI shim is over 1,000,000 bytes |
  | Thin adapters | `checkThinAdapters` (root) | [`config/thin-adapters.txt`](config/thin-adapters.txt) doesn't name exactly the Kotlin/Native main source files (`*/src/{ios,apple,native}*Main/**/*.kt`) |
  | Test timeout | every test task | A test task runs over 10 minutes ([ADR 0017](docs/adr/0017-testing-architecture.md) decision 13); no task retries a failed test |

- **Kover profile** (`KOTLIN.md`):

  | Field | Value |
  |---|---|
  | Modules | `:meshlink-core`, `:meshlink-crypto-sodium`, `:meshlink`, `:meshlink-testing`, merged in the root report |
  | Source sets | `commonMain` and `androidMain`, as compiled for the Android target |
  | Test tasks | `testAndroidHostTest` in every module |
  | Exclusions | None |
  | Engine | Kover default (IntelliJ), the same for every module |
  | Thresholds | 100% line and 100% branch, on the merged report |
  | Unmeasured targets | `iosMain` and every other Kotlin/Native source set: no CI coverage tool exists (Constitution T3 4.0.0); thin adapters only, listed in [`config/thin-adapters.txt`](config/thin-adapters.txt) and tested on target |

- **Layout:**

  | Path | Holds |
  |---|---|
  | `build-logic/` | Included build: the `meshlink.kmp-library`, `meshlink.published-library`, `meshlink.build-info`, `meshlink.robolectric`, `meshlink.root`, `meshlink.dependency-direction`, `meshlink.changelog`, `meshlink.libsodium` and `meshlink.spec-vectors` convention plugins, the native gate tasks, and their tests |
  | `meshlink-core/`, `meshlink-crypto-sodium/`, `meshlink/`, `meshlink-testing/` | The four Gradle modules; `meshlink/api/` and `meshlink-testing/api/` hold the committed ABI dumps |
  | `config/detekt/` | detekt config on top of the defaults |
  | `config/thin-adapters.txt` | The Kotlin/Native main source files, all thin adapters (Constitution T3) |
  | `CHANGELOG.md` | Keep a Changelog history ([ADR 0019](docs/adr/0019-release-versioning-and-api-compatibility.md) decision 6) |
  | `gradle/` | Version catalog, wrapper and daemon JDK declaration |
  | `docs/` | Diátaxis docs, ADRs and research ([index](docs/README.md)) |
  | `tools/` | Spec vectors and checks, privacy scan, ADR check, lab tools |
  | `tools/swiftpm-consumer/` | SwiftPM package that consumes the built XCFramework through a local `binaryTarget`, and its `test.sh` |
  | `LICENSE` | The Unlicense |
  | `.scratch/` | Local issue tracker ([conventions](docs/agents/issue-tracker.md)) |
  | `.github/workflows/` | Build, docs, privacy and CodeQL checks |
  | `GLOSSARY.md` | Domain terms |

## Commands

Docs tools verified locally on 2026-11-01 (macOS, Homebrew lychee 0.24.2 and typos-cli 1.51.1, Node 24); CI runs the same tools at the same versions from [`docs.yml`](.github/workflows/docs.yml). Gradle commands verified on 2026-11-14 (macOS 26, JDK 21 and 25 installed, Android SDK platform 37).

| Check | Command |
|---|---|
| Build checks: every gate above, all module tests and `build-logic` tests | `./gradlew check` |
| iOS simulator tests | `./gradlew iosSimulatorArm64Test` (macOS only) |
| One focused Android host test | `./gradlew :<module>:testAndroidHostTest --tests '<pattern>'` |
| `build-logic` tests only | `./gradlew -p build-logic test` |
| Coverage report (HTML; `koverLog` prints the totals) | `./gradlew koverHtmlReport` |
| Format all Kotlin and Gradle Kotlin files | `./gradlew spotlessApply` |
| Publish `meshlink` into Maven local (`-Dmaven.repo.local=<dir>` for a throwaway repository) | `./gradlew :meshlink:publishToMavenLocal` |
| SwiftPM consumer of the XCFramework on an iOS Simulator (macOS only; set `DEVELOPER_DIR` to the Xcode of ADR 0005; `MESHLINK_SIMULATOR_DESTINATION` overrides the destination) | `tools/swiftpm-consumer/test.sh` |
| Update the ABI dumps after an intended API change | `./gradlew updateKotlinAbi`, then add a `CHANGELOG.md` entry |
| API reference (HTML in `build/dokka/html/`) | `./gradlew dokkaGenerate` |
| Markdown lint | `npx -y markdownlint-cli2@0.24.0` |
| Spelling (US English) | `typos` |
| Links, repository only | `lychee --offline .` |
| Links, including external | `lychee .` (set `GITHUB_TOKEN` to avoid GitHub rate limits) |
| Workflow lint | `actionlint` |
| libsodium for Android (3 ABIs; needs the catalog's NDK under `$ANDROID_HOME` or `$ANDROID_SDK_ROOT`, and `minisign`) | `./gradlew buildLibsodiumAndroid` |
| libsodium for iOS (`ios-arm64`, `ios-arm64-simulator`; macOS only, uses the selected Xcode) | `./gradlew buildLibsodiumApple` |
| libsodium for the test host (test-only) | `./gradlew buildLibsodiumHostMacos` or `./gradlew buildLibsodiumHostLinux` |
| Download and verify the libsodium tarball only | `./gradlew verifyLibsodium` |
| libsodium JNI shim for one target (`AndroidArm64V8a`, `AndroidArmeabiV7a`, `AndroidX8664`, `MacosArm64`, `LinuxX8664`) | `./gradlew :meshlink-crypto-sodium:buildSodiumJni<Target>` |
| Android shim symbol and size gates (also in `check`) | `./gradlew checkSodiumJniSymbols checkSodiumJniSize` |
| iOS gates: libsodium archive size per slice, and `MeshLinkKit` symbols (macOS only) | `./gradlew checkSodiumAppleSize :meshlink:checkFrameworkSymbols` |
| Thin-adapter list (also in `check`) | `./gradlew checkThinAdapters` |

The libsodium tasks write `meshlink-crypto-sodium/build/libsodium/out/<target>/{include,lib/libsodium.a}`. Prerequisites: `minisign` (`brew install minisign` or `apt-get install minisign`), NDK 30.0.16248370 (`sdkmanager 'ndk;30.0.16248370'`) for Android, Xcode for Apple, and `make` with a C compiler for the hosts. Verified 2026-11-14 on an M1 with Xcode 16.4 and NDK 30.0.16248370 (Android 3 ABIs in about 90 s, iOS and macOS slices about 25 s each), and for Linux x86_64 in an `ubuntu:24.04` container with GCC 13.3.

The `buildSodiumJni<Target>` tasks link `meshlink-crypto-sodium/native/meshlink_sodium_jni.c` with that target's archive into `build/libsodium/jni/<target>/`, exporting only `JNI_OnLoad`. The Android shims are generated `jniLibs`, so the AAR carries `jni/<abi>/libmeshlink_sodium.so`; `testAndroidHostTest` loads the host shim through `java.library.path` and fails on a host other than macOS arm64 or Linux x86_64. The gate tasks write their findings and sizes to `meshlink-crypto-sodium/build/reports/native/` and `meshlink/build/reports/native/`.

### CI

[`build.yml`](.github/workflows/build.yml) runs on every pull request and every push to `main`. No step retries a test. Gradle's caches and wrapper are cached by `gradle/actions/setup-gradle` with the MIT `basic` provider: the key hashes the build files, the version catalog and the wrapper properties, and only `main` writes the cache. Both jobs use the image's JDK 21 and 25 through `org.gradle.java.installations.fromEnv`.

| Job | Runner | Steps | Required on `main` |
|---|---|---|---|
| Gradle check and Dokka (Linux) | `ubuntu-24.04` | Install `minisign` (`apt`) and the catalog's NDK (`sdkmanager`), restore the libsodium cache, `./gradlew buildLibsodiumAndroid buildLibsodiumHostLinux`, `./gradlew check`, `./gradlew dokkaGenerate` | Yes |
| iOS simulator tests and SwiftPM consumer (macOS) | `macos-26`, `DEVELOPER_DIR` = `/Applications/Xcode_26.4.1.app` | `xcodebuild -version`, install `minisign` (Homebrew), restore the libsodium cache, `./gradlew buildLibsodiumApple buildLibsodiumHostMacos`, `./gradlew iosSimulatorArm64Test`, `tools/swiftpm-consumer/test.sh`, `./gradlew checkSodiumAppleSize :meshlink:checkFrameworkSymbols` | Yes |
| Xcode 27 preview (scheduled, not required) | `xcode-27` preview, `DEVELOPER_DIR` = `/Applications/Xcode_27.2.app` | As the macOS job | No; weekly and on manual dispatch |

Measured on PR 12 (2026-11-14), with no Gradle cache, because pull requests only read the cache that `main` writes: Linux 4 min 14 s; macOS 9 min 13 s and 11 min 53 s (about 5.5 min each for `iosSimulatorArm64Test` and `test.sh`). Both stay under ADR 0017's 15 min limit. The basic provider doesn't cache `~/.konan`, so every macOS run downloads the Kotlin/Native LLVM bundle.

`docs.yml` ("Markdown lint, spelling and ADR headers", "Protocol spec", "Links") and `privacy.yml` ("No private identifiers in any branch history") run alongside. All but "Links" are required ([ADR 0021](docs/adr/0021-change-workflow-and-merge-gates.md) decision 6). `.github/actionlint.yaml` declares the `xcode-27` preview label, which actionlint 1.7.12 doesn't know yet.

## Proof matrix

The `{module,target,host,prerequisites,task,proof,limitations}` matrix that `KOTLIN.md` requires, plus the CI job that is the authoritative proof for each row. The modules hold no sources yet, so each row proves the build wiring, not product behavior.

| Module | Target | Host | CI job | Prerequisites | Task | Proof | Limitations |
|---|---|---|---|---|---|---|---|
| all four | Android (host tests) | Linux, macOS | Linux (`ubuntu-24.04`) | JDK 21 and 25, Android SDK platform 37 | `testAndroidHostTest` (via `check`) | Compilation and JVM host-test runtime | No device |
| `:meshlink` | Android (host tests, Robolectric) | Linux, macOS | Linux (`ubuntu-24.04`) | As above; android-all jars resolved by Gradle | `testAndroidHostTest` (via `check`) | Android framework on SDK 26 and 37 with no network at test time | Robolectric shadows, not a real BLE stack (T9) |
| all four | `iosSimulatorArm64` | macOS only | macOS (`macos-26`, Xcode 26.4.1) | Xcode per ADR 0005 | `iosSimulatorArm64Test` | Compilation and simulator test runtime | Simulator, not hardware (T9) |
| all four | `iosArm64` | macOS only | macOS (`macos-26`, Xcode 26.4.1) | Xcode per ADR 0005 | `compileKotlinIosArm64` (in CI, through the XCFramework build in `tools/swiftpm-consumer/test.sh`) | Compilation only | No device run in the build |
| all four | Android (host tests) | Linux, macOS | Linux (`ubuntu-24.04`) | As above | `koverVerify` (via `check`) | 100% line and branch on `commonMain` and `androidMain` | No coverage for Kotlin/Native source sets |
| `:meshlink`, `:meshlink-testing` | Android (JVM) and klib | Linux, macOS | Linux (`ubuntu-24.04`) | As above | `checkKotlinAbi` (via `check`) | Public ABI matches the committed dumps | Experimental KGP feature (E2 hold) |
| `:meshlink`, `:meshlink-testing` | all | Linux, macOS | Linux (`ubuntu-24.04`) | As above | `dokkaGenerate` | API reference builds with no warning; undocumented public API fails | None |
| `:meshlink` | Maven publication | Linux, macOS | None (local only; CI publishing is ticket 65) | As above | `:meshlink:publishToMavenLocal` | Artifacts and a POM with name, description, URL, license, developer and SCM, and project dependencies under `ch.trancee.meshlink` | Its project dependencies don't publish yet, so a consumer can't resolve it (ticket 65) |
| `:meshlink` | `MeshLinkKit` XCFramework (`iosArm64`, `iosSimulatorArm64`) | macOS only | macOS (`macos-26`, Xcode 26.4.1) | Xcode 27.2 locally, 26.4.1 in CI; an iPhone simulator the selected Xcode accepts, picked by the script from `xcodebuild -showdestinations` (verified locally: iOS 18.6, iPhone 16 Pro) | `tools/swiftpm-consumer/test.sh` | A SwiftPM `binaryTarget` links it and the bundle `ch.trancee.meshlink.kit` loads on the simulator | No public API yet to call from Swift (ticket 09) |
| `build-logic` | JVM | Linux, macOS | Linux (`ubuntu-24.04`) | JDK 21 and 25; `git`; `minisign` | `test` (via root `check`) | Gradle TestKit runs of the dependency-direction and changelog checks, and of `verifyLibsodium` (a flipped byte fails the SHA-256 check, another key's signature fails the minisign check, a missing `minisign` fails closed); the native gates on `cc`-built libraries with a seeded exported `crypto_` symbol and an imported `aes_` symbol, and on oversize files; the thin-adapter check with unlisted and stale paths | Fixture builds use a `jvm()` target only; the libsodium fixture is a signed 346-byte tarball, not the release |
| `:meshlink-crypto-sodium` | libsodium static archives: Android arm64-v8a, armeabi-v7a, x86_64 | Linux, macOS | Linux (`ubuntu-24.04`) | `minisign`, NDK 30.0.16248370 | `buildLibsodiumAndroid` | Verified tarball, unmodified upstream scripts, static ELF objects per ABI | None |
| `:meshlink-crypto-sodium` | Android JNI shims (3 ABIs) | Linux, macOS | Linux (`ubuntu-24.04`) | As above | `checkSodiumJniSymbols`, `checkSodiumJniSize` (via `check`) | Each shim exports only `JNI_OnLoad`, has no AES, AEGIS or IPCrypt symbol and is at most 1,000,000 bytes; `build-logic` tests prove each rule fails on a seeded violation | Not loaded on a device (T9) |
| `:meshlink-crypto-sodium` | Android (host tests) with the host shim | Linux x86_64, macOS arm64 | Linux (`ubuntu-24.04`) | As above, plus the host archive | `testAndroidHostTest` (via `check`) | `CryptoProvider` on real libsodium; the derivation vectors of `docs/spec/vectors/derivations.json` (epoch, NodeId, mesh identifier digest, mesh tag) and RFC 8032 TEST 1 | The host shim, not the Android ABIs |
| `:meshlink-crypto-sodium` | `iosSimulatorArm64` | macOS only | macOS (`macos-26`, Xcode 26.4.1) | Xcode per ADR 0005 | `iosSimulatorArm64Test` | The same vector and provider tests through cinterop | Simulator, not hardware (T9) |
| `:meshlink-crypto-sodium`, `:meshlink` | iOS archives and `MeshLinkKit` slices | macOS only | macOS (`macos-26`, Xcode 26.4.1) | Xcode | `checkSodiumAppleSize`, `:meshlink:checkFrameworkSymbols` | Each slice's libsodium archive has at most 1,000,000 bytes of segments; no slice exports a libsodium symbol or touches an AES, AEGIS or IPCrypt symbol | `:meshlink` doesn't call crypto yet, so the slices hold no libsodium code |
| `:meshlink-crypto-sodium` | libsodium static archives: `ios-arm64`, `ios-arm64-simulator` | macOS only | macOS (`macos-26`, Xcode 26.4.1) | `minisign`, Xcode | `buildLibsodiumApple` | Verified tarball; `lipo -info` arm64 with platform iOS or iOS Simulator, minimum 15.0 | None |
| `:meshlink-crypto-sodium` | libsodium host archives (test-only): macOS arm64, Linux x86_64 | macOS / Linux | macOS / Linux job | `minisign`, `make`, a C compiler | `buildLibsodiumHostMacos`, `buildLibsodiumHostLinux` | Verified tarball, static archive for the JVM host | Never published |

Device hosts, recorded for that matrix ([device lab](.scratch/meshlink-v1/issues/06-device-lab-readiness.md)):

- iOS 17 and later: CoreDevice (`xcrun devicectl`).
- iOS 15 and 16: libimobiledevice (`ideviceinstaller`, `idevicedebug`), because `devicectl` lists them by ECID only.
- Android: adb, local or remote.

## Limitations

- CI doesn't run `iosArm64` on a device, the Maven publication, or the Xcode 27 job on pull requests; the Xcode 27 preview job runs weekly and isn't required.
- Maven consumer resolution: only `:meshlink` publishes. `:meshlink-core` and `:meshlink-crypto-sodium` don't apply the publishing plugin yet, and `:meshlink-testing` has no source, so KGP writes no iOS klib for it. The `meshlink` POM names the first two, so a consumer can't resolve it yet ([ticket 65](.scratch/meshlink-0.1.0-build/issues/65-maven-consumer-proof.md)).
- Assembling the XCFramework a second time fails ("No 'swiftinterface' files found") while `:meshlink` has no Swift API: when the release link is up to date, SKIE's directory task re-runs and leaves an empty `MeshLinkKit.swiftmodule` in the framework. Reproduced on `main` at `9f85c7b`. Before re-running `tools/swiftpm-consumer/test.sh`, delete `meshlink/build/bin` and `meshlink/build/XCFrameworks`. CI builds the XCFramework once per job, and `:meshlink:checkFrameworkSymbols` reads the link outputs, so it never re-assembles.
- Xcode 27.2 `dsymutil` prints "could not open … stdlib-per-file-cache" warnings while linking Kotlin/Native debug test binaries ([KT-90461](https://youtrack.jetbrains.com/issue/KT-90461)); the build succeeds.
- Android and iOS radio behavior is proved on hardware only (Constitution T9). The routing simulator results (ADR 0008) are not device evidence.
