# Dependency register

Every dependency and tool MeshLink uses, with its Constitution E3 rationale. The decision behind this baseline is [ADR 0005](../adr/0005-toolchain-and-dependency-baseline.md).

Rules:

- `gradle/libs.versions.toml` is the only source of versions; this page never overrides it. A CI check fails if any catalog entry has no row here.
- Native and Rust entries have no catalog entry, so their pins live in the build scripts and `tools/reference/`, and they are listed here by hand.
- Adding or removing a dependency needs user approval and a row here in the same change.

Scope legend:

- `runtime`: ships inside a published artifact.
- `build`: Gradle or compiler tooling.
- `test`: test sources only.
- `reference`: test-only Rust, never published.

Facts verified 2026-11-01.

## Runtime (ships in artifacts)

| Dependency | Version | Scope | Need | Maintenance | License | Security | Size | Transitives | Why stdlib or existing fails |
|---|---|---|---|---|---|---|---|---|---|
| Kotlin stdlib | 2.4.21 | runtime | Language runtime | JetBrains, stable | Apache-2.0 | JetBrains security process | Platform-provided per target | None | Required by Kotlin |
| kotlinx-coroutines-core | 1.11.0 | runtime | Structured concurrency, `Flow` for the public API and bearer I/O | JetBrains, stable | Apache-2.0 | JetBrains security process | Shared with most KMP host apps | atomicfu (compile-time) | The stdlib has no coroutine library |
| libsodium | 1.0.22 | runtime (native, static, hidden symbols) | X25519, Ed25519, ChaCha20-Poly1305, SHA-256/HKDF, ML-KEM-768, X-Wing | See ADR 0004 | ISC | ADR 0004; pinned once in `meshlink-crypto-sodium/libsodium.properties` (sha256 `adbdd8f1…be3349`, upstream minisign key); `verifyLibsodium` checks both before extraction, locally and in CI | Static archive at `-Os`, minimal: arm64-v8a 539 KB, armeabi-v7a 551 KB, x86_64 651 KB, `ios-arm64` 427 KB stripped. Linked into a shared object or app with every MeshLink primitive: arm64-v8a 189 KB, armeabi-v7a 302 KB, x86_64 284 KB, iOS 150 KB (ADR 0011, 2026-11-14 amendment) | None | ADR 0004 |

## Build tooling

| Dependency | Version | Scope | Need | Maintenance | License | Security | Size | Transitives | Why stdlib or existing fails |
|---|---|---|---|---|---|---|---|---|---|
| Gradle | 9.7.1 | build | Build system that KMP requires; its bundled `kotlin-dsl` plugin and TestKit build and test `build-logic/` | Gradle Inc., stable | Apache-2.0 | Wrapper checksum validated | n/a | n/a | Required by KGP |
| Kotlin Gradle plugin | 2.4.21 | build | KMP compilation, XCFramework, built-in ABI validation (E2 hold: Experimental, until it becomes stable) | JetBrains, stable | Apache-2.0 | n/a | n/a | n/a | Required |
| AGP + `com.android.kotlin.multiplatform.library` | 9.3.3 | build | Android library target in KMP | Google, stable | Apache-2.0 | n/a | n/a | n/a | Required for the Android target |
| Android NDK | 30.0.16248370 (`android-ndk` in the version catalog) | build | Builds libsodium and the JNI shim | Google, stable | Apache-2.0 and others | n/a | n/a | n/a | Native crypto needs it (ADR 0004) |
| SKIE | 0.10.16 | build (compiler plugin) | Swift-friendly suspend, Flow, sealed and enum interop | Touchlab, releases follow Kotlin; 0.10.16 adds 2.4.21 | Apache-2.0 | Analytics upload disabled; `produceDistributableFramework()` (Swift library evolution, required for an XCFramework) | Adds Swift glue to the framework | None at runtime | Plain Objective-C export loses suspend, Flow and sealed types |
| Kover | 0.9.11 | build | T3 coverage for common and Android | JetBrains; Beta (E2 hold until a stable release) | Apache-2.0 | n/a | n/a | n/a | No built-in coverage tool |
| Spotless + ktlint | 8.11.0 + 1.8.0 | build | Formatting check (`spotlessCheck`) | DiffPlug and Pinterest, active | Apache-2.0 / MIT | n/a | n/a | n/a | No built-in formatter gate |
| detekt | 2.0.0-alpha.6 | build | Static analysis | Active; alpha (E2 hold until 2.0 stable) | Apache-2.0 | n/a | n/a | n/a | 1.23.8 is built against Kotlin 2.0.21; ktlint is formatting only |
| Dokka | 2.3.0 | build | API reference docs | JetBrains, stable | Apache-2.0 | n/a | n/a | n/a | Required for generated API docs |
| vanniktech maven-publish | 0.37.0 | build | Maven Central publishing for KMP; applied as the `.base` plugin by `meshlink.published-library` | Active | Apache-2.0 | Signing configured at release | n/a | n/a | Plain `maven-publish` lacks Central Portal upload and KMP defaults |
| minisign | 0.11 or newer (CI: Ubuntu 24.04 `apt` 0.11, Homebrew 0.12) | build (host tool) | Verifies the libsodium tarball's prehashed Ed25519 signature in `verifyLibsodium` | Frank Denis (libsodium's author), stable | ISC | Upstream signs libsodium releases with it | n/a | n/a | The JDK has Ed25519 but no BLAKE2b, which prehashed signatures need; a hand-written BLAKE2b would be more crypto code to prove |
| Xcode | 27.2 local, 26.4.1 CI | build (host) | iOS SDK and the Swift consumer | Apple | Apple EULA | n/a | n/a | n/a | Required for Apple targets; versions per ADR 0005 v4 |

