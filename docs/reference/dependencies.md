# Dependency register

Every dependency and tool MeshLink uses, with its Constitution E3 rationale. The decision behind this baseline is [ADR 0005](../adr/0005-toolchain-and-dependency-baseline.md).

Rules:

- `gradle/libs.versions.toml` is the only source of versions; this page never overrides it. The `Catalog` column names each row's catalog entries: a library alias, `plugin:` and a plugin alias, or `version:` and a version key no alias uses. `./gradlew checkDependencyRegister`, part of `check`, fails when a catalog entry has no row or two, a row names an entry the catalog lacks, or a row's Version leaves out the catalog version.
- Native and Rust entries have no catalog entry, so their pins live in the build scripts and `tools/reference/`, and they are listed here by hand.
- Adding or removing a dependency needs user approval and a row here in the same change.

Scope legend:

- `runtime`: ships inside a published artifact.
- `build`: Gradle or compiler tooling.
- `test`: test sources only.
- `reference`: test-only Rust, never published.

Facts verified 2026-11-01.

## Runtime (ships in artifacts)

| Dependency | Version | Catalog | Scope | Need | Maintenance | License | Security | Size | Transitives | Why stdlib or existing fails |
|---|---|---|---|---|---|---|---|---|---|---|
| Kotlin stdlib | 2.4.21 | none | runtime | Language runtime | JetBrains, stable | Apache-2.0 | JetBrains security process | Platform-provided per target | None | Required by Kotlin |
| kotlinx-coroutines-core | 1.11.0 | `kotlinx-coroutines-core` | runtime | Structured concurrency, `Flow` for the public API and bearer I/O | JetBrains, stable | Apache-2.0 | JetBrains security process | Shared with most KMP host apps | atomicfu (compile-time) | The stdlib has no coroutine library |
| libsodium | 1.0.22 | none | runtime (native, static, hidden symbols) | X25519, Ed25519, ChaCha20-Poly1305, SHA-256/HKDF, ML-KEM-768, X-Wing | See ADR 0004 | ISC | ADR 0004; pinned once in `meshlink-crypto-sodium/libsodium.properties` (sha256 `adbdd8f1…be3349`, upstream minisign key); `verifyLibsodium` checks both before extraction, locally and in CI | Static archive at `-Os`, minimal: arm64-v8a 539 KB, armeabi-v7a 551 KB, x86_64 651 KB, `ios-arm64` 427 KB stripped. Linked into a shared object or app with every MeshLink primitive: arm64-v8a 189 KB, armeabi-v7a 302 KB, x86_64 284 KB, iOS 150 KB (ADR 0011, 2026-11-14 amendment). The shipped JNI shim, binding every primitive: arm64-v8a 201,672 B, armeabi-v7a 311,888 B, x86_64 295,520 B (ADR 0004, 2026-11-15 amendment) | None | ADR 0004 |

## Build tooling

