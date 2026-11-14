# 0005: Toolchain and dependency baseline

Status: accepted (2026-11-01; ticket "What is the toolchain and dependency baseline?"). Version: 6 (amended 2026-11-08, ADRs 0017 and 0019; 2026-11-13, toolchain refresh; 2026-11-14, quality gates; 2026-11-14, Robolectric proof).

## Context

MeshLink is a Kotlin Multiplatform library for Android (minSdk 26) and iOS 15+. Xcode 16.4 was the only Apple toolchain (hard constraint, ticket "Is the device lab ready?"); the 2026-11-13 amendment replaces it with Xcode 27.2 locally and Xcode 26.4.1 in CI. [ADR 0004](0004-crypto-provider-libsodium.md) adds a libsodium 1.0.22 native build and a test-only Rust reference. E1–E3 require pinned, mutually compatible, stable releases, and every dependency needs a rationale.

Two throwaway smoke builds on 2026-11-01 (outside the repository) proved the tuple below.

**Kotlin tuple: all of these passed.**

- `build` with the configuration cache;
- Robolectric on SDK 26 and 37, under JDK 21 and 25;
- `iosSimulatorArm64Test`;
- a release XCFramework (minos 15.0) consumed by a SwiftPM `binaryTarget` test that calls SKIE suspend, Flow and sealed APIs;
- Kover at 100% on common and Android;
- Dokka, Spotless, detekt, KGP `abiValidation` (which failed correctly on an API change) and `publishToMavenLocal`.

**libsodium:**

- Source sha256 and minisign signature verified.
- `--enable-minimal` keeps every primitive MeshLink needs.
- Stripped shim at `-Os`: arm64 194 KB, armv7 306 KB, x86_64 289 KB; iOS arm64 280 KB.
- Only shim symbols are exported.
- X-Wing matches `draft-connolly-cfrg-xwing-kem-11` Appendix C and `draft-ietf-hpke-pq-05` A.5/A.12 byte for byte on lab phones.
- Upstream `make check` passes 87/87.

Facts verified 2026-11-01:

