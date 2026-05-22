# AGP 9 Migration Plan for MeshLink Build Modules

Status: Implemented on `main` (historical plan and execution record)

## Implementation status

The migration described in this document has now landed on `main` in these
commits:

- `7140e2d` `refactor(reference): split android app from shared module`
- `931a987` `chore(build): upgrade to AGP 9 and migrate android plugin usage`
- `0ea7b5e` `chore(reference): remove AGP 8 lint override`

Current verified toolchain on `main`:

- Gradle `9.5.0`
- AGP `9.2.1`
- Kotlin `2.3.21`
- Compose Multiplatform `1.11.0`

Current module shape on `main`:

- `:meshlink` → KMP library using `com.android.kotlin.multiplatform.library`
- `:meshlink-reference` → shared KMP UI/state/framework-export module
- `:meshlink-reference:android-app` → Android entry-point module
- `:meshlink-proof:android:meshlink-proof-android-app` → pure Android app using AGP built-in Kotlin

The remaining sections are kept as a historical execution record showing the
migration order, rationale, and file-level split plan from the pre-AGP-9 state.

## Reader and intended outcome

This plan is for maintainers working on the Gradle and Android build surface.
After reading it, a maintainer should be able to execute the AGP 9 migration on
one feature branch without rediscovering the module boundaries, upgrade order,
or verification strategy.

Assumption for this plan: **focused** means the minimum migration required for
AGP 9 compatibility, not a full JetBrains-style app-per-platform restructure.

## Why this is not a version-bump-only change

AGP 9 no longer allows the Android application or Android library plugins to
coexist with `org.jetbrains.kotlin.multiplatform` in the same module.
MeshLink currently has both affected shapes:

| Module | Current shape | AGP 9 migration path | Why |
|---|---|---|---|
| `:meshlink` | KMP + Android library | Path A | Must replace the Android library plugin with the AGP 9 KMP Android library plugin. |
| `:meshlink-reference` | KMP + Android application | Path B | Must split the Android app entry point out of the shared KMP module. |
| `:meshlink-proof:android:meshlink-proof-android-app` | Pure Android application + `org.jetbrains.kotlin.android` | Built-in Kotlin cleanup | AGP 9 wants built-in Kotlin for pure Android modules. |

This means the migration is a **module-shape change**, not just an AGP/Gradle
version bump.

## Decision summary

The minimum-risk AGP 9 plan for this repository is:

1. Keep `:meshlink-reference` as the shared KMP module so the iOS framework
   export and Xcode integration stay anchored to the same module name.
2. Add a new nested Android app module named
   `:meshlink-reference:android-app` for the Android launcher activity,
   application manifest, and instrumentation surface.
3. Migrate `:meshlink` to the AGP 9 KMP Android library plugin.
4. Migrate `:meshlink-proof:android:meshlink-proof-android-app` off
   `org.jetbrains.kotlin.android` and onto AGP 9 built-in Kotlin in the same
   branch.
5. Preserve contributor-facing commands where practical by keeping thin
   delegating tasks or scripts for the reference app instead of forcing a
   flag day for every command and document.

## Non-goals

This plan intentionally does **not** include:

- renaming `:meshlink-reference` to `:shared`
- moving the iOS host project out of its current location
- splitting desktop or web entry points, because this repository does not have
  them
- changing MeshLink public API shape, wire format, transport behavior, or
  runtime dependency policy
- redesigning proof or benchmark flows beyond the build changes required by
  AGP 9

## Target end state

The repo shape after migration should be:

| Module | Target role |
|---|---|
| `:meshlink` | Shared KMP library using the AGP 9 KMP Android library plugin |
| `:meshlink-reference` | Shared KMP UI/state/framework-export module for Android and iOS |
| `:meshlink-reference:android-app` | Android app entry point for the reference app |
| `:meshlink-proof:android:meshlink-proof-android-app` | Pure Android app using AGP 9 built-in Kotlin |

