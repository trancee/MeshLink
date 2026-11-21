# PROJECT

Verified project profile (required by [`AGENTS.md`](AGENTS.md) and [`KOTLIN.md`](KOTLIN.md)). It records only current, checked state; each decided fact lives in its ADR and is linked, not copied.

## State (2026-11-14)

- **Phase:** build. `main` holds the Gradle scaffold (four modules, `build-logic/`) with its quality gates, Maven publishing of all four modules (`meshlink-core` and `meshlink-crypto-sodium` as dependency-only internal artifacts, ADR 0011) and the `MeshLinkKit` XCFramework. `:meshlink` holds the node lifecycle API: config DSL, `MeshLink` create, start, stop and close, `MeshState`, a persistent installation identity (Android Keystore, iOS Keychain) with `resetIdentity()`, and discovery: a running node advertises, keeps the peers of its mesh as link candidates and opens `Unsecured` links to them with `PLAIN_HELLO`, listing linked peers in `peers`, and exchanges direct `Unsecured` messages over those links with `send`, delivery status and the `messages` flow (milestone M1). On Android, `MeshPlatform.android(context)` runs it on a real Bluetooth LE radio (`androidMain`: advertising, scanning, the GATT server and client, and the platform requirements), and on iPhones `MeshPlatform.ios()` runs it on Core Bluetooth; both are proved on phone pairs ([device evidence](docs/evidence/2026-11-15-radio-tracer/README.md)). `:meshlink-core` holds the internal `CryptoProvider`, the spec's hash derivations (NodeId, mesh identifier digest, epoch, mesh tag), the advertisement codec, the mesh-tag filter, and the capability record, link record and link-frame (`PLAIN_HELLO`, `CLOSE`) codecs with the unsecured link policy, and the `MESSAGE`, container and plaintext codecs with unsecured message building and acceptance. `:meshlink-crypto-sodium` builds static libsodium 1.0.22 for every target from the verified upstream tarball and implements `CryptoProvider` over it, with every primitive of the `Classical` and `PostQuantum` profiles (X25519, Ed25519, ChaCha20-Poly1305, SHA-256, HKDF-SHA-256, ML-KEM-768, X-Wing): a JNI shim on Android and the JVM test hosts, cinterop on iOS. `:meshlink` calls it to create the installation identity.
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
- **Modules** ([ADR 0011](docs/adr/0011-modules-toggles-and-presets.md) decision 2). Every library module applies the `meshlink.kmp-library` convention plugin, which derives the Android `namespace` from the module path; the unpublished lab apps follow it:

  | Module | Android namespace | May depend on |
  |---|---|---|
  | `:meshlink-core` | `ch.trancee.meshlink.core` | nothing |
  | `:meshlink-crypto-sodium` | `ch.trancee.meshlink.crypto.sodium` | `:meshlink-core` |
  | `:meshlink` | `ch.trancee.meshlink` | `:meshlink-core`, `:meshlink-crypto-sodium` |
  | `:meshlink-testing` | `ch.trancee.meshlink.testing` | `:meshlink` |
  | `:labapp-shared` (lab only) | `ch.trancee.labapp.shared` | `:meshlink` |
  | `:labapp-android` (lab only, `meshlink.lab-app`) | `ch.trancee.lab.host` | `:labapp-shared` |

  `:meshlink` and `:meshlink-testing` also apply `meshlink.published-library`: they are the published, documented modules inside the ABI promise ([ADR 0019](docs/adr/0019-release-versioning-and-api-compatibility.md)). The root build applies `meshlink.root`.

  `:meshlink` also applies `meshlink.build-info`, which generates the internal `ch.trancee.meshlink.internal.BuildInfo` object holding the library version, and SKIE. It builds the `MeshLinkKit` XCFramework (task `:meshlink:assembleMeshLinkKitReleaseXCFramework`, output `meshlink/build/XCFrameworks/release/`).
- **Publishing:** `meshlink.published-library` configures vanniktech maven-publish: groupId `ch.trancee.meshlink`, the module name as artifact ID, one version from `meshlink.version` in `gradle.properties` (`0.1.0-SNAPSHOT` until the release PR), a POM under The Unlicense and the Dokka HTML as javadoc JAR. Signing and Maven Central upload are off (tickets 58 and 60). Only `:meshlink` publishes so far; see Limitations.

  `checkDependencyDirection`, wired into `check`, fails on any project dependency outside this allow-list, in every configuration including tests (except the aggregate configuration Kotlin 2.4 creates for SwiftPM lock files), and fails closed on a module with no entry.