| Dependency | Version | Catalog | Scope | Need | Maintenance | License | Security | Size | Transitives | Why stdlib or existing fails |
|---|---|---|---|---|---|---|---|---|---|---|
| Gradle | 9.7.1 | none | build | Build system that KMP requires; its bundled `kotlin-dsl` plugin and TestKit build and test `build-logic/` | Gradle Inc., stable | Apache-2.0 | Wrapper checksum validated | n/a | n/a | Required by KGP |
| Kotlin Gradle plugin | 2.4.21 | `kotlin-gradle-plugin` | build | KMP compilation, XCFramework, built-in ABI validation (E2 hold: Experimental, until it becomes stable) | JetBrains, stable | Apache-2.0 | n/a | n/a | n/a | Required |
| AGP + `com.android.kotlin.multiplatform.library` | 9.3.3 | `android-gradle-plugin` | build | Android library target in KMP | Google, stable | Apache-2.0 | n/a | n/a | n/a | Required for the Android target |
| cucumber-kmp Gradle plugin | 0.2.1 | `plugin:cucumber-kmp` | build | Generates common acceptance scenarios as target tests | Independent project; early 0.x API (user-approved hold until a stable API is available) | MIT | n/a | n/a | cucumber-kmp-core, KotlinPoet | Existing tools do not generate tests from Gherkin for both Android host and iOS |
| Android NDK | 30.0.16248370 (`android-ndk` in the version catalog) | `version:android-ndk` | build | Builds libsodium and the JNI shim | Google, stable | Apache-2.0 and others | n/a | n/a | n/a | Native crypto needs it (ADR 0004) |
| SKIE | 0.10.16 | `skie-gradle-plugin` | build (compiler plugin) | Swift-friendly suspend, Flow, sealed and enum interop | Touchlab, releases follow Kotlin; 0.10.16 adds 2.4.21 | Apache-2.0 | Analytics upload disabled; `produceDistributableFramework()` (Swift library evolution, required for an XCFramework) | Adds Swift glue to the framework | None at runtime | Plain Objective-C export loses suspend, Flow and sealed types |
| Kover | 0.9.11 | `kover-gradle-plugin` | build | T3 coverage for common and Android | JetBrains; Beta (E2 hold until a stable release) | Apache-2.0 | n/a | n/a | n/a | No built-in coverage tool |
| Spotless + ktlint | 8.12.0 + 1.8.0 | `spotless-gradle-plugin`, `version:ktlint` | build | Formatting check (`spotlessCheck`) | DiffPlug and Pinterest, active | Apache-2.0 / MIT | n/a | n/a | n/a | No built-in formatter gate |
| detekt | 2.0.0-alpha.6 | `detekt-gradle-plugin` | build | Static analysis | Active; alpha (E2 hold until 2.0 stable) | Apache-2.0 | n/a | n/a | n/a | 1.23.8 is built against Kotlin 2.0.21; ktlint is formatting only |
| Dokka | 2.3.0 | `dokka-gradle-plugin` | build | API reference docs | JetBrains, stable | Apache-2.0 | n/a | n/a | n/a | Required for generated API docs |
| vanniktech maven-publish | 0.37.0 | `maven-publish-gradle-plugin` | build | Maven Central publishing for KMP; applied as the `.base` plugin by `meshlink.published-library` | Active | Apache-2.0 | Signing configured at release | n/a | n/a | Plain `maven-publish` lacks Central Portal upload and KMP defaults |
| minisign | 0.11 or newer (CI: Ubuntu 24.04 `apt` 0.11, Homebrew 0.12) | none | build (host tool) | Verifies the libsodium tarball's prehashed Ed25519 signature in `verifyLibsodium` | Frank Denis (libsodium's author), stable | ISC | Upstream signs libsodium releases with it | n/a | n/a | The JDK has Ed25519 but no BLAKE2b, which prehashed signatures need; a hand-written BLAKE2b would be more crypto code to prove |
| JDK toolchain | 21 | `version:jdk-toolchain` | build | Compiles Kotlin/JVM and `build-logic/`, and runs the host tests, whether Gradle runs on JDK 21 or 25 (ADR 0005) | OpenJDK, LTS | GPL-2.0 with Classpath Exception | n/a | n/a | n/a | KGP and AGP need a JDK; 21 is the LTS both support |
| Android SDK platform | compile 37.2, min 26 | `version:android-compileSdk`, `version:android-minSdk` | build | `compileSdk` 37.2 compiles against the Android 17 APIs and their second minor release, which Compose `ui` 1.13 requires; `minSdk` 26 is the oldest Android MeshLink supports (ADR 0005) | Google, stable | Android SDK License | n/a | n/a | n/a | Required by AGP |
| Xcode | 27.2 local, 26.4.1 CI | none | build (host) | iOS SDK and the Swift consumer | Apple | Apple EULA | n/a | n/a | n/a | Required for Apple targets; versions per ADR 0005 v4 |

## Security floors (build tooling transitives)

The lowest version any configuration may resolve for a module that build tooling pulls in transitively; never a direct dependency. A lower request is raised and a higher one is kept. The catalog's `floor-*` entries are the source; the decision and upkeep rule are in [ADR 0005](../adr/0005-toolchain-and-dependency-baseline.md) (2026-11-24, security floors). Facts verified 2026-11-24.