Key consequence: the iOS host project should continue to build the
`ReferenceAppShared` framework from `:meshlink-reference`, while Android app
installation and connected-device tasks move to
`:meshlink-reference:android-app`.

## What moves and what stays in the reference app split

### Moves into `:meshlink-reference:android-app`

These are Android **app entry-point** concerns and should leave the shared KMP
module:

- the launcher `MainActivity`
- the Android manifest containing `<application>` and the launcher activity
- Android-only app metadata such as the app-name resource
- Android instrumented smoke and workflow tests that launch the activity
- Android app verification tasks such as install, lint, and connected-device
  test entry points

### Stays in `:meshlink-reference`

These are Android **platform glue for shared code** and should remain in the
shared KMP module:

- shared Compose UI, navigation, controller, retention, export, and timeline
  logic
- Compose Multiplatform resources
- iOS framework export and Xcode integration
- Android platform service factories and platform adapters
- Android readiness explanation and Android document-store implementations
- shared tests and iOS tests

This boundary keeps Android app bootstrapping separate from shared runtime
logic while minimizing churn in the iOS host integration.

## Execution checklist (build-green order)

The phase plan below is still the right logical breakdown, but the repository
should be migrated in **build-green commit order**, not by naïvely bumping AGP
first.

- [ ] **C01: Split the reference Android app from the shared KMP module while staying on the current AGP 8 toolchain** `depends:[]`
  > After this: `:meshlink-reference` is a shared KMP + Android library module, `:meshlink-reference:android-app` owns the Android launcher and instrumentation surface, and iOS still builds from `:meshlink-reference`.
- [ ] **C02: Upgrade the toolchain and migrate all remaining Android plugin usage in one coordinated AGP 9 commit** `depends:[C01]`
  > After this: Gradle 9 + AGP 9 are in place, `:meshlink` and `:meshlink-reference` use the AGP 9 KMP Android library plugin, and the proof app no longer applies `org.jetbrains.kotlin.android`.
- [ ] **C03: Remove AGP 8 workarounds and normalize scripts, hooks, and docs around the final task graph** `depends:[C02]`
  > After this: the lint override, reference-app command surface, and contributor docs all match the AGP 9 module layout.
- [ ] **C04: Run the final full-repo verification bundle on the AGP 9 branch** `depends:[C03]`
  > After this: the migration branch has fresh end-to-end build evidence instead of only phase-local checks.

## Exact first migration commit

### Commit message

`refactor(reference): split android app from shared module`

### Why this must be first

This repository cannot take an AGP 9 version bump as its first migration commit
without breaking configuration immediately:

- `:meshlink-reference` is currently the only **KMP + Android application**
  module, which AGP 9 forbids outright.
- `:meshlink` is already a **KMP + Android library** module, so once the
  reference app is split, the remaining AGP 9 work becomes a coordinated
  library-plugin migration instead of an application split plus plugin
  migration at the same time.
- The split can be done safely on the current verified toolchain
  (`Gradle 8.11.1`, `AGP 8.9.2`), which keeps the branch green while reducing
  the later AGP 9 blast radius.

### Exact scope of the first commit

#### Edit existing files

