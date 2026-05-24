# AGP 9 migration plan for MeshLink build modules

Status: Implemented on `main`

This page is now a concise maintenance summary. The migration itself has
already landed, and the full execution record lives in
[agp-9-migration-plan-history.md](agp-9-migration-plan-history.md).

## What changed

The AGP 9 migration did more than bump versions. It changed module shape:

| Module | Current role |
|---|---|
| `:meshlink` | KMP library using `com.android.kotlin.multiplatform.library` |
| `:meshlink-reference` | Shared KMP UI/state/framework-export module |
| `:meshlink-reference:android-app` | Android application entry point |
| `:meshlink-proof:android:meshlink-proof-android-app` | Pure Android app using AGP built-in Kotlin |

## Current verified toolchain

- Gradle `9.5.0`
- AGP `9.2.1`
- Kotlin `2.3.21`
- Compose Multiplatform `1.11.0`

## Invariants maintainers should preserve

Unless a change intentionally replaces the current build shape, keep these
invariants intact:

- `:meshlink` and `:meshlink-reference` stay on the AGP 9 KMP Android library
  plugin
- `:meshlink-reference:android-app` stays the Android application entry point
- `:meshlink-reference` remains the iOS framework-export anchor
- `:meshlink-reference:localCheck`, `:meshlink-reference:installDebug`, and
  `:meshlink-reference:connectedDebugAndroidTest` stay available as the stable
  contributor-facing compatibility surface unless scripts and docs are migrated
  together

## Verification

After Gradle, Android plugin, or module-shape changes, run:

- `./gradlew checkAgp9Invariants` for the invariant guard
- `./scripts/run-agp9-verification.sh` for the fuller retained verification
  bundle

## Why this document is short now

The original RFC was written as a migration plan and execution record. That was
useful while the migration was in flight, but it made the day-to-day reference
harder to scan once the work had landed.

The current reading path is:

1. use this page for the current build shape and maintenance rules
2. use the historical record only when you need the original migration order,
   split rationale, or detailed patch plan