| Dependency | Version | Catalog | Scope | Need | Maintenance | License | Security | Size | Transitives | Why stdlib or existing fails |
|---|---|---|---|---|---|---|---|---|---|---|
| Netty (`netty-codec`, `-codec-http`, `-codec-http2`, `-common`, `-handler`, `-handler-proxy`) | 4.1.141.Final | `floor-netty-codec`, `floor-netty-codec-http`, `floor-netty-codec-http2`, `floor-netty-common`, `floor-netty-handler`, `floor-netty-handler-proxy` | build | AGP's test platform (`unified-test-platform-*`) through gRPC | Netty project, 4.1 maintenance line | Apache-2.0 | Fixes the HTTP/2, HTTP codec and handler advisories up to 4.1.137 | n/a | The other Netty 4.1 modules follow it | AGP 9.3.3 requests 4.1.93 and 4.1.110 |
| Jackson (`jackson-core`, `jackson-databind`) | 2.22.4 | `floor-jackson-core`, `floor-jackson-databind` | build | Dokka's plugin and generator | FasterXML, 2.22 line | Apache-2.0 | Fixes the 2.22.0–2.22.2 advisories | n/a | `jackson-annotations` 2.22 | Dokka 2.3.0 requests 2.22.2 |
| Guava | 33.8.0-jre | `floor-guava` | build, test | AGP, lint and test platform; Robolectric's test runtime | Google, stable | Apache-2.0 | Fixes the advisory up to 33.7.1 | n/a | Its own annotations | AGP requests 33.4.0 and 32.0.1 |
| BouncyCastle (`bcprov`, `bcpkix`) | 1.87 | `floor-bcprov`, `floor-bcpkix` | build, test | AGP's `sdk-common` and lint; Robolectric's test runtime | Legion of the Bouncy Castle, stable | MIT | Fixes the advisories below 1.85 | n/a | `bcutil` 1.87 | AGP requests 1.79 |
| FreeMarker | 2.3.35 | `floor-freemarker` | build | Kover's report generator | Apache, stable | Apache-2.0 | Fixes the advisory up to 2.3.34 | n/a | None | Kover 0.9.11 requests 2.3.32 |
| commons-lang3 | 3.22.0 | `floor-commons-lang3` | build | AGP and lint | Apache Commons, stable | Apache-2.0 | Fixes the advisory below 3.18.0 | n/a | None | AGP requests 3.16.0 |
| httpclient | 4.5.14 | `floor-httpclient` | build | AGP and lint | Apache HttpComponents, 4.5 maintenance line | Apache-2.0 | Fixes the advisory below 4.5.13 | n/a | httpcore 4.4 | AGP requests 4.5.6 |
| jose4j | 0.9.7 | `floor-jose4j` | build | AGP | Bitbucket project, active | Apache-2.0 | Fixes the advisory below 0.9.6 | n/a | None | AGP requests 0.9.5 |
| jdom2 | 2.0.6.1 | `floor-jdom2` | build | AGP | JDOM project, maintenance | JDOM (Apache-style) | Fixes the XXE advisory below 2.0.6.1 | n/a | None | AGP requests 2.0.6 |
| OpenTelemetry API | 1.68.0 | `floor-opentelemetry-api` | build | KGP's swift-export classpath | OpenTelemetry, stable | Apache-2.0 | Fixes the advisories up to 1.66.0 | n/a | `opentelemetry-context` and `-common` 1.68.0 | KGP 2.4.21 requests 1.41.0 |

## Test only