| File | Action | Exact patch intent |
|---|---|---|
| `settings.gradle.kts` | edit | Add `include(":meshlink-reference:android-app")` and `project(":meshlink-reference:android-app").projectDir = file("meshlink-reference/android-app")`. Do not rename the shared module. |
| `meshlink-reference/build.gradle.kts` | edit | Switch `alias(libs.plugins.android.application)` to `alias(libs.plugins.android.library)`. Remove `applicationId`, `targetSdk`, `versionCode`, `versionName`, `testInstrumentationRunner`, and `installation`. Remove `androidMain` dependency on `libs.androidx.activity.compose`. Remove `androidInstrumentedTest` dependencies because those tests move to the new app module. Keep `namespace`, `compileSdk`, `minSdk`, `buildFeatures { compose = true }`, shared/iOS configuration, and current iOS framework export unchanged. |
| `meshlink-reference/build.gradle.kts` | edit | Add task-compatibility glue so existing command surfaces keep working in commit 1: make `ktfmtFormat` depend on `:meshlink-reference:android-app:ktfmtFormat`; make `check` depend on `:meshlink-reference:android-app:check`; make `build` depend on `:meshlink-reference:android-app:assemble`; keep `localCheck` but add the new app-module verification dependency; add alias tasks `installDebug` and `connectedDebugAndroidTest` that delegate to the new app module. |
| `build.gradle.kts` | no change by default | Leave root Kover aggregation untouched in C01 unless verification proves the new app module must join the aggregate immediately. The goal of C01 is the module split, not coverage-policy expansion. |
| `.githooks/pre-commit` | no change | Do not edit hooks in C01. The shared-module compatibility tasks above are what keep the current hook behavior truthful. |
| `.githooks/pre-push` | no change | Same rationale as pre-commit: preserve the existing `:meshlink-reference:localCheck` surface in the shared module for now. |
| `scripts/run-reference-local-check.sh` | no change | Keep the script stable by preserving `:meshlink-reference:localCheck` as a delegating shared-module task. |
| `meshlink-reference/scripts/run_headless_reference_live_proof.py` | no change | Keep `:meshlink-reference:installDebug` working via a shared-module alias task so the harness does not need churn in C01. |
| `meshlink-reference/README.md` | no change | Keep `:meshlink-reference:connectedDebugAndroidTest` valid through a shared-module alias task. |
| `docs/how-to/evaluate-meshlink-with-the-reference-app.md` | no change | Keep `:meshlink-reference:installDebug` valid through a shared-module alias task. |
| `CONTRIBUTING.md` | no change | Preserve `./scripts/run-reference-local-check.sh` and `:meshlink-reference:build` semantics through shared-module compatibility tasks in C01. |
| `docs/reference/contributor-reference.md` | no change | Same rationale as `CONTRIBUTING.md`; defer doc churn until the AGP 9 toolchain is in place. |

#### Add new files

| File | Action | Exact patch intent |
|---|---|---|
| `meshlink-reference/android-app/build.gradle.kts` | add | Create a pure Android application module on the current AGP 8 toolchain. Apply `android.application`, `kotlin.android`, `kotlin.compose`, `detekt`, and `ktfmt`. Set a **distinct namespace** such as `ch.trancee.meshlink.reference.androidapp` while keeping `applicationId = "ch.trancee.meshlink.reference"`. Keep `compileSdk = 36`, `minSdk = 29`, `targetSdk = 35`, `versionCode = 1`, `versionName = "0.1.0"`, `testInstrumentationRunner`, Java 17, `installation { installOptions += "-g" }`, and `buildFeatures { compose = true }`. Add `implementation(project(":meshlink-reference"))`, `implementation(libs.androidx.activity.compose)`, and the existing Android instrumentation-test dependencies. |
| `meshlink-reference/android-app/src/main/AndroidManifest.xml` | add via move | Rehome the current launcher manifest unchanged except for any package/namespace-sensitive cleanup required by the new app module namespace choice. |
| `meshlink-reference/android-app/src/main/kotlin/ch/trancee/meshlink/reference/MainActivity.kt` | add via move | Move the current `MainActivity` unchanged. Keep its package and automation intent helpers stable so tests and scripts do not need rewriting in C01. |
| `meshlink-reference/android-app/src/main/res/values/strings.xml` | add via move | Move the app-name resource unchanged. |
| `meshlink-reference/android-app/src/androidTest/kotlin/ch/trancee/meshlink/reference/ReferenceAppAndroidSmokeTest.kt` | add via move | Move unchanged. |
| `meshlink-reference/android-app/src/androidTest/kotlin/ch/trancee/meshlink/reference/ReferenceAppAndroidWorkflowTest.kt` | add via move | Move unchanged. |

