# 0005: Toolchain and dependency baseline

Status: accepted (2026-11-01; ticket "What is the toolchain and dependency baseline?"). Version: 1.

## Context

MeshLink is a Kotlin Multiplatform library for Android (minSdk 26) and iOS 15+. Xcode 16.4 is the only Apple toolchain (hard constraint, ticket "Is the device lab ready?"). [ADR 0004](0004-crypto-provider-libsodium.md) adds a libsodium 1.0.22 native build and a test-only Rust reference. E1–E3 require pinned, mutually compatible, stable releases, and every dependency needs a rationale.

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
| Kotlin (KGP, compiler, stdlib) | 2.4.21 | Builds with Xcode 16.4 |
| Gradle | 9.7.1 | Newest patch in the minor that KGP lists |
| AGP + `com.android.kotlin.multiplatform.library` | 9.3.3 | Newest patch in the minor that KGP lists |
| Gradle daemon JDK / compile toolchain / test runtime | 25 / 21 / 21 | |
| compileSdk / minSdk | 37 / 26 | |
| Xcode / iOS deployment target | 16.4 / 15.0 | |
| Android NDK | 30.0.16248370 | Used for the libsodium build and the JNI shim |
| kotlinx-coroutines | 1.11.0 | |
| SKIE | 0.10.16 | |
| Kover | 0.9.11 | |
| Spotless + ktlint | 8.10.4 + 1.8.0 | |
| detekt | 2.0.0-alpha.6 | E2 hold |
| Dokka | 2.2.0 | |
| vanniktech maven-publish | 0.37.0 | |
| Robolectric / JUnit 4 / androidx.test core | 4.17 / 4.13.2 / 1.7.0 | Test only |
| libsodium | 1.0.22 | ADR 0004 |
| Rust (test-only reference) | 1.99.0 | |

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
  | Kover 0.9.11 | Beta | A stable release | |

- **SKIE:** analytics upload disabled (`skie { analytics { disableUpload.set(true) } }`). The Swift API shape belongs to the public-API ticket.
- **Robolectric:** `Test` tasks carry the two JVM flags above plus a per-test timeout.
- **Native bundle ID:** set explicitly through `binaryOption("bundleId", …)`.

**libsodium build:**
- Unmodified upstream `dist-build` scripts, `-Os`, `--enable-minimal`, with the tarball's sha256 and minisign signature verified in CI.
- The iOS XCFramework build runs in full, with its output cached in CI and keyed by the tarball's sha256.
- Keep the upstream `-march=armv8-a+crypto`. **Invariant:** the shim never exposes AES-GCM, AEGIS, ipcrypt or any other API whose hardware path is selected by libsodium's compile-time CPU detection. A CI check fails if the shim's exported or imported symbol set includes an `aes`, `aegis` or `ipcrypt` function.

**Coverage:**
- T3 as amended by Constitution 4.0.0: Kover measures `commonMain` and `androidMain` at 100% line and branch coverage.
- `iosMain` holds only thin platform adapters. Each one is listed in a CI-named file and exercised by on-target tests.

**Test-only Rust reference** (`tools/reference/`; never published, never linked into an artifact):
- `rust-toolchain.toml` pins Rust 1.99.0, and `Cargo.lock` is committed.
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
- macOS jobs run on `macos-15` arm64 with Xcode 16.4 selected explicitly.
- The repository has no remote yet. Creating it is an external change and needs explicit approval.

**Publishing:**
- vanniktech 0.37.0 is configured, with `publishToMavenLocal` as the local proof.
- Maven Central credentials, the GPG key and XCFramework/SwiftPM hosting belong to the release process.

**Device-lab signing:**
- One shared lab bundle ID per free team: `ch.trancee.lab.host` plus its UI-test runner. MeshLink, cairn and sqisign all use it, so every install replaces the previous app and uses at most 2 of a device's 3 free slots.
- A local script re-signs within the 7-day expiry before lab runs.
- If the free tier keeps blocking device tests, revisit a paid team.
- The libsodium iPhone benchmark runs on the iPhone SE (2016, iOS 15.8) as low-end T9 evidence.

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