| Dependency | Version | Catalog | Scope | Need | Maintenance | License | Security | Size | Transitives | Why stdlib or existing fails |
|---|---|---|---|---|---|---|---|---|---|---|
| kotlin-test (with `kotlin-test-junit` for `build-logic/`) | 2.4.21 | `kotlin-test`, `kotlin-test-junit` | test | Multiplatform assertions; the JUnit 4 binding runs the `build-logic/` TestKit tests | JetBrains, stable | Apache-2.0 | n/a | n/a | n/a | Required |
| cucumber-kmp-core | 0.2.1 | `cucumber-kmp-core` | test | Runs Gherkin steps on Android host and iOS | Independent project; early 0.x API (user-approved hold until a stable API is available) | MIT | n/a | Test only | Kotlin stdlib, kotlinx-coroutines-core | Existing test tools do not bind Gherkin steps across these targets |
| kotlinx-coroutines-test | 1.11.0 | `kotlinx-coroutines-test` | test (including `meshlink`'s `commonTest`); `api` of the published `meshlink-testing`, never a main dependency of `meshlink` | Virtual time for deterministic tests (T2, T8); `TestClock` and `VirtualAir` run on its scheduler | JetBrains, stable | Apache-2.0 | n/a | n/a | n/a | Required for deterministic coroutine tests |
| Robolectric | 4.18.1 | `robolectric` | test | Android framework on the JVM, SDK 26 and 37; proven offline with no extra JVM flags (ADR 0005, 2026-11-14 amendment) | Google-backed, active | MIT | n/a | n/a | Large (test only) | Instrumented tests can't run in default CI (T2) |
| Robolectric android-all (`org.robolectric:android-all-instrumented`) | `8.0.0_r4-robolectric-r1-i7` (SDK 26), `17-robolectric-16653203-i7` (SDK 37) | `robolectric-android-all-sdk26`, `robolectric-android-all-sdk37` | test | Android framework jars Robolectric loads per SDK; resolved by Gradle and passed in through `robolectric-deps.properties`, so tests never download | Google-backed, active | Apache-2.0 | n/a | ~90 MB and ~241 MB jars (test only) | None (resolved non-transitively) | Robolectric's own Maven fetch at test time breaks T2 |
| JUnit 4 | 4.13.2 | `junit4` | test | Robolectric test runner; `TemporaryFolder` in the `build-logic/` TestKit tests | Maintenance mode, stable | EPL-1.0 | n/a | n/a | hamcrest-core | Robolectric needs a JUnit 4 runner |
| androidx.test core | 1.7.0 | `androidx-test-core` | test | `ApplicationProvider` for Robolectric | Google, stable | Apache-2.0 | n/a | n/a | n/a | Standard Robolectric companion |
| Jazzer (`com.code-intelligence:jazzer`) | 0.30.0 | `jazzer` | fuzz task only (the `jazzer` configuration of `:meshlink-core`, never a test or published classpath) | Coverage-guided fuzzing of decoders (T10, ADR 0017); the standalone driver with bundled macOS and Linux natives, run by `./gradlew :meshlink-core:fuzz` | Code Intelligence, active (2026-02 release) | Apache-2.0 | n/a | ~8.9 MB jar (test only) | `jazzer-api` (runtime) | The deterministic mutation tests miss deep paths; kotlinx.fuzz needs JDK 8 and custom repositories; `jazzer-junit` would add JUnit 5 |
| Jazzer API (`com.code-intelligence:jazzer-api`) | 0.30.0 | `jazzer-api` | fuzz task only, as Jazzer | The driver's runtime API, declared beside it so the catalog pins both; the targets take a plain `ByteArray` and don't compile against it | As Jazzer | Apache-2.0 | n/a | ~47 KB jar (test only) | none | Jazzer's POM needs it at run time |
| libsodium host build | 1.0.22 | none | test (host native) | Real crypto and the JNI shim in JVM host tests (ADR 0017) | As the runtime row | ISC | Same tarball and `verifyLibsodium` check; `buildLibsodiumHostMacos` (arm64) and `buildLibsodiumHostLinux` (x86_64, `--with-pic`) | n/a (never published) | none | Fake crypto would leave the shim and vectors untested on the only Kover-measured target |

## CI tools (docs checks ADR 0006, build ADR 0005)

Run in CI and locally; never shipped. Actions are pinned by commit SHA with a version comment. `checkDependencyRegister` checks this section against the workflow files: every `uses:` needs a row at its comment's version (a sub-action such as `actions/cache/restore` counts as its repository), each action has one commit across all workflows, `lycheeVersion` matches the lychee row, and every action row is used.

| Tool | Version | Action | License | Need | Why existing fails |
|---|---|---|---|---|---|
| lychee | 0.24.2 | `lycheeverse/lychee-action` v2.9.0 | Apache-2.0 OR MIT | Link check: repository links offline on every PR, external links weekly (O6) | Nothing in the build checks Markdown links |
| markdownlint-cli2 | 0.24.2 | `DavidAnson/markdownlint-cli2-action` v25.1.0 | MIT | Markdown structure lint (O6) | Spotless/ktlint cover Kotlin only |
| typos | 1.52.2 | `crate-ci/typos` v1.52.2 | Apache-2.0 OR MIT | Spelling, US English, offline, low false positives (O6) | No spelling gate exists; cspell needs Node and is noisier |
| actions/checkout | 7.0.1 | `actions/checkout` v7.0.1 | MIT | Check out the repository for the build, docs, Pages, privacy and freshness jobs | GitHub's own checkout action; nothing to replace |
| setup-gradle | 6.4.1 | `gradle/actions/setup-gradle` v6.4.1, `cache-provider: basic` | MIT (the action and its basic provider; the default `enhanced` provider is proprietary and not used) | Caches `~/.gradle/caches` (dependencies, Robolectric android-all jars) and `~/.gradle/wrapper`, keyed by the build files, the version catalog and the wrapper properties; validates the wrapper JAR checksum; only `main` writes the cache (`build.yml`, ticket 05). Off on self-hosted runners, whose `~/.gradle` persists; wrapper validation stays on | Without a cache every run downloads every dependency and ~330 MB of android-all jars; plain `actions/cache` would also need a separate wrapper-validation action |
| actions/cache | 6.1.0 | `actions/cache` v6.1.0 | MIT | Caches the libsodium download and per-target outputs, with a fallback across catalog changes for the same job, toolchain, pin and script; the Linux toolchain includes the compiler version and OS release. Also caches Kotlin/Native dependencies by OS, architecture, Xcode, Kotlin version and phase, with a fallback to the same toolchain, on GitHub's runners only (the self-hosted Mac mini keeps `~/.konan`) | setup-gradle's basic provider caches only Gradle's home; Gradle's task history isn't cached, so the output stamp, not Gradle, decides whether to rebuild libsodium |
| actions/upload-artifact | 7.0.2 | `actions/upload-artifact` v7.0.2 | MIT | Uploads fuzz crashes and the iOS Gradle timing profile and test XML | GitHub's own artifact action; crash inputs and timing reports must leave the runner to be inspected |
| actions/download-artifact | 8.0.2 | `actions/download-artifact` v8.0.2 | MIT | Downloads the per-cell density reports into the summary job (`density.yml`, ticket 76) | GitHub's own artifact action; the summary job runs on another runner than the cells |
| actions/upload-pages-artifact | 5.0.0 | `actions/upload-pages-artifact` v5.0.0 | MIT | Packs the assembled docs site (`pages.yml`) as the artifact a Pages deployment takes (ADR 0006 decision 8) | GitHub's own Pages packaging; `deploy-pages` accepts only its archive format |
| actions/deploy-pages | 5.0.1 | `actions/deploy-pages` v5.0.1 | MIT | Deploys that artifact to GitHub Pages from the `github-pages` environment with the job's OIDC token, on a release only (`pages.yml`, ADR 0019 decision 7) | GitHub's own Pages deployment; no branch or push token is needed. `actions/configure-pages` is not used: the site needs none of its base-URL outputs |
| actionlint | 1.7.12 | None: run locally (Homebrew) | MIT | Lints every workflow (ticket 05 proof) | GitHub reports workflow errors only after a push |
| GitHub-hosted runner `ubuntu-24.04` | Image as of the run (x64) | `runs-on` | n/a | Linux build job for fork PRs: JDK 21 and 25 (`JAVA_HOME_21_X64`, `JAVA_HOME_25_X64`), Android SDK platform 37 (verified 2026-11-14 in the runner-images readme); the job installs `minisign` with `apt` and the catalog's NDK with `sdkmanager` | n/a: fork workflows should not run on the self-hosted runner |
| Self-hosted runners (Fedora) | Common labels `self-hosted`, `Linux`, `X64`, `linux-ci`; host labels `YOGA` (Fedora 44) and `VIVOBOOK` (Fedora 45); runner user `gh-runner` ([maintenance](../how-to/maintain-fedora-runners.md)) | `runs-on` | n/a | Linux build for pushes, same-repository PRs and dispatches; JDK 21 and 25, `minisign`, and Android SDK platform 37.0 and catalog NDK | Runs the required Linux build on the owner's hosts; see ADR 0017 for the public-repository risk |
| GitHub-hosted runner `macos-26` | Image as of the run (arm64) | `runs-on` | n/a | Apple phases of a fork's pull request with `/Applications/Xcode_26.4.1.app`, iOS Simulator runtimes, JDK 21 and 25 (`JAVA_HOME_21_arm64`, `JAVA_HOME_25_arm64`), Android SDK platform 37 (ADR 0005 "CI"; verified 2026-11-14); the job installs `minisign` with Homebrew and the catalog's NDK with `sdkmanager` | n/a: fork code must not run on the self-hosted runner |
| Self-hosted runner (Mac mini) | Labels `self-hosted`, `macOS`, `ARM64`; macOS 27, runner user `gh-runner`; Xcode installed with `xcodes` ([setup](../how-to/set-up-self-hosted-runner.md)) | `runs-on` | n/a | Apple phases of pushes, same-repository pull requests and dispatches with `/Applications/Xcode-27.2.0.app` and the iOS 18.6 simulator runtime. Its `.env` sets the hosted image's names: `JAVA_HOME_21_arm64` (Homebrew `openjdk@21`), `JAVA_HOME_25_arm64` (Temurin 25.0.4) and `ANDROID_HOME` (an SDK with the catalog's NDK); `minisign` comes from Homebrew (ADR 0005 and ADR 0017 self-hosted runner amendments, 2026-11-21) | GitHub's only Xcode 27.2 image is the `xcode-27` preview |
| Python 3 (standard library only) | 3.9 or newer (verified: 3.9.6, 3.13 and 3.15.2 produce identical vectors) | None: the `ubuntu-24.04` runner image's `python3` | PSF-2.0 | Generates and checks the protocol spec's tier-1 vectors and data tables: `tools/spec/gen_vectors.py`, `tools/spec/check_spec.py` (C10, ticket 29). Scans tracked files, or with `--history` every blob of every branch, gzipped evidence included, for private lab and personal identifiers: `tools/check_private.py` (tickets 30 and 35; `privacy.yml` and `.githooks/pre-push`). Checks that each ADR's version matches its amendments: `tools/check_adrs.py` (ADR 0006 amendment). Drives the device lab and writes evidence records: `tools/lab/lab.py` and its unit tests (ticket 16; run locally, tested in `docs.yml`). Lists every catalog entry except the security floors, every action and tool with a newer stable release from Maven Central, Google Maven, the Gradle Plugin Portal, the Android SDK repository, GitHub releases, npm and PyPI: `tools/check_freshness.py` (ticket 56; weekly in `freshness.yml`, tested in `docs.yml`). Uses `hashlib` BLAKE2b and `hmac` HKDF; no third-party packages | Gradle tests run only after Kotlin code exists, and the spec must be self-checking now; the standard library needs no extra dependency |