- **Gates in `check`** (versions and E2 holds: [ADR 0005](docs/adr/0005-toolchain-and-dependency-baseline.md)):

  | Gate | Task | Fails when |
  |---|---|---|
  | Coverage | `koverVerify` (root) | Merged line or branch coverage is below 100% (see the Kover profile below) |
  | Static analysis | `detekt<SourceSet>SourceSet` and `detektMainAndroid` (type resolution, `commonMain` plus `androidMain`) in every module | Any detekt finding. Config: detekt defaults plus [`config/detekt/detekt.yml`](config/detekt/detekt.yml), which widens the test-code excludes and forbids ambient time and randomness in `commonMain` (`ForbiddenImport`, `ForbiddenMethodCall`; only `RandomSource.kt` may import `kotlin.random`). No baseline; no suppressions except the `checkSuppressions` exception below. `:meshlink-crypto-sodium` logs 9 analysis-only `expect`/`actual` errors and `:meshlink` logs 6 analysis-only `@OptionalExpectation` errors, because `detektMainAndroid` analyzes `commonMain` and `androidMain` as one module ([detekt/detekt#9602](https://github.com/detekt/detekt/issues/9602)); call resolution, which the forbidden-call rule needs, is unaffected |
  | Formatting | `spotlessCheck` (root) | A `.kt` or `.gradle.kts` file differs from ktlint 1.8.0 formatting; fix with `./gradlew spotlessApply` |
  | Public ABI | `checkKotlinAbi` in the published modules | The ABI differs from the committed dumps in `<module>/api/`; update with `./gradlew updateKotlinAbi` |
  | Changelog | `checkChangelog` (root) | An `api/**/*.api` dump changed since the merge base with `origin/main` (override: `-Pmeshlink.changelog.base=<ref>`) without a `CHANGELOG.md` change; fails closed if the base ref is missing |
  | Native symbols | `checkSodiumJniSymbols` in `:meshlink-crypto-sodium` | An Android JNI shim exports a `sodium_`, `crypto_` or `randombytes_` symbol, or exports or imports a symbol matching `aes`, `aegis` or `ipcrypt` ([ADR 0004](docs/adr/0004-crypto-provider-libsodium.md) 2026-11-14 amendment) |
  | Native size | `checkSodiumJniSize` in `:meshlink-crypto-sodium` | An Android JNI shim is over 1,000,000 bytes |
  | Thin adapters | `checkThinAdapters` (root) | [`config/thin-adapters.txt`](config/thin-adapters.txt) doesn't name exactly the Kotlin/Native main source files (`*/src/{ios,apple,native}*Main/**/*.kt`) |
  | Suppressions | `checkSuppressions` (root) | A main source file (`*/src/*Main/**/*.kt`) holds a `@Suppress` or `@file:Suppress` other than `@Suppress("DEPRECATION")` in a file [`config/legacy-platform-apis.txt`](config/legacy-platform-apis.txt) lists, or a listed file holds none (Constitution Q2 exception) |
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
  | `build-logic/` | Included build: the `meshlink.kmp-library`, `meshlink.published-library`, `meshlink.build-info`, `meshlink.robolectric`, `meshlink.root`, `meshlink.dependency-direction`, `meshlink.changelog`, `meshlink.libsodium`, `meshlink.sodium-host-jni`, `meshlink.lab-app` and `meshlink.spec-vectors` convention plugins, the native gate tasks, and their tests |
  | `meshlink-core/`, `meshlink-crypto-sodium/`, `meshlink/`, `meshlink-testing/` | The four library modules; `meshlink/api/` and `meshlink-testing/api/` hold the committed ABI dumps |
  | `labapp/` | The unpublished lab apps: `shared/` (`:labapp-shared`, the scenarios and the `LabKit` framework), `android/` (`:labapp-android`, Compose) and `ios/` (XcodeGen SwiftUI shell); [ADR 0017](docs/adr/0017-testing-architecture.md), 2026-11-15 lab tools amendment |
  | `config/detekt/` | detekt config on top of the defaults |
  | `config/thin-adapters.txt` | The Kotlin/Native main source files, all thin adapters (Constitution T3) |
  | `CHANGELOG.md` | Keep a Changelog history ([ADR 0019](docs/adr/0019-release-versioning-and-api-compatibility.md) decision 6) |
  | `gradle/` | Version catalog, wrapper and daemon JDK declaration |
  | `docs/` | Diátaxis docs, ADRs and research ([index](docs/README.md)) |
  | `tools/` | Spec vectors and checks, privacy scan, ADR check, lab tools (`tools/lab/`, [README](tools/lab/README.md)) |
  | `tools/swiftpm-consumer/` | SwiftPM package that consumes the built XCFramework through a local `binaryTarget`, and its `test.sh` |
  | `tools/maven-consumer/` | `test.sh`: publishes every module into a temporary Maven repository, checks the POMs and resolves `meshlink` from a generated KMP consumer |
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
| Gherkin direct-message scenarios on the Android host | `./gradlew :meshlink-testing:testAndroidHostTest --tests '*DirectMessageFeatureTest*'` |
| All Gherkin scenarios on the Android host | `./gradlew :meshlink-testing:testAndroidHostTest --tests '*FeatureTest*'` |
| All Gherkin scenarios on the iOS simulator | `./gradlew :meshlink-testing:iosSimulatorArm64Test --tests '*FeatureTest*'` |
| `build-logic` tests only | `./gradlew -p build-logic test` |
| Coverage report (HTML; `koverLog` prints the totals) | `./gradlew koverHtmlReport` |
| Format all Kotlin and Gradle Kotlin files | `./gradlew spotlessApply` |
| Publish every module into Maven local (`-Dmaven.repo.local=<dir>` for a throwaway repository) | `./gradlew publishToMavenLocal` |
| Maven consumer of the published artifacts (macOS only: the iOS klibs; needs the pinned NDK and `buildLibsodiumAndroid`; never writes `~/.m2`) | `tools/maven-consumer/test.sh` |
| SwiftPM consumer of the XCFramework on an iOS Simulator (macOS only; set `DEVELOPER_DIR` to the Xcode of ADR 0005; `MESHLINK_SIMULATOR_DESTINATION` overrides the destination) | `tools/swiftpm-consumer/test.sh` |
| Update the ABI dumps after an intended API change | `./gradlew updateKotlinAbi`, then add a `CHANGELOG.md` entry |
| API reference (HTML in `build/dokka/html/`) | `./gradlew dokkaGenerate` |
| Markdown lint | `npx -y markdownlint-cli2@0.24.0` |
| Spelling (US English) | `typos` |
| Links, repository only | `lychee --offline .` |
| Links, including external | `lychee .` (set `GITHUB_TOKEN` to avoid GitHub rate limits) |
| Workflow lint | `actionlint` |
| CI gate unit tests | `python3 -m unittest discover -s tools/ci/tests` |
| Tier 2 vectors: the Rust reference's tests, including the committed-file staleness check (needs rustup; installs Rust 1.100.0 from `tools/reference/rust-toolchain.toml`) | `cargo test --manifest-path tools/reference/Cargo.toml` |
| Regenerate the tier 2 vector files and schemas | `cargo run --manifest-path tools/reference/Cargo.toml` |
| libsodium for Android (3 ABIs; needs the catalog's NDK under `$ANDROID_HOME` or `$ANDROID_SDK_ROOT`, and `minisign`) | `./gradlew buildLibsodiumAndroid` |
| libsodium for iOS (`ios-arm64`, `ios-arm64-simulator`; macOS only, uses the selected Xcode) | `./gradlew buildLibsodiumApple` |
| libsodium for the test host (test-only) | `./gradlew buildLibsodiumHostMacos` or `./gradlew buildLibsodiumHostLinux` |
| Download and verify the libsodium tarball only | `./gradlew verifyLibsodium` |
| libsodium JNI shim for one target (`AndroidArm64V8a`, `AndroidArmeabiV7a`, `AndroidX8664`, `MacosArm64`, `LinuxX8664`) | `./gradlew :meshlink-crypto-sodium:buildSodiumJni<Target>` |
| Android shim symbol and size gates (also in `check`) | `./gradlew checkSodiumJniSymbols checkSodiumJniSize` |
| iOS gates: libsodium archive size per slice, and `MeshLinkKit` symbols (macOS only) | `./gradlew checkSodiumAppleSize :meshlink:checkFrameworkSymbols` |
| Thin-adapter list (also in `check`) | `./gradlew checkThinAdapters` |
| Fuzz every decoder for 30 s each (not in `check`; `-Pfuzz.seconds=N` per target); crash inputs land in `meshlink-core/build/fuzz/crashes/` | `./gradlew :meshlink-core:fuzz` |
| Reseed and minimize the committed fuzz corpus from the vector files | `python3 tools/fuzz/seed_corpus.py build/fuzz-seeds && ./gradlew :meshlink-core:fuzz -Pfuzz.seeds=build/fuzz-seeds` |
| Lab tools' unit tests (also in `docs.yml`) | `python3 -m unittest discover -s tools/lab/tests` |
| One device scenario on two lab phones, from a dedicated clean worktree; `--profile` is `unsecured` (default), `classical` (needs ticket 24) or `post-quantum` (needs ticket 25) | `python3 tools/lab/lab.py run --scenario direct-message --devices <alias>,<alias> --profile unsecured` |
| Turn runs into one T9 evidence record (repeat `--run`; several runs need `--name`) | `python3 tools/lab/lab.py evidence --run build/lab/<run> [--name <name>]` |

The lab commands need the local configuration in `~/.config/meshlink-lab/` and the host tools listed in [`tools/lab/README.md`](tools/lab/README.md); `lab.py` refuses the main checkout and a dirty tree.

The Gherkin scenarios in `meshlink-testing/src/commonTest/resources/features/` generate target tests for the Android host and iOS simulator. Step definitions use `VirtualAir`; these tests are not physical-radio evidence.

The libsodium tasks write `meshlink-crypto-sodium/build/libsodium/out/<target>/{include,lib/libsodium.a}`. Prerequisites: `minisign` (`brew install minisign` or `apt-get install minisign`), NDK 30.0.16248370 (`sdkmanager 'ndk;30.0.16248370'`) for Android, Xcode for Apple, and `make` with a C compiler for the hosts. Verified 2026-11-14 on an M1 with Xcode 16.4 and NDK 30.0.16248370 (Android 3 ABIs in about 90 s, iOS and macOS slices about 25 s each), and for Linux x86_64 in an `ubuntu:24.04` container with GCC 13.3.

The `buildSodiumJni<Target>` tasks link `meshlink-crypto-sodium/native/meshlink_sodium_jni.c` with that target's archive into `build/libsodium/jni/<target>/`, exporting only `JNI_OnLoad`. The Android shims are generated `jniLibs`, so the AAR carries `jni/<abi>/libmeshlink_sodium.so`; `testAndroidHostTest` loads the host shim through `java.library.path` and fails on a host other than macOS arm64 or Linux x86_64. The gate tasks write their findings and sizes to `meshlink-crypto-sodium/build/reports/native/` and `meshlink/build/reports/native/`.

### CI

[`build.yml`](.github/workflows/build.yml) runs on every pull request and every push to `main`. No step retries a test. Gradle's caches and wrapper are cached by `gradle/actions/setup-gradle` with the MIT `basic` provider: the key hashes the build files, the version catalog and the wrapper properties, and only `main` writes the cache. The Linux and Apple build phases use the runner's JDK 21 and 25 through `org.gradle.java.installations.fromEnv`. The Apple phases run on the self-hosted Mac mini for pushes, same-repository pull requests and manual dispatches, one after another; a fork's pull request runs them in parallel on GitHub's `macos-26` image and never reaches the Mac mini ([ADR 0017](docs/adr/0017-testing-architecture.md), self-hosted runner amendment). The existing required macOS check fails unless all phases succeed.

| Job | Runner | Steps | Required on `main` |
|---|---|---|---|
| Gradle check and Dokka (Linux) | `ubuntu-24.04` | Install `minisign` (`apt`) and the catalog's NDK (`sdkmanager`), restore the libsodium cache, `./gradlew buildLibsodiumAndroid buildLibsodiumHostLinux`, `./gradlew check`, `./gradlew dokkaGenerate` | Yes |
| Apple (simulator) | self-hosted Mac mini (`self-hosted`, `macOS`, `ARM64`), `DEVELOPER_DIR` = `/Applications/Xcode-27.2.0.app`; fork PRs: `macos-26`, `/Applications/Xcode_26.4.1.app` | Restore caches, build Apple and host libsodium, run `./gradlew iosSimulatorArm64Test --profile` and upload its Gradle profile and test XML for seven days | Via the required macOS check |
| Apple (swiftpm) | self-hosted Mac mini (`self-hosted`, `macOS`, `ARM64`), `DEVELOPER_DIR` = `/Applications/Xcode-27.2.0.app`; fork PRs: `macos-26`, `/Applications/Xcode_26.4.1.app` | Restore caches, build Apple and host libsodium, run `tools/swiftpm-consumer/test.sh` and `./gradlew checkKotlinAbi checkSodiumAppleSize :meshlink:checkFrameworkSymbols`; on consumer failure, summarize simulator inventory and probe a destination without exposing identifiers | Via the required macOS check |
| Apple (maven) | self-hosted Mac mini (`self-hosted`, `macOS`, `ARM64`), `DEVELOPER_DIR` = `/Applications/Xcode-27.2.0.app`; fork PRs: `macos-26`, `/Applications/Xcode_26.4.1.app` | Restore caches, build Apple and host libsodium, install the pinned NDK, build Android libsodium and run `tools/maven-consumer/test.sh` | Via the required macOS check |
| iOS simulator tests and SwiftPM consumer (macOS) | `ubuntu-24.04` | Require success of all Apple phases | Yes |
| Reference vectors (`reference-vectors.yml`, scheduled, not required) | `ubuntu-24.04` | `rustup toolchain install`, `cargo clippy --locked --all-targets -- -D warnings`, `cargo test --locked`, `cargo run --locked`, diff `docs/spec/vectors/` | No; weekly and on manual dispatch |
| Fuzz (`fuzz.yml`, scheduled, not required) | `ubuntu-24.04` | `./gradlew :meshlink-core:fuzz` with 300 s per target; 15 s on a pull request that changes the fuzz targets, corpus, `meshlink-core/build.gradle.kts`, `tools/fuzz/` or the workflow; uploads `meshlink-core/build/fuzz/crashes/` on failure | No; weekly, on manual dispatch and on those pull requests |

The Apple phases restore `~/.konan/dependencies` by runner OS, architecture, Xcode, Kotlin version and phase, falling back to another phase with the same toolchain; only `main` saves it. Libsodium cache restore falls back across version-catalog changes only when the pin, build script and toolchain match; the per-target stamp still checks the tarball, script and target toolchain before reuse. The SwiftPM script prints separate framework-build and simulator-test times.

`docs.yml` ("Markdown lint, spelling and ADR headers", "Protocol spec", "Links") and `privacy.yml` ("No private identifiers in any branch history") run alongside. All but "Links" are required ([ADR 0021](docs/adr/0021-change-workflow-and-merge-gates.md) decision 6).

## Proof matrix

The `{module,target,host,prerequisites,task,proof,limitations}` matrix that `KOTLIN.md` requires, plus the CI job that is the authoritative proof for each row. The modules hold no sources yet, so each row proves the build wiring, not product behavior.

| Module | Target | Host | CI job | Prerequisites | Task | Proof | Limitations |
|---|---|---|---|---|---|---|---|
| all four | Android (host tests) | Linux, macOS | Linux (`ubuntu-24.04`) | JDK 21 and 25, Android SDK platform 37 | `testAndroidHostTest` (via `check`) | Compilation and JVM host-test runtime | No device |
| `:meshlink` | Android (host tests, Robolectric) | Linux, macOS | Linux (`ubuntu-24.04`) | As above; android-all jars resolved by Gradle | `testAndroidHostTest` (via `check`) | Android framework on SDK 26 and 37 with no network at test time; the Android radio (`AndroidRadio*Test`, `GattExchangeTest`): advertising and scan parameters, the GATT service, server open retries, central connect settings per SDK, MTU, discovery, capability read, subscribe, chunked writes and notifications, busy retries, disconnects and Bluetooth off, the permission table per API level, requirements refreshed by the Bluetooth broadcast and on activity resume, and the cached `MeshPlatform.android` | Robolectric's shadows plus MeshLink's own (`RadioShadows.kt`), not a real BLE stack (T9); API 29 and 30 permissions tested as a table only; real radios: [device evidence](docs/evidence/2026-11-15-radio-tracer/README.md) |
| `:meshlink` | Android (host tests) and `iosSimulatorArm64` | Linux, macOS (iOS on macOS only) | Linux (`ubuntu-24.04`) via `check`; macOS (self-hosted, Xcode 27.2; forks `macos-26`, Xcode 26.4.1) | As above | `testAndroidHostTest` (via `check`), `iosSimulatorArm64Test` | The iOS radio logic in `commonMain` (`AppleRadio*Test`, `ApplePlatformPortTest`) against `FakeCoreBluetooth`: requirements from both manager states and the authorization, the managers created on first collection only, advertising and scanning, the GATT service published once and again after the peripheral manager stops, the central's connect, discovery, capability read, subscribe and paced writes, the 30 s connect timeout, the peripheral's links by subscription, control-read reuse and paced notifications, disconnects and Bluetooth off | A fake of the internal Core Bluetooth port, not Core Bluetooth (T9); the calls in `iosArm64Main` are compiled only, listed in `config/thin-adapters.txt`, and proved on iPhones: [device evidence](docs/evidence/2026-11-15-radio-tracer/README.md) |
| `:meshlink` | Android (host tests) and `iosSimulatorArm64` | Linux, macOS (iOS on macOS only) | Linux (`ubuntu-24.04`) via `check`; macOS (self-hosted, Xcode 27.2; forks `macos-26`, Xcode 26.4.1) | As above | `testAndroidHostTest` (via `check`), `iosSimulatorArm64Test` | The persistent installation identity: `PersistentIdentityStore` and `KeychainSlot` in `commonMain` (create only when nothing is stored, reload the same seed, never replace an unreadable one, wipe what was read); `KeystoreSlot` under Robolectric on SDK 26 and 37 against a fake `AndroidKeyStore` provider (the AES-256-GCM key spec, StrongBox and its fallback, the sealed blob in `noBackupFilesDir`, and T10 cases: corrupt, truncated or other-version blobs, a missing key, an unreachable Keystore, an unreadable file, failed key generation, writes and deletes); `MeshLinkIdentityTest` on `VirtualAir` (`Blocked(IdentityUnavailable)`, retry on `start()`, `resetIdentity()`); `IosKeychainTest` on the simulator (item attributes, the copy query, the added seed zeroed afterwards, and each call well formed: `errSecNotAvailable`, not `errSecParam`) | A fake JCA provider, not Android Keystore; the simulator test host has no Keychain, so storing and reading a real item is proved on phones in ticket 17 |
| `:labapp-shared`, `:labapp-android` | Android (host tests), `iosSimulatorArm64`, `iosArm64` | Linux, macOS (iOS on macOS only) | Linux (`ubuntu-24.04`) via `check`; macOS (self-hosted, Xcode 27.2; forks `macos-26`, Xcode 26.4.1) | As above | `check` (host tests, detekt, Spotless), `iosSimulatorArm64Test` | Launch-argument parsing, the JSON Lines recorder on a test time source and the self-checking payload (`LabArgsTest`, `RecorderTest`, `PayloadTest`); the Android app builds | Not in Kover, ABI or Dokka; the scenarios and the iOS app run only on phones (`tools/lab/lab.py`, T9) |
| all four | `iosSimulatorArm64` | macOS only | macOS (self-hosted, Xcode 27.2; forks `macos-26`, Xcode 26.4.1) | Xcode per ADR 0005 | `iosSimulatorArm64Test` | Compilation and simulator test runtime | Simulator, not hardware (T9) |
| all four | `iosArm64` | macOS only | macOS (self-hosted, Xcode 27.2; forks `macos-26`, Xcode 26.4.1) | Xcode per ADR 0005 | `compileKotlinIosArm64` (in CI, through the XCFramework build in `tools/swiftpm-consumer/test.sh`) | Compilation only | No device run in the build |
| all four | Android (host tests) | Linux, macOS | Linux (`ubuntu-24.04`) | As above | `koverVerify` (via `check`) | 100% line and branch on `commonMain` and `androidMain` | No coverage for Kotlin/Native source sets |
| `:meshlink`, `:meshlink-testing` | Android (JVM) and klib | Linux (both infer their iOS klib dumps), macOS | Linux (`ubuntu-24.04`) via `check`; macOS (self-hosted; forks `macos-26`) by name for the klib dump | As above | `checkKotlinAbi` | Public ABI matches the committed dumps | Experimental KGP feature (E2 hold) |
| `:meshlink`, `:meshlink-testing` | all | Linux, macOS | Linux (`ubuntu-24.04`) | As above | `dokkaGenerate` | API reference builds with no warning; undocumented public API fails | `:meshlink`'s `iosAdaptersMain`, `iosArm64Main` and `iosSimulatorArm64Main` are suppressed: they hold only internal thin adapters. Dokka 2.3.0 warns on links in the KDoc that `QueueDispatcher` inherits from `CoroutineDispatcher`, and on Linux it can't resolve the cinterop types in `IosKeychain`; Dokka fails unless the source sets that depend on a suppressed one are suppressed too |
| all four | Maven publication | macOS (the iOS klibs need it) | macOS (self-hosted; forks `macos-26`) | Kotlin and AGP from the catalog; Android `minSdk` 26 | `tools/maven-consumer/test.sh` | Every POM has name, description, URL, license (The Unlicense), developer and SCM at one version; a KMP consumer depending only on `ch.trancee.meshlink:meshlink` resolves `meshlink-core` and `meshlink-crypto-sodium` on `androidRuntimeClasspath`, `iosArm64CompileKlibraries` and `iosSimulatorArm64CompileKlibraries` | Resolution only: the consumer doesn't compile or run code against the artifacts; nothing is published to Maven Central yet |
| `:meshlink` | `MeshLinkKit` XCFramework (`iosArm64`, `iosSimulatorArm64`) | macOS only | macOS (self-hosted, Xcode 27.2; forks `macos-26`, Xcode 26.4.1) | Xcode 27.2 locally and in CI, 26.4.1 for fork pull requests; an iPhone simulator the selected Xcode accepts, picked by the script from `xcodebuild -showdestinations` (verified locally: iOS 18.6, iPhone 16 Pro) | `tools/swiftpm-consumer/test.sh` | A SwiftPM `binaryTarget` links it, the bundle `ch.trancee.meshlink.kit` loads on the simulator, and Swift drives the lifecycle API | Swift creates and starts a node, which reports `Blocked(Unsupported)` on the simulator; Swift sees `state` as a plain Kotlin `StateFlow` (ticket 55) |
| `build-logic` | JVM | Linux, macOS | Linux (`ubuntu-24.04`) | JDK 21 and 25; `git`; `minisign` | `test` (via root `check`) | Gradle TestKit runs of the dependency-direction and changelog checks, and of `verifyLibsodium` (a flipped byte fails the SHA-256 check, another key's signature fails the minisign check, a missing `minisign` fails closed); the native gates on `cc`-built libraries with a seeded exported `crypto_` symbol and an imported `aes_` symbol, and on oversize files; the thin-adapter check with unlisted and stale paths | Fixture builds use a `jvm()` target only; the libsodium fixture is a signed 346-byte tarball, not the release |
| `:meshlink-crypto-sodium` | libsodium static archives: Android arm64-v8a, armeabi-v7a, x86_64 | Linux, macOS | Linux (`ubuntu-24.04`) | `minisign`, NDK 30.0.16248370 | `buildLibsodiumAndroid` | Verified tarball, unmodified upstream scripts, static ELF objects per ABI | None |
| `:meshlink-crypto-sodium` | Android JNI shims (3 ABIs) | Linux, macOS | Linux (`ubuntu-24.04`) | As above | `checkSodiumJniSymbols`, `checkSodiumJniSize` (via `check`) | Each shim exports only `JNI_OnLoad`, has no AES, AEGIS or IPCrypt symbol and is at most 1,000,000 bytes; `build-logic` tests prove each rule fails on a seeded violation | Not loaded on a device (T9) |
| `:meshlink-crypto-sodium` | Android (host tests) with the host shim | Linux x86_64, macOS arm64 | Linux (`ubuntu-24.04`) | As above, plus the host archive | `testAndroidHostTest` (via `check`) | `CryptoProvider` on real libsodium; the vector runner over every `docs/spec/vectors/*.json` file (derivations, layouts with a mutation test per valid layout: every prefix, over-long inputs and 10,000 seeded mutations, with no exception), Wycheproof X25519, Ed25519, ChaCha20-Poly1305, HKDF-SHA-256 and ML-KEM-768, and hpke-pq X-Wing, with pending keys named by ticket; RFC 8032 TEST 1; unsecured message building and every acceptance check; the mesh-tag filter (epochs e ± 1 kept, e ± 2, another mesh ID or profile and the node's own payload dropped) | The host shim, not the Android ABIs |
| `:meshlink-testing` | Android (host tests) and `iosSimulatorArm64` | Linux, macOS (iOS on macOS only) | Linux (`ubuntu-24.04`) via `check`; macOS (self-hosted, Xcode 27.2; forks `macos-26`, Xcode 26.4.1) | As above | `testAndroidHostTest` (via `check`), `iosSimulatorArm64Test` | Multi-node tests on `VirtualAir` in virtual time: the node lifecycle (state order, `Blocked` and its clearing, one node per platform radio, installation identity), discovery (two nodes of one mesh find each other within one air latency and ignore a third mesh; discovery stops when stopped or blocked; radio refusals recorded), `Unsecured` links (hello, version selection, wrong mesh, protocol errors, the 10 s timeout, a 20-seed simultaneous-connect race collapsing to one link, `stop()` closing with `SHUTTING_DOWN`), direct messages (the M1 exit test: two nodes with real libsodium hashing exchange a message, `Queued` then `Sent`; rejections, `Held`, `Expired`, `Failed(Stopped)`, one collector at a time, queued messages moving to the kept link of a duplicate pair or the next link after a failed write), sightings, a 2 KB stream in order at ATT MTU 23 and 247, disconnects, failures as values, and one seed replaying one event log | Latency, MTU and disconnects only: no loss, link caps or advertising and scan windows yet; not a real BLE stack (T9) |
| `:meshlink-core` | Android (host tests) and `iosSimulatorArm64` | Linux, macOS (iOS on macOS only) | Linux (`ubuntu-24.04`) via `check`; macOS (self-hosted, Xcode 27.2; forks `macos-26`, Xcode 26.4.1) | As above | `testAndroidHostTest` (via `check`), `iosSimulatorArm64Test` | Focused codec cases for the advertisement, capability record, link record, `PLAIN_HELLO` and `CLOSE`, the unsecured `MESSAGE` frame and inner plaintexts, and the service-UUID byte order (D-12); their vectors run in `:meshlink-crypto-sodium` | Codec only; the mesh-tag filter runs on real libsodium in `:meshlink-crypto-sodium` |
| `:meshlink-crypto-sodium` | `iosSimulatorArm64` | macOS only | macOS (self-hosted, Xcode 27.2; forks `macos-26`, Xcode 26.4.1) | Xcode per ADR 0005 | `iosSimulatorArm64Test` | The same vector runner and provider tests through cinterop | Simulator, not hardware (T9) |
| `:meshlink-crypto-sodium`, `:meshlink` | iOS archives and `MeshLinkKit` slices | macOS only | macOS (self-hosted, Xcode 27.2; forks `macos-26`, Xcode 26.4.1) | Xcode | `checkSodiumAppleSize`, `:meshlink:checkFrameworkSymbols` | Each slice's libsodium archive has at most 1,000,000 bytes of segments; no slice exports a libsodium symbol or touches an AES, AEGIS or IPCrypt symbol | None |
| `:meshlink-crypto-sodium` | libsodium static archives: `ios-arm64`, `ios-arm64-simulator` | macOS only | macOS (self-hosted, Xcode 27.2; forks `macos-26`, Xcode 26.4.1) | `minisign`, Xcode | `buildLibsodiumApple` | Verified tarball; `lipo -info` arm64 with platform iOS or iOS Simulator, minimum 15.0 | None |
| `:meshlink-crypto-sodium` | libsodium host archives (test-only): macOS arm64, Linux x86_64 | macOS / Linux | macOS / Linux job | `minisign`, `make`, a C compiler | `buildLibsodiumHostMacos`, `buildLibsodiumHostLinux` | Verified tarball, static archive for the JVM host | Never published |

Device hosts, recorded for that matrix ([device lab](.scratch/meshlink-v1/issues/06-device-lab-readiness.md)):

- iOS 17 and later: CoreDevice (`xcrun devicectl`).
- iOS 15 and 16: libimobiledevice (`ideviceinstaller`, `idevicedebug`), because `devicectl` lists them by ECID only.
- Android: adb, local or remote.

## Limitations

- CI doesn't run `iosArm64` on a device, publish to Maven Central, or run the Xcode 27 job on pull requests; the Xcode 27 preview job runs weekly and isn't required.
- Lifecycle API gaps until their tickets: `start()` in `Unsecured` emits no warning diagnostic yet (ticket 54); a running node keeps one advertisement until it stops, with no rotation each epoch (ticket 27), and a radio that refuses to advertise or scan, or fails later, ends discovery until the next start, recorded only internally (tickets 32 and 54); SKIE exports `state` to Swift as a plain Kotlin `StateFlow`, so Swift reads `state.value` (ticket 55); links are `Unsecured` GATT links only, and `Peer` has no `proximity` yet (ticket 32).
- Messaging gaps until their tickets: every message, direct or not, goes out with `hop_limit` 1 over a direct link only (ticket 37); `send` has no direct-only option yet (ticket 47); a payload above 4,096 bytes is `TooLarge` until large messages (ticket 50); without receipts a `Sent` message ends `Expired` (ticket 47). A write that fails stalls the rest of that link's queue until the link ends; the messages then move to the peer's next link. `QueueFull` may let the queue exceed 1 MiB by one message.
- Android radio gaps until their tickets: no connect retries on status 133 or 147 (ticket 29), no L2CAP CoC (ticket 31), no PHY requests, scan restarts, screen-off filters or scan-start budget (ticket 32). GATT requests have no timeout, so a callback the stack never sends holds that link's queue until the link closes. Location services being off on API 26 to 30 isn't reported as a requirement. Phones proved one direct message both ways over GATT, each phone central and peripheral (Android 9 and 17 against each other and against iOS 27; [device evidence](docs/evidence/2026-11-15-radio-tracer/README.md)). Android 8.0 and 8.1 truncate the advertised 32-bit MESH UUID (an AOSP bug fixed in 9), so the P10 lite (Android 8.0) links as a central but no phone finds it as a peripheral; it still advertises until ticket 66.
- iOS radio gaps until their tickets: no background advertising or scanning rules and no state restoration (ticket 32), no 3-link cap for incoming links (ticket 29), no L2CAP CoC (ticket 31). Only the connect has a timeout (30 s); a discovery, read or subscribe callback that never arrives holds that link until it closes. A peripheral-side link that MeshLink closes stays connected until the central leaves, because Core Bluetooth can't disconnect a central. The simulator build reports `Blocked(Unsupported)`. iPhones on iOS 15.8 and 27 proved one direct message both ways, with each other and with Android 9 ([device evidence](docs/evidence/2026-11-15-radio-tracer/README.md)).
- Installation identity: on the iOS simulator it lives in memory, so each process gets a new NodeId. The immutable `CFData` that `SecItemCopyMatching` returns can't be zeroed. Proof so far is a fake Keystore provider and a simulator without a Keychain; on phones both stores kept the NodeId across app relaunches ([device evidence](docs/evidence/2026-11-15-radio-tracer/README.md)).
- Xcode 27.2 `dsymutil` prints "could not open … stdlib-per-file-cache" warnings while linking Kotlin/Native debug test binaries ([KT-90461](https://youtrack.jetbrains.com/issue/KT-90461)); the build succeeds.
- Android and iOS radio behavior is proved on hardware only (Constitution T9). The routing simulator results (ADR 0008) are not device evidence.