## Test only

| Dependency | Version | Scope | Need | Maintenance | License | Security | Size | Transitives | Why stdlib or existing fails |
|---|---|---|---|---|---|---|---|---|---|
| kotlin-test (with `kotlin-test-junit` for `build-logic/`) | 2.4.21 | test | Multiplatform assertions; the JUnit 4 binding runs the `build-logic/` TestKit tests | JetBrains, stable | Apache-2.0 | n/a | n/a | n/a | Required |
| kotlinx-coroutines-test | 1.11.0 | test | Virtual time for deterministic tests (T2, T8) | JetBrains, stable | Apache-2.0 | n/a | n/a | n/a | Required for deterministic coroutine tests |
| Robolectric | 4.18 | test | Android framework on the JVM, SDK 26 and 37; proven offline with no extra JVM flags (ADR 0005, 2026-11-14 amendment) | Google-backed, active | MIT | n/a | n/a | Large (test only) | Instrumented tests can't run in default CI (T2) |
| Robolectric android-all (`org.robolectric:android-all-instrumented`) | `8.0.0_r4-robolectric-r1-i7` (SDK 26), `17-robolectric-16653203-i7` (SDK 37) | test | Android framework jars Robolectric loads per SDK; resolved by Gradle and passed in through `robolectric-deps.properties`, so tests never download | Google-backed, active | Apache-2.0 | n/a | ~90 MB and ~241 MB jars (test only) | None (resolved non-transitively) | Robolectric's own Maven fetch at test time breaks T2 |
| JUnit 4 | 4.13.2 | test | Robolectric test runner; `TemporaryFolder` in the `build-logic/` TestKit tests | Maintenance mode, stable | EPL-1.0 | n/a | n/a | hamcrest-core | Robolectric needs a JUnit 4 runner |
| androidx.test core | 1.7.0 | test | `ApplicationProvider` for Robolectric | Google, stable | Apache-2.0 | n/a | n/a | n/a | Standard Robolectric companion |
| Jazzer (`com.code-intelligence:jazzer`) | 0.30.0 | test (scheduled job) | Coverage-guided fuzzing of decoders (T10, ADR 0017); the standalone driver with bundled macOS and Linux natives | Code Intelligence, active (2026-02 release) | Apache-2.0 | n/a | ~8.9 MB jar (test only) | `jazzer-api` (runtime) | The deterministic mutation tests miss deep paths; kotlinx.fuzz needs JDK 8 and custom repositories; `jazzer-junit` would add JUnit 5 |
| libsodium host build | 1.0.22 | test (host native) | Real crypto and the JNI shim in JVM host tests (ADR 0017) | As the runtime row | ISC | Same tarball and `verifyLibsodium` check; `buildLibsodiumHostMacos` (arm64) and `buildLibsodiumHostLinux` (x86_64, `--with-pic`) | n/a (never published) | none | Fake crypto would leave the shim and vectors untested on the only Kover-measured target |

## CI tools (docs checks ADR 0006, build ADR 0005)

Run in CI and locally; never shipped. Actions are pinned by commit SHA with a version comment. This section is checked against the workflow files, not the version catalog.

