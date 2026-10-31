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