#### Move files out of the shared KMP module

Use `git mv` so the history stays readable:

- `meshlink-reference/src/androidMain/AndroidManifest.xml`
  → `meshlink-reference/android-app/src/main/AndroidManifest.xml`
- `meshlink-reference/src/androidMain/kotlin/ch/trancee/meshlink/reference/MainActivity.kt`
  → `meshlink-reference/android-app/src/main/kotlin/ch/trancee/meshlink/reference/MainActivity.kt`
- `meshlink-reference/src/androidMain/res/values/strings.xml`
  → `meshlink-reference/android-app/src/main/res/values/strings.xml`
- `meshlink-reference/src/androidInstrumentedTest/kotlin/ch/trancee/meshlink/reference/ReferenceAppAndroidSmokeTest.kt`
  → `meshlink-reference/android-app/src/androidTest/kotlin/ch/trancee/meshlink/reference/ReferenceAppAndroidSmokeTest.kt`
- `meshlink-reference/src/androidInstrumentedTest/kotlin/ch/trancee/meshlink/reference/ReferenceAppAndroidWorkflowTest.kt`
  → `meshlink-reference/android-app/src/androidTest/kotlin/ch/trancee/meshlink/reference/ReferenceAppAndroidWorkflowTest.kt`

#### Keep these files in place

These remain in `:meshlink-reference` and should not move in C01:

- `meshlink-reference/src/androidMain/kotlin/ch/trancee/meshlink/reference/platform/AndroidPlatformServices.kt`
- `meshlink-reference/src/androidMain/kotlin/ch/trancee/meshlink/reference/platform/AndroidReadinessExplainer.kt`
- `meshlink-reference/src/androidMain/kotlin/ch/trancee/meshlink/reference/platform/AndroidReferenceDocumentStore.kt`
- everything under `meshlink-reference/src/commonMain/`
- everything under `meshlink-reference/src/commonTest/`
- everything under `meshlink-reference/src/iosMain/` and `meshlink-reference/src/iosTest/`
- everything under `meshlink-reference/ios/`

#### Explicit deferrals for C01

Do **not** do these in the first commit:

- change AGP, Gradle, or Kotlin versions
- remove `org.jetbrains.kotlin.android` from the proof app
- rewrite contributor docs to reference `:meshlink-reference:android-app` directly
- change the iOS Xcode project or `embedAndSignAppleFrameworkForXcode`
- add AGP 9-specific DSL or plugin aliases

### Exact verification for the first commit

Run these commands after the last edit in the commit:

```bash
./scripts/run-reference-local-check.sh
./gradlew --console=plain :meshlink-reference:android-app:check verifyDocs
```

Expected result for this first commit:

- the branch is still on `Gradle 8.11.1` and `AGP 8.9.2`
- `:meshlink-reference` is no longer an Android application module
- Android install/test entry points now come from
  `:meshlink-reference:android-app`
- iOS framework export still comes from `:meshlink-reference`

## Phase plan

### Phase 0 — Compatibility gate and branch setup

Before editing module shapes, confirm the toolchain and plugin matrix on a
feature branch.

### Actions

- Confirm the AGP 9.x, Gradle 9.x, Kotlin 2.3.x, and Compose Multiplatform
  compatibility matrix from the official sources before pinning exact target
  versions.
- Inventory third-party Gradle plugins used here and check AGP 9 compatibility:
  Compose Multiplatform, Detekt, Kover, BCV, ktfmt, and the benchmark plugin.
- Confirm proxy-backed Gradle 9 wrapper download strategy, because earlier
  Gradle distribution fetches through the proxy were slow enough to matter.
- Review `gradle.properties` for AGP-8-era workarounds that should be removed
  or revalidated under AGP 9, especially `android.experimental.lint.version`.

### Exit criteria

- Exact target versions are selected and documented in the branch plan.
- No blocking plugin incompatibility remains unknown.
- The Gradle 9 wrapper download path is known to work, or the cache-preload
  fallback is documented for the branch.