| Tool | Version | Action | License | Need | Why existing fails |
|---|---|---|---|---|---|
| lychee | 0.24.2 | `lycheeverse/lychee-action` v2.9.0 | Apache-2.0 OR MIT | Link check: repository links offline on every PR, external links weekly (O6) | Nothing in the build checks Markdown links |
| markdownlint-cli2 | 0.24.0 | `DavidAnson/markdownlint-cli2-action` v25.0.0 | MIT | Markdown structure lint (O6) | Spotless/ktlint cover Kotlin only |
| typos | 1.52.0 | `crate-ci/typos` v1.52.0 | Apache-2.0 OR MIT | Spelling, US English, offline, low false positives (O6) | No spelling gate exists; cspell needs Node and is noisier |
| actions/checkout | 7.0.1 | `actions/checkout` v7.0.1 | MIT | Check out the repository for the build, docs and privacy jobs | GitHub's own checkout action; nothing to replace |
| setup-gradle | 6.4.1 | `gradle/actions/setup-gradle` v6.4.1, `cache-provider: basic` | MIT (the action and its basic provider; the default `enhanced` provider is proprietary and not used) | Caches `~/.gradle/caches` (dependencies, Robolectric android-all jars) and `~/.gradle/wrapper`, keyed by the build files, the version catalog and the wrapper properties; validates the wrapper JAR checksum; only `main` writes the cache (`build.yml`, ticket 05) | Without a cache every run downloads every dependency and ~330 MB of android-all jars; plain `actions/cache` would also need a separate wrapper-validation action |
| actions/cache | 6.1.0 | `actions/cache` v6.1.0 | MIT | Caches the libsodium download and per-target outputs in `build.yml`, keyed by the job, the toolchain (`cc -dumpfullversion` or `xcodebuild -version`) and the hashes of `libsodium.properties`, `build-libsodium.sh` and the version catalog (ticket 06) | setup-gradle's basic provider caches only Gradle's home; Gradle's task history isn't cached, so the output stamp, not Gradle, decides whether to rebuild |
| actionlint | 1.7.12 | None: run locally (Homebrew); `.github/actionlint.yaml` declares the `xcode-27` preview label | MIT | Lints every workflow (ticket 05 proof) | GitHub reports workflow errors only after a push |
| GitHub-hosted runner `ubuntu-24.04` | Image as of the run (x64) | `runs-on` | n/a | Linux build job: JDK 21 and 25 (`JAVA_HOME_21_X64`, `JAVA_HOME_25_X64`), Android SDK platform 37 (verified 2026-11-14 in the runner-images readme); the job installs `minisign` with `apt` and the catalog's NDK with `sdkmanager` | n/a: the image ships everything else the Gradle gates need |
| GitHub-hosted runner `macos-26` | Image as of the run (arm64) | `runs-on` | n/a | Required macOS job with `/Applications/Xcode_26.4.1.app`, iOS Simulator runtimes, JDK 21 and 25 (`JAVA_HOME_21_arm64`, `JAVA_HOME_25_arm64`), Android SDK platform 37 (ADR 0005 "CI"; verified 2026-11-14); the job installs `minisign` with Homebrew | n/a: the only generally available image with Xcode 26.4.1 |
| GitHub-hosted runner `xcode-27` | Preview image (arm64, macOS 27) | `runs-on` | n/a | Weekly, non-required job with `/Applications/Xcode_27.2.app` (ADR 0005 "CI"; actions/runner-images issue 14404; verified 2026-11-14) | n/a: the only hosted image with Xcode 27.2 |
| Python 3 (standard library only) | 3.9 or newer (verified: 3.9.6, 3.13 and 3.15.2 produce identical vectors) | None: the `ubuntu-24.04` runner image's `python3` | PSF-2.0 | Generates and checks the protocol spec's tier-1 vectors and data tables: `tools/spec/gen_vectors.py`, `tools/spec/check_spec.py` (C10, ticket 29). Scans tracked files, or with `--history` every blob of every branch, gzipped evidence included, for private lab and personal identifiers: `tools/check_private.py` (tickets 30 and 35; `privacy.yml` and `.githooks/pre-push`). Checks that each ADR's version matches its amendments: `tools/check_adrs.py` (ADR 0006 amendment). Uses `hashlib` BLAKE2b and `hmac` HKDF; no third-party packages | Gradle tests run only after Kotlin code exists, and the spec must be self-checking now; the standard library needs no extra dependency |

## Reference (test-only Rust, `tools/reference/`, never published)

Pinned by `rust-toolchain.toml` (Rust 1.100.0) and a committed `Cargo.lock`. Purpose: an independent second vector set for ADR 0002 and ADR 0004.

| Crate | Version | License | Need |
|---|---|---|---|
| hpke (rust-hpke) | 0.15.1 | MIT/Apache-2.0 | HPKE reference |
| ml-kem | 0.3.4 | Apache-2.0 OR MIT | ML-KEM-768 reference |
| x25519-dalek | 3.0.0 | BSD-3-Clause | X25519 reference |
| ed25519-dalek | 3.0.0 | BSD-3-Clause | Ed25519 / identity-certificate reference |
| chacha20poly1305 | 0.11.0 | Apache-2.0 OR MIT | AEAD reference |
| sha2 | 0.11.1 | MIT OR Apache-2.0 | SHA-256 reference |
| hkdf | 0.13.0 | MIT OR Apache-2.0 | HKDF reference |
| snow | git `dc18c06c28fbdeaa6ffae7347e650c805e9b932e` (snow#210) | Apache-2.0 OR MIT | Independent Noise `XXhfs` with ML-KEM-768. Moves to a crates.io release once #210 merges |
