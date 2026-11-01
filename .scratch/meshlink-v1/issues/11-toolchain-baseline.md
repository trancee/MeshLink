# What is the toolchain and dependency baseline?

Type: grilling
Status: resolved
Blocked by: 05

## Question

Fix the version tuple, plugins, static analysis, formatting, coverage, API compatibility, docs tooling, publishing and CI workflow set, each dependency with an E3 rationale, recorded in a dependency register.

## Scope additions (from research, 2026-10-31)

- E2 holds needed: detekt (2.0 is alpha), ABI validation (Kotlin built-in experimental vs BCV maintenance mode), possibly SKIE lagging Kotlin.
- How is T3 (100% coverage) met for iOS code that Kover can't measure (llvm-cov vs thin CI-excluded iOS shims)?
- AGP 9.3.x in range vs 9.5.0; JDK 25 vs 21 (Robolectric on 25 unverified); SwiftPM Package.swift location and XCFramework hosting.
- Requires a smoke build proving the tuple (vanniktech 0.37.0, Dokka 2.2.0, ktlint 1.8.0 with Kotlin 2.4.21 + AGP 9.3.x).
- **Hard constraint (user, 2026-10-31):** Xcode 16.4 must work ([device lab](06-device-lab-readiness.md)). Smoke-build Kotlin 2.4.21 (tested with Xcode 26.4) against Xcode 16.4 first; fall back to Kotlin 2.2.20 (tested with 16.4; AGP ≤ 8.11.1, Gradle ≤ 8.14) only if it fails. Record the conflict with "most modern toolchain" and an E2 hold if needed. Also verify an XCFramework built with Xcode 16.4 works in apps built with Xcode 26/27.
- Include Xcode 16.4 build and debug on iOS 27 (iPhone 12 mini) in the smoke build.
- From [constitution amendments](07-constitution-amendments.md): refresh the version snapshot in root `KOTLIN.md` (dated 2026-10-05; it says SKIE support ends at Kotlin `2.4.10`, which conflicts with the `2.4.21` candidate). Verify whether any CI tool measures Kotlin/Native coverage; if none does, bring a T3 amendment proposal (MAJOR) back to the user.
- From the [crypto provider decision](10-rust-approval.md) (ADR 0004):
  - Add the libsodium 1.0.22 build to the tuple: pinned and signature-verified source, upstream `dist-build` scripts, NDK/CMake JNI shim, cinterop def, static linking with hidden symbols, packaging inside the XCFramework.
  - Measure arm64 size against the ≤ 1 MB per ABI gate.
  - Pin the test-only Rust reference toolchain and crates, each with an E3 rationale (never published).
  - Run primitive microbenchmarks on the oldest Android and iOS lab phones as T9 evidence.
- From libp2p alignment (ADR 0002 amendment): the test-only Rust reference should cross-check `XXhfs` against snow with ML-KEM-768 `hfs` (snow#210, open on 2026-11-01). If it's unmerged at baseline time, ask before pinning a fork or commit.

## Answer

Agreed with the user in two grilling rounds on 2026-11-01, backed by two throwaway smoke builds. Recorded in `docs/adr/0005-toolchain-and-dependency-baseline.md`, `docs/reference/dependencies.md` and Constitution 4.0.0.

- **Tuple:** Kotlin 2.4.21 (works with Xcode 16.4; the 2.2.20 fallback is unneeded), Gradle 9.7.1 and AGP 9.3.3 (newest patches in the minors KGP lists), JDK 25 daemon / 21 toolchain and tests, compileSdk 37 / minSdk 26, iOS 15.0, NDK 30.0.16248370.
- **Tools:**
  - SKIE 0.10.16, analytics upload off. Its tag adds Kotlin 2.4.21; the intro page is stale.
  - Kover 0.9.11, Spotless 8.10.4 + ktlint 1.8.0, Dokka 2.2.0, vanniktech 0.37.0, coroutines 1.11.0, Robolectric 4.17 (SDK 37 JVM flags plus a timeout).
  - E2 holds on detekt 2.0.0-alpha.6 (per-source-set tasks in `check`), KGP built-in `abiValidation` and Kover Beta.
- **Targets:** Android `arm64-v8a`, `armeabi-v7a`, `x86_64`; iOS `iosArm64` and `iosSimulatorArm64`.
  - The user challenged the simulator slice: BLE doesn't work there.
  - Kept because host apps must still build for the simulator (MeshLink reports "Bluetooth unsupported") and non-radio tests run there in CI. Radio tests run on hardware only.
- **T3:** no Kotlin/Native coverage tool exists, so the Constitution moves to 4.0.0 (MAJOR). 100% coverage applies on targets with a CI tool; iOS code is limited to thin, CI-listed adapters with on-target tests.
- **libsodium:** unmodified upstream scripts, `-Os`, `--enable-minimal`, sha256 + minisign checked in CI, and the iOS build cached by the tarball's hash.
  - The upstream `+crypto` flag stays, with the invariant that AES, AEGIS and ipcrypt are never exposed, enforced by a CI symbol check.
  - No crypto-less arm64 phone exists in the lab, so the risk is inferred from disassembly only.
- **Rust reference:** Rust 1.99.0 plus hpke 0.15.1, ml-kem 0.3.4, dalek 3.0.0, chacha20poly1305 0.11.0, sha2 0.11.1, hkdf 0.13.0 and snow at the snow#210 head `dc18c06`. Test-only, never published.
- **Register and CI:** the catalog is the only version source; `docs/reference/dependencies.md` holds the E3 rows, and a CI check fails on a missing row. CI is GitHub Actions with SHA-pinned actions; the `macos-15` arm64 runner defaults to Xcode 16.4. Creating the repository remote needs approval.
- **Signing:** both lab teams are free (7-day profiles, 3 apps per device). One shared lab bundle ID (`ch.trancee.lab.host`) is used across projects. The libsodium iPhone benchmark on the iPhone SE (2016) is still pending as T9 evidence.
- **Publishing:** credentials, GPG and XCFramework hosting are deferred to the release process.
