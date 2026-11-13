# Which toolchain and build checks does the first build start on?

Type: grilling
Status: resolved
Blocked by: 01, 10

## Question

Decide the version tuple, the Apple toolchain (Xcode 16.4 or 27.x, locally and in CI), the E2 holds, and the dependency-direction tool; amend ADR 0005 (and ADR 0011 for the tool) and the dependency register.

## Context

- Inputs: [ticket 01](01-toolchain-refresh.md).

## Answer

Resolved 2026-11-13 (grilling; every recommendation accepted). Recorded in ADR 0005 v4, ADR 0011 v9 and ADR 0019 v2.

1. **Hold Kotlin 2.4.21, Gradle 9.7.1, AGP 9.3.3.** Move to Kotlin 2.5.x only when it is stable, KGP's table lists it, and SKIE supports it.
2. **Stable updates:** Dokka 2.3.0, Spotless 8.11.0, Rust 1.100.0.
3. **Robolectric 4.18:** proven by the scaffold ticket on SDK 26 and 37, which also checks whether the two JVM flags can go; otherwise back to 4.17.
4. **Local Xcode 27.2** (proven by [ticket 10](10-xcode27-smoke.md)); the Xcode 16.4 hard constraint is gone; iOS 15/16 phones use libimobiledevice. Kotlin's Xcode warning is not suppressed.
5. **CI Xcode:** required checks and the release workflow on `macos-26` with Xcode 26.4.1 (generally available; the Xcode Kotlin 2.4.21 lists), plus a scheduled non-required job on the `xcode-27` preview with Xcode 27.2. Everything moves to Xcode 27 once a generally available macOS 27 image and a Kotlin release listing Xcode 27 exist.
6. **Dependency direction:** a custom check in `build-logic/` with no new dependency, wired into `check`, with one failing-case test.
7. **Unchanged:** compileSdk 37, NDK 30 (installed explicitly in CI), and the E2 holds on detekt 2.0.0-alpha.6, Kover 0.9.11 and KGP ABI validation.

Also updated: `KOTLIN.md`, the dependency register, and version numbers removed from ADR 0006 decision 8 and ADR 0017 decision 5 (ADR 0005 owns the pins).