### Phase 1 — Root build and catalog preparation

Make the root build ready for AGP 9 without migrating module behavior yet.

### Actions

- Upgrade the Gradle wrapper to the selected Gradle 9.x version.
- Upgrade AGP to the selected 9.x version.
- Add a version-catalog plugin alias for
  `com.android.kotlin.multiplatform.library`.
- Remove the repository-wide `kotlin-android` plugin alias only in the same
  branch where pure Android modules are migrated to built-in Kotlin.
- Revisit root plugin declarations so they match the new plugin set.

### Exit criteria

- The root catalog and wrapper are ready for the module-by-module migration.
- No module still depends on a removed root plugin alias.

### Phase 2 — Migrate `:meshlink` to the AGP 9 KMP Android library plugin

This is the lower-risk KMP migration and should happen before the reference-app
split.

### Actions

- Replace `com.android.library` with the AGP 9 KMP Android library plugin.
- Move Android-specific DSL from the top-level Android block into the new KMP
  Android library DSL inside `kotlin { ... }`.
- Keep the current JVM and iOS targets unchanged.
- Explicitly enable any Android resources, tests, or Java support required by
  the new plugin instead of relying on AGP 8 defaults.
- Verify how the new plugin maps Android test source sets in this repository.
  The current tree uses KMP-style Android test source sets already, but the
  AGP 9 plugin introduces explicit host/device test concepts and may require
  source-set renames or opt-ins.

### Exit criteria

- `:meshlink` builds and tests successfully on the AGP 9 toolchain.
- API, coverage, and Detekt behavior stay equivalent to the pre-migration
  branch.

### Phase 3 — Split `:meshlink-reference` into shared + Android app modules

This is the mandatory AGP 9 step for the reference app.

### Actions

- Convert `:meshlink-reference` from KMP + Android application into a shared KMP
  library module using the AGP 9 KMP Android library plugin.
- Add a new module named `:meshlink-reference:android-app` for the Android app
  entry point.
- Move the launcher activity, Android manifest, Android app-name resource, and
  Android instrumented tests into `:meshlink-reference:android-app`.
- Keep Android platform services and Android platform adapters in the shared
  KMP module so the shared UI can still build Android-specific behavior.
- Make `:meshlink-reference:android-app` depend on `:meshlink-reference` and
  `androidx.activity:activity-compose`.
- Configure the new Android app module for Compose and AGP 9 built-in Kotlin.
- Keep the iOS host project calling
  `:meshlink-reference:embedAndSignAppleFrameworkForXcode` so the Xcode side
  does not need a second structural migration.

### Preferred compatibility choice

To reduce contributor churn, keep the current shared module name and introduce
thin alias tasks where they materially reduce doc and script breakage.
Examples worth preserving if practical:

- `:meshlink-reference:localCheck` delegating to the new Android app module
  plus the shared iOS/framework checks
- `:meshlink-reference:installDebug` delegating to
  `:meshlink-reference:android-app:installDebug`
- `:meshlink-reference:connectedDebugAndroidTest` delegating to the new app
  module task

This preserves the iOS framework export task name while minimizing Android-side
command churn.

### Exit criteria

- Android app install, lint, and instrumentation tasks run from the new Android
  app module.
- iOS framework export remains anchored to `:meshlink-reference`.
- Shared UI/state code still builds for both Android and iOS.

### Phase 4 — Migrate `:meshlink-proof:android:meshlink-proof-android-app`

The proof app is not structurally blocked by KMP, but it still needs the AGP 9
built-in Kotlin cleanup.

### Actions

- Remove `org.jetbrains.kotlin.android` from the proof app and from the root
  plugin declarations/version catalog.
- Keep or rewrite Kotlin compiler-options configuration in the AGP 9-compatible
  location.
- Verify that proof-app lint and unit-test behavior stays stable under AGP 9.

### Exit criteria