## Lab apps (never published)

Used only by `labapp/` and `tools/lab/` ([ADR 0005](../adr/0005-toolchain-and-dependency-baseline.md), 2026-11-15 lab apps amendment). `DependencyDirectionPlugin` keeps every published module from reaching them.

| Dependency | Version | Catalog | Scope | Need | Maintenance | License | Why existing fails |
|---|---|---|---|---|---|---|---|
| Compose BOM (`ui`, `material3`) | 2026.11.01 (`ui` 1.13.0, `material3` 1.5.0) | `compose-bom`, `compose-ui`, `compose-material3` | `:labapp-android` | The Android lab app's screen (ADR 0017 decision 7) | Google, stable | Apache-2.0 | Android Views would need XML layouts the lab doesn't otherwise need |
| `androidx.activity:activity-compose` | 1.13.0 | `androidx-activity-compose` | `:labapp-android` | `ComponentActivity.setContent` | Google, stable | Apache-2.0 | Compose needs a host activity |
| Kotlin Compose compiler plugin | 2.4.21 (Kotlin) | `compose-compiler-gradle-plugin` | `:labapp-android` build | Compiles `@Composable` functions | JetBrains, ships with Kotlin | Apache-2.0 | Required by Compose |
| XcodeGen | 2.46.0 | none | host tool | Generates `labapp/ios/LabHost.xcodeproj` from `project.yml` | Active (release 2026-10) | MIT | A checked-in Xcode project churns and hides its settings |
| libimobiledevice + ideviceinstaller | 1.4.0 + 1.2.0 | none | host tool (Homebrew) | Installs, launches and reads results on iOS 15 and 16 phones, which `devicectl` can't drive | Active | LGPL-2.1 + GPL-2.0 (run as programs, never linked) | `devicectl` needs iOS 17 |
| adb (Android SDK platform-tools) | 37.0.1 | none | host tool | Installs, launches and reads results on Android phones | Google, stable | Apache-2.0 | Required for Android devices |

