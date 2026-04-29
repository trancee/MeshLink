# Workflow: Set Up a KMP Module

<required_reading>
**Read these reference files NOW:**
1. references/project-structure.md
2. references/gradle-config.md
</required_reading>

<process>
## Step 1: Determine Targets

Identify which platforms the module needs to support. Common configurations:

- **Mobile only:** `androidTarget()`, `iosArm64()`, `iosSimulatorArm64()`
- **Mobile + Desktop:** add `jvm()` to the above
- **Mobile + Web:** add `wasmJs { browser() }` or `js(IR) { browser() }`
- **Library (all platforms):** all of the above

For iOS, you almost always need both `iosArm64` (device) and `iosSimulatorArm64` (simulator on Apple Silicon). Add `iosX64` only for Intel Mac development.

## Step 2: Create the Gradle Build File

Write `build.gradle.kts` with the multiplatform plugin and target declarations:

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.android.library") // only if targeting Android
}

kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    // Add other targets as needed

    sourceSets {
        commonMain.dependencies {
            // Shared dependencies
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
```

## Step 3: Create Source Directories

```
src/
├── commonMain/kotlin/com/example/module/
├── commonTest/kotlin/com/example/module/
├── androidMain/kotlin/com/example/module/
├── iosMain/kotlin/com/example/module/
└── jvmMain/kotlin/com/example/module/  (if JVM target declared)
```

Only create directories for declared targets. The `iosMain` intermediate source set covers both `iosArm64` and `iosSimulatorArm64` — you rarely need separate directories for those.

## Step 4: Add to Settings

In `settings.gradle.kts`:

```kotlin
include(":module-name")
```

## Step 5: Sync and Verify

Run Gradle sync to verify the configuration compiles:

```bash
./gradlew :module-name:compileKotlinMetadata
```

This compiles the common metadata and validates the source set hierarchy.

## Step 6: Add Platform Abstractions (if needed)

If the module needs platform-specific APIs, define `expect` declarations in `commonMain` with `actual` implementations in platform source sets. See the `add-expect-actual` workflow for details.
</process>

<success_criteria>
- [ ] Targets declared in `build.gradle.kts`
- [ ] Source directories created for `commonMain` and each platform source set
- [ ] Module included in `settings.gradle.kts`
- [ ] `./gradlew :module:compileKotlinMetadata` passes
- [ ] Test source sets mirror main source sets
</success_criteria>
