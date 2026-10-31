# What is the most modern stable KMP toolchain in 2026?

Type: research
Status: resolved

## Question

Verify current stable versions and compatibility of: Kotlin, AGP (KMP Android library plugin), Gradle, JDK; Swift export vs SKIE; XCFramework + SwiftPM distribution tooling; CocoaPods trunk status; Maven Central publishing plugin; Robolectric; Kover; detekt (2.x status); Spotless/ktlint; Dokka 2; binary/ABI compatibility validation (Kotlin built-in vs plugin); UniFFI bindings for KMP (Ubique plugin vs Gobley); GitHub Actions latest releases. Note blockers and maintenance health.

## Answer

Verified on 2026-10-31 from primary sources. This is a candidate baseline, not a decision.

- **Core:** Kotlin 2.4.21 + AGP 9.3.x + Gradle 9.7.0 is the newest combination that every vendor documents as supported. AGP 9.5.0 and Gradle 9.8.2 are out but fall outside KGP's fully supported range. JDK 25 LTS for the Gradle daemon.
- **Android:** the `com.android.kotlin.multiplatform.library` plugin, with compileSdk 37 and minSdk 26.
- **iOS:** SKIE 0.10.16 supports Kotlin 2.4.21. Swift export is Alpha and works only with direct Xcode integration, so it cannot build an XCFramework. Distribute a KGP XCFramework through a `Package.swift` `binaryTarget`. CocoaPods trunk becomes read-only on 2026-12-02.
- **Xcode:** Kotlin 2.4 is tested with Xcode 26.4. Xcode 27.2 keeps iOS 15.0 as the minimum deployment target but debugs only iOS 17+ devices. This Mac has only Xcode 16.4 (on macOS 27), which blocks local iOS builds.
- **Publishing:** vanniktech 0.37.0 to the Central Portal (OSSRH shut down in 2025).
- **Tests and docs:** Robolectric 4.17 (SDK 26 and 37); Kover 0.9.11, which cannot measure iOS/native code, so T3 needs a strategy; Spotless 8.10.4 with ktlint 1.8.0; Dokka 2.2.0.
- **Non-stable tools:** detekt 2.x is still alpha (2.0.0-alpha.6), and 1.23.8 is pinned to Kotlin 2.0.21. ABI validation is Experimental in KGP, and BCV 0.18.2 is in maintenance mode. Both need E2 holds.
- **UniFFI (only if Rust is approved):** use Ubique 1.3.2 (UniFFI 0.32). Gobley is stale.

GitHub Actions versions with pinned SHAs are in the findings file.

Findings: branch `research/kmp-toolchain-2026`, file `docs/research/2026-10-31-kmp-toolchain-2026.md`.