## Vendored test vectors

Data, not code: unchanged copies under `third_party/vectors/`, embedded only into `:meshlink-crypto-sodium` test sources and never published. `generateSpecVectors` fails if a file's SHA-256 differs from its `SHA256SUMS` line. The [vectors README](../spec/vectors/README.md#vector-runner) describes the runner.

| Set | Source | Pin | Files and SHA-256 | License | Need |
|---|---|---|---|---|---|
| Wycheproof | <https://github.com/C2SP/wycheproof> `testvectors_v1/` | commit `65ca494727a52944c204441c860b039e6786b99b` | `x25519_test.json` `35c3f523…be7d`, `ed25519_test.json` `ea80ffeb…74b3`, `chacha20_poly1305_test.json` `fe61d25f…d53d`, `hkdf_sha256_test.json` `bb2b462a…5f1e`, `mlkem_768_test.json` `c59c067a…1e90`, `mlkem_768_encaps_test.json` `9d4381f9…b713`, `mlkem_768_keygen_seed_test.json` `fde5abe2…ebe4`, `mlkem_768_semi_expanded_decaps_test.json` `e4438ab7…060a` (full values in `SHA256SUMS`) | Apache-2.0 (`LICENSE` vendored) | Edge cases for every primitive (ADR 0002, ADR 0004) |
| hpke-pq | <https://github.com/hpkewg/hpke-pq> `test-vectors.json` | tag `draft-ietf-hpke-pq-05`, commit `6433c8fce0b8b749dfc86c1095081a88698ccfab` | `test-vectors.json` `35c59f4a…613c` | IETF contribution, BCP 78 and the IETF Trust Legal Provisions; code components Revised BSD | X-Wing (KEM `0x647a`) known answers; replaces the hand-copied A.5 vectors of ticket 18 |
| cacophony | <https://github.com/haskell-cryptography/cacophony> `vectors/cacophony.txt` | commit `8ee9d41e34a1a596cfa3ab12aa4069ff87dc1247` | `cacophony.txt` `3bde7c09…6cae` | Unlicense (`LICENSE` vendored) | Noise `XX` and `XXpsk3` known answers, handshake and transport, from an independent implementation (ADR 0002) |

