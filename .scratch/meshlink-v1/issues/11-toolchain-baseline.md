# What is the toolchain and dependency baseline?

Type: grilling
Status: open
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