- The proof app no longer applies `org.jetbrains.kotlin.android`.
- The proof app passes its existing `check` surface on the AGP 9 toolchain.

### Phase 5 — Script, hook, and documentation cleanup

After the module migration lands, update the contributor surface.

### Actions

- Update the fast local reference-app verification script to target the new
  app/shared shape.
- Remove the AGP-8-specific lint override if AGP 9 no longer needs it.
- Update contributor commands and verification bundles.
- Update the reference-app README and evaluation guides only where the Android
  task surface changed.
- Keep iOS build instructions stable if the iOS host project still builds from
  the same shared module task.

### Exit criteria

- The documented Android reference-app commands are correct again.
- No script still points at an obsolete application-shaped
  `:meshlink-reference` module.

## Known risks and decision points

### 1. Android test source-set mapping under the new KMP Android library plugin

Current KMP source sets already use Android-specific test trees, but AGP 9
introduces explicit host/device test configuration on the library side. Treat
this as an early checkpoint rather than a surprise discovered halfway through
Phase 2.

### 2. Compose and Android resources in shared KMP modules

The new KMP Android library plugin makes some Android capabilities explicit.
If Android resources or Compose-generated resources are not enabled correctly,
shared Android builds can fail or behave differently than they do today.

### 3. Reference-app command churn

Without alias tasks, every current Android reference-app command changes. That
would force simultaneous updates across hooks, scripts, README flows, and
operator docs. Preserving a small compatibility layer is cheaper than a flag
day if the task graph stays understandable.

### 4. AGP-8-era lint workaround

The current repository carries an AGP 8 lint-version override to stabilize the
Kotlin 2.3.x toolchain. AGP 9 may make that override unnecessary, or it may
become actively incorrect. Treat its removal as part of the migration, not a
postscript.

### 5. Gradle distribution download speed through the proxy

Earlier Gradle distribution downloads through the proxy were reachable but too
slow to be a reliable edit-loop assumption. The migration branch should either
use a preseeded wrapper cache or validate the proxy path before attempting the
full toolchain jump.

## Suggested commit sequence

A clean **build-green** migration history should follow this order instead of
bumping AGP first:

1. `refactor(reference): split android app from shared module`
2. `chore(build): upgrade to AGP 9 and migrate android plugin usage`
3. `docs(contributor): update AGP 9 build and verification guidance`

Notes:

- Commit 2 is intentionally broader than usual because AGP 9 compatibility is
  enforced during configuration. Splitting the toolchain bump from the
  remaining plugin migrations would leave the branch red between commits.
- If AGP 9 reveals a follow-up cleanup that is mechanically separate but still
  build-green, keep it as a narrow `chore(build):` follow-up after commit 2,
  not before it.

## Verification plan

Run phase verification after each module move, not only at the end.

### After Phase 2

- `./gradlew --console=plain :meshlink:build`

### After Phase 3

- shared framework verification for iOS from `:meshlink-reference`
- Android app verification from `:meshlink-reference:android-app`
- if alias tasks are kept, verify the alias entry points too

### After Phase 4

- `./gradlew --console=plain :meshlink-proof:android:meshlink-proof-android-app:check`

### Final branch verification

- `./gradlew --console=plain :meshlink:build :benchmarks:check :meshlink-proof:android:meshlink-proof-android-app:check verifyDocs`
- the reference-app Android verification bundle using either the preserved alias
  script or the new app-module task surface
- iOS host verification through the existing Xcode project if the branch
  changes framework export behavior

## Done criteria

The AGP 9 migration is complete when all of the following are true:

- no KMP module still applies `com.android.application` or `com.android.library`
- no pure Android module still applies `org.jetbrains.kotlin.android`
- `:meshlink-reference` is a shared KMP module and the Android launcher lives in
  `:meshlink-reference:android-app`
- the iOS host project still builds its shared framework successfully
- contributor scripts and docs no longer point at obsolete Android application
  tasks
- the repository verification bundle is green on the AGP 9 toolchain