## Reference (test-only Rust, `tools/reference/`, never published)

Pinned by `rust-toolchain.toml` (Rust 1.100.0) and a committed `Cargo.lock`. Purpose: an independent second vector set for ADR 0002 and ADR 0004.

| Crate | Version | License | Need |
|---|---|---|---|
| hpke (rust-hpke) | 0.15.1 | MIT/Apache-2.0 | HPKE reference |
| ml-kem | 0.3.4 | Apache-2.0 OR MIT | ML-KEM-768 reference |
| x25519-dalek | 3.0.0 | BSD-3-Clause | X25519 reference |
| ed25519-dalek | 3.0.0 | BSD-3-Clause | Ed25519 / identity-certificate reference |
| chacha20poly1305 | 0.11.0 | Apache-2.0 OR MIT | AEAD reference, through hpke's `chacha` feature; not a direct dependency (ADR 0005, 2026-11-16 amendment) |
| sha2 | 0.11.1 | MIT OR Apache-2.0 | SHA-256 reference |
| hkdf | 0.13.0 | MIT OR Apache-2.0 | HKDF reference |
| blake2 | 0.11.0 | MIT OR Apache-2.0 | BLAKE2b-256 for `H` and `DS` derivations (tier 1 rule); the Python generator uses `hashlib` |
| snow | git `dc18c06c28fbdeaa6ffae7347e650c805e9b932e` (snow#210) | Apache-2.0 OR MIT | Independent Noise `XXhfs` with ML-KEM-768, and the ChaCha20-Poly1305 that seals the reference lookup replies. Moves to a crates.io release once #210 merges. It brings its own `chacha20poly1305` 0.10.1, `blake2` 0.10.6 and `curve25519-dalek` 4.1.3, so `Cargo.lock` holds two versions of each |