- **Kotlin compatibility:** Kotlin 2.4.21 documents Gradle up to 9.7.0 and AGP up to 9.3.1. Gradle 9.8.2 and AGP 9.5.0 are released but not listed. Kotlin 2.4.21 with Xcode 16.4 builds, links and runs without a KGP warning, so the Kotlin 2.2.20 fallback is unneeded.
- **SKIE:** SKIE 0.10.16 (tag 2026-10-27, "Add support for Kotlin 2.4.21", touchlab/SKIE#211) supports Kotlin 2.4.21. The SKIE intro page still says "up to 2.4.10" and is stale. SKIE uploads build analytics by default.
- **detekt:** stable 1.23.8 is built against Kotlin 2.0.21. Only `2.0.0-alpha.6` (`dev.detekt`) runs on 2.4.x.
- **Kotlin/Native coverage:** no CI tool measures it. Kover does not support native targets (kotlinx-kover#293, open), and Kotlin 2.4.21 rejects `-Xcoverage`.
- **ABI validation:** KGP built-in `abiValidation` is `@ExperimentalAbiValidation` and covers Android and klib. The legacy binary-compatibility-validator is in maintenance mode.
- **Robolectric 4.17 on SDK 37** needs `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` and `--add-opens=java.base/java.io=ALL-UNNAMED`. One SDK 37 run hung once.
- **libsodium's arm64 flag:** the upstream arm64 Android script uses `-march=armv8-a+crypto`, and that compiles out libsodium's runtime check for AES and PMULL (`runtime.c`). AES/PMULL instructions appear only in the AES-GCM, AEGIS and ipcrypt objects. MeshLink's primitives contain none. The lab has no arm64 phone without the crypto extensions; all 29 online phones report `aes pmull sha1 sha2`.
- **libsodium on Apple:** the upstream Apple script (`apple-xcframework.sh`) builds every Apple platform in about 16 minutes.
- **CI runners:** GitHub's `macos-15` arm64 runner image (20261028) has Xcode 16.4 as default, plus JDK 21 and 25.
- **Apple signing:** both lab signing teams (`apple-team-1`, `apple-team-2`) are free personal teams. Profiles expire after 7 days, each team covers at most 3 devices, and each device allows 3 free-provisioned apps.
- **snow#210** (ML-KEM-768 `hfs`) is open; head `dc18c06c28fbdeaa6ffae7347e650c805e9b932e`.

## Decision

**Version tuple** (source of truth: `gradle/libs.versions.toml`; rationale per entry in the [dependency register](../reference/dependencies.md)):

| Component | Version | Note |
|---|---|---|
| Kotlin (KGP, compiler, stdlib) | 2.4.21 | Builds with Xcode 16.4 and 27.2 |
| Gradle | 9.7.1 | Newest patch in the minor that KGP lists |
| AGP + `com.android.kotlin.multiplatform.library` | 9.3.3 | Newest patch in the minor that KGP lists |
| Gradle daemon JDK / compile toolchain / test runtime | 25 / 21 / 21 | |
| compileSdk / minSdk | 37 / 26 | |
| Xcode / iOS deployment target | 27.2 local, 26.4.1 CI / 15.0 | See the 2026-11-13 amendment |
| Android NDK | 30.0.16248370 | Used for the libsodium build and the JNI shim |
| kotlinx-coroutines | 1.11.0 | |
| SKIE | 0.10.16 | |
| Kover | 0.9.11 | |
| Spotless + ktlint | 8.11.0 + 1.8.0 | |
| detekt | 2.0.0-alpha.6 | E2 hold |
| Dokka | 2.3.0 | |
| vanniktech maven-publish | 0.37.0 | |
| Robolectric / JUnit 4 / androidx.test core | 4.18 / 4.13.2 / 1.7.0 | Test only; 4.18 proven on SDK 26 and 37 (2026-11-14 amendment) |
| libsodium | 1.0.22 | ADR 0004 |
| Rust (test-only reference) | 1.100.0 | |

Version rules:

- **Moving to a newer minor:** for Gradle, AGP or Kotlin, only after KGP's compatibility table (or, for SKIE, its release) lists the combination.
- **Patch releases** within a listed minor are allowed after the smoke gates pass.

**Targets:**

- **Android ABIs:** `arm64-v8a`, `armeabi-v7a` (the lab has v7a-only API 28 phones) and `x86_64` (emulators). No `x86`.
- **iOS:** `iosArm64` and `iosSimulatorArm64`; no `iosX64` (Tier 3, Intel Macs only).
  - The simulator slice ships so that host apps can build and run their UI in the simulator, where MeshLink reports "Bluetooth unsupported" through its normal state API.
  - The slice also runs non-radio tests in CI: crypto vectors, the libsodium binding and the Swift API.
  - Every radio test runs on hardware only (T9).

**Tools:**

- **E2 holds:**

  | Tool | Blocker | Removed when | Wiring |
  |---|---|---|---|
  | detekt 2.0.0-alpha.6 | Prerelease | detekt 2.0 stable supports the Kotlin version | Per-source-set tasks wired into `check`, because the plain task finds no sources in KMP |
  | KGP built-in `abiValidation` | Experimental | It becomes stable | `@OptIn(ExperimentalAbiValidation::class)`; wired into `check` |
  | Kover 0.9.11 | Beta | A stable release | The root report merges every module's Android host-test run; `koverVerify` is in `check` by default |

- **SKIE:** analytics upload disabled (`skie { analytics { disableUpload.set(true) } }`). The Swift API shape belongs to the public-API ticket.
- **Robolectric:** `Test` tasks carry no extra JVM flags (2026-11-14 amendment). Test classes carry a JUnit `Timeout` rule, and every test task has a 10-minute timeout ([ADR 0017](0017-testing-architecture.md) decision 13).
- **Native bundle ID:** set explicitly through `binaryOption("bundleId", …)`.

**libsodium build:**

- Unmodified upstream `dist-build` scripts, `-Os`, `--enable-minimal`, with the tarball's sha256 and minisign signature verified in CI.
- The iOS XCFramework build runs in full, with its output cached in CI and keyed by the tarball's sha256.
- Keep the upstream `-march=armv8-a+crypto`. **Invariant:** the shim never exposes AES-GCM, AEGIS, ipcrypt or any other API whose hardware path is selected by libsodium's compile-time CPU detection. A CI check fails if the shim's exported or imported symbol set includes an `aes`, `aegis` or `ipcrypt` function.

**Coverage:**

- T3 as amended by Constitution 4.0.0: Kover measures `commonMain` and `androidMain` at 100% line and branch coverage.
- `iosMain` holds only thin platform adapters. Each one is listed in a CI-named file and exercised by on-target tests.

**Test-only Rust reference** (`tools/reference/`; never published, never linked into an artifact):

- `rust-toolchain.toml` pins Rust 1.100.0, and `Cargo.lock` is committed.
- Crates:
  - rust-hpke 0.15.1, RustCrypto `ml-kem` 0.3.4;
  - `x25519-dalek` and `ed25519-dalek` 3.0.0, `chacha20poly1305` 0.11.0;
  - `sha2` 0.11.1 and `hkdf` 0.13.0;
  - snow pinned to snow#210 head `dc18c06c28fbdeaa6ffae7347e650c805e9b932e`, for an independent `XXhfs` cross-check. Move to a crates.io release once #210 merges.

**Dependency register:**

- `gradle/libs.versions.toml` is the only version source.
- `docs/reference/dependencies.md` holds one E3 row per catalog entry, plus the native and Rust entries.
- A CI check fails if any catalog entry has no register row.

**CI:**

- GitHub, with GitHub Actions; every action is pinned to a commit SHA with a version comment.
- Required macOS jobs and the release workflow run on `macos-26` arm64 with Xcode 26.4.1 selected explicitly (`/Applications/Xcode_26.4.1.app`). A scheduled, non-required job runs on the `xcode-27` preview image with Xcode 27.2.
- NDK 30.0.16248370 is installed explicitly with `sdkmanager`, because the runner images don't ship it.
- The repository has no remote yet. Creating it is an external change and needs explicit approval.

**Publishing:**

- vanniktech 0.37.0 is configured, with `publishToMavenLocal` as the local proof.
- Maven Central credentials, the GPG key and XCFramework/SwiftPM hosting belong to the release process.

**Device-lab signing:**

- One shared lab bundle ID per free team: `ch.trancee.lab.host` plus its UI-test runner. MeshLink, cairn and sqisign all use it, so every install replaces the previous app and uses at most 2 of a device's 3 free slots.
- A local script re-signs within the 7-day expiry before lab runs.
- If the free tier keeps blocking device tests, revisit a paid team.
- The libsodium iPhone benchmark runs on the iPhone SE (2016, iOS 15.8) as low-end T9 evidence. Done 2026-11-02 ([evidence](../evidence/2026-11-se2016-bench/README.md)).

## Alternatives

- **Kotlin 2.2.20 with Gradle 8.14 and AGP 8.11:** officially tested with Xcode 16.4, but unnecessary because 2.4.21 works. Rejected as older.
- **Gradle 9.8 and AGP 9.5:** newest, but outside KGP's documented range (E2).
- **detekt 1.23.8:** stable, but built against Kotlin 2.0.21. **ktlint only:** loses static analysis. Rejected.
- **The legacy binary-compatibility-validator:** in maintenance mode, and its klib support is experimental too.
- **`-O2` libsodium:** about 25% faster ML-KEM/X-Wing, at +78 KB on arm64 and a modified script. Rejected, because `-Os` already decapsulates X-Wing in 0.74 ms on the slowest phone (P10 lite).
- **`-march=armv8-a`:** keeps libsodium's runtime CPU check for +152 B, but needs a modified script and cannot be proven on lab hardware. The symbol-set check guards the same risk.
- **No simulator slice:** breaks simulator builds of host apps, and removes iOS non-radio tests from CI.
- **A paid Apple team:** removes the app and expiry limits, but is not needed while the shared bundle ID fits.
- **llvm-cov for Kotlin/Native:** no supported compiler flag exists in 2.4.21.

## Risks

- **Out-of-range patches:** Gradle 9.7.1 and AGP 9.3.3 are newer patches than KGP's table lists. The smoke gates are the evidence, and a regression means rolling back to 9.7.0 / 9.3.1.
- **Prerelease tools** (detekt alpha, experimental ABI validation, Kover Beta) may break on upgrade; the holds above track them.
- **Unexposed AES paths:** a phone without ARMv8 crypto extensions would crash if an AES path were ever exposed. The symbol check enforces the invariant.
- **Unmeasured iOS code:** iOS adapter code has no line coverage, so the thin-adapter rule and the on-target test list carry that risk.
- **Free signing:** expiry and per-device limits can interrupt lab runs.

## Migration

None: greenfield. Scaffolding the build applies this baseline. Bumping the Constitution to 4.0.0 changes no existing code.

## Amendments

2026-11-08, testing architecture ([ADR 0017](0017-testing-architecture.md)):

- **libsodium build:** libsodium and the JNI shim are also built for the host (macOS arm64, Linux x64) from the same verified tarball and flags. The build is test-only, never published, and cached by the tarball's sha256.
- **Test tools:** Jazzer 0.30.0, as the standalone `jazzer` driver, runs coverage-guided fuzzing of the decoders in a scheduled job.
- **CI:**
  - Pull requests: docs gates, detekt, ABI checks, JVM and Robolectric tests with Kover, and iOS simulator tests. Target: at most 15 min.
  - Scheduled: Jazzer, large density meshes, the Rust reference cross-check and a dependency freshness check.
  - Devices: manual only.
  - No automatic retries; every test has a timeout.

2026-11-08, release and versioning ([ADR 0019](0019-release-versioning-and-api-compatibility.md)):

- **ABI validation:** rechecked 2026-11-08; still Experimental, so the hold stays. Dumps cover JVM and klib, with `explicitApi()` strict.
- **CI:** a tag-triggered release workflow on `macos-15`, behind an environment that requires manual approval. It covers Maven Central with `automaticRelease`, the attested XCFramework ZIP, a GitHub Release through `gh`, Dokka on Pages, and the `meshlink-swift` update through a GitHub App token. `actions/attest-build-provenance` and `actions/create-github-app-token` gain register rows when the workflow is written.

2026-11-13, toolchain refresh (0.1.0 map ticket "Which toolchain and build checks does the first build start on?"; research on `research/toolchain-refresh`, smoke test in map ticket "Does Kotlin 2.4.21 build with Xcode 27.2, and do its builds run on iOS 15 and 16 phones?"):

- **Facts:**
  - Kotlin 2.4.21 is still the newest stable. It lists Gradle up to 9.7.0, AGP up to 9.3.1 and Xcode 26.4; Gradle 9.8–9.9 and AGP 9.4–9.5 are outside that range.
  - Kotlin 2.4.21 with SKIE 0.10.16 and libsodium 1.0.22 builds with Xcode 27.2 for iOS 15.0, without a Kotlin Xcode warning, and the app runs on iOS 15.8.8, 16.7.16, 17.6.1, 18.7.8 and 27.2 with every vector matching.
  - Xcode 27 debugs only iOS 17+ devices, but libimobiledevice installs, launches and pulls results on iOS 15/16. Apple supports Xcode 16.4 only up to macOS Tahoe 26.1.x.
  - No generally available runner image has Xcode 27: `xcode-27` is a preview. `macos-26` is generally available and ships Xcode 26.4.1 (image 20261102).
  - detekt 2.0 is not stable, Kover is still Beta, and KGP's ABI validation is still Experimental.
- **Hold Kotlin 2.4.21, Gradle 9.7.1 and AGP 9.3.3.** Moving to Kotlin 2.5.x needs a stable release, a KGP compatibility-table entry and a SKIE release that supports it (touchlab/SKIE#213).
- **Stable updates:** Dokka 2.3.0 (fixes CVEs in its bundled libraries), Spotless 8.11.0, Rust 1.100.0.
- **Robolectric 4.18**, released the day of this decision: the scaffold ticket proves it on SDK 26 and 37 and checks whether the two JVM flags are still needed; if it fails, the pin returns to 4.17.
- **Xcode:**
  - Local builds use Xcode 27.2; Xcode 16.4 is no longer required. iOS 15/16 lab phones use libimobiledevice (`docs/agents/lessons.md`).
  - Kotlin's Xcode compatibility warning is not suppressed (`kotlin.apple.xcodeCompatibility.nowarn` stays unset).
  - CI: see the CI section above. Everything moves to Xcode 27 once a generally available macOS 27 image and a Kotlin release that lists Xcode 27 both exist.
- **Unchanged:** compileSdk 37 (not the 37.x minor SDKs until a 37.x API is needed), NDK 30.0.16248370, and the E2 holds on detekt 2.0.0-alpha.6, Kover 0.9.11 and KGP ABI validation.
- **Dependency direction** ([ADR 0011](0011-modules-toggles-and-presets.md) decision 2): a check in `build-logic/` with no new dependency.
- **Rejected:** Gradle 9.9 and AGP 9.5 (outside KGP's range); Xcode 16.4 in CI (no longer matches local builds and is unsupported on macOS 27); every job on the `xcode-27` preview image (not generally available, and Kotlin 2.4.21 doesn't list Xcode 27); modules-graph-assert (a new dependency with partial KMP support).

2026-11-14, quality gates (0.1.0 backlog ticket "Gate the build with Kover, detekt, Spotless, ABI validation and Dokka"):

- **detekt test code:** detekt 2.0.0-alpha.6 exempts test code from 15 rules (for example `MagicNumber`) by directory name, but its list predates AGP 9's `androidHostTest` and the KMP hierarchy (`appleTest`, `nativeTest`). `config/detekt/detekt.yml` widens those excludes to `**/*Test/**`; production source sets keep every rule, and nothing is baselined or suppressed.
- **ABI dumps:** KGP writes `<module>/api/android/<module>.api` and `<module>/api/<module>.klib.api`. `checkChangelog` treats any `api/**/*.api` change as an ABI change.
- **Test timeouts:** see the Robolectric line above and the ADR 0017 amendment of the same date.

2026-11-14, Robolectric proof (0.1.0 backlog ticket "Prove Robolectric 4.18 on SDK 26 and 37"):

- **Robolectric 4.18 stays.** `RobolectricHarnessTest` passes on SDK 26 and 37 ten runs in a row, with outbound network blocked by `sandbox-exec` and the `~/.m2` android-all cache moved aside.
- **android-all pins:** `8.0.0_r4-robolectric-r1-i7` (SDK 26) and `17-robolectric-16653203-i7` (SDK 37), the versions Robolectric 4.18's `DefaultSdkProvider` asks for. The `meshlink.robolectric` plugin resolves each in its own Gradle configuration and passes the jar paths through the `robolectric-deps.properties` system property, which Robolectric reads before any Maven fetch. Without it, the sandboxed run fails with `Failed to fetch maven artifact org.robolectric:android-all-instrumented:8.0.0_r4-robolectric-r1-i7`.
- **JVM flags dropped:** with both `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` and `--add-opens=java.base/java.io=ALL-UNNAMED` removed, both SDKs pass and print no warning. The host-test JVM is the JDK 21 toolchain, whether Gradle itself runs on JDK 21 or 25. A flag returns only with a failing run that needs it.
- **Timeouts:** a JUnit `Timeout` rule fires under Robolectric on both SDKs (a seeded 1 s limit against a 3 s sleep failed with `TestTimedOutException`).
