---
name: kotlin-binary-compatibility-validator
description: Kotlin Binary Compatibility Validator (BCV) Gradle plugin reference (org.jetbrains.kotlinx.binary-compatibility-validator). Dumps and validates public binary API of Kotlin/JVM libraries. Covers plugin setup, tasks (apiDump to generate .api files, apiCheck wired into build), apiValidation DSL (ignoredPackages/Projects/Classes, nonPublicMarkers, apiDumpDirectory), inputJar for custom builds, recommended workflow (apiDump+commit, apiCheck on CI). Experimental KLib ABI validation. Public API rules (ACC_PUBLIC/PROTECTED classes/members, PublishedApi, lateinit). Incompatible change definitions (name/descriptor/visibility/modifier changes). Also covers the newer experimental ABI validation built into the Kotlin Gradle Plugin itself (`kotlin { abiValidation { } }`, checkKotlinAbi/updateKotlinAbi tasks) as the plugin's eventual successor. Use when setting up BCV, configuring API validation, diagnosing apiCheck failures, or any binary compatibility question.
---

<essential_principles>

**Binary Compatibility Validator (BCV)** — Gradle plugin by JetBrains that dumps and validates the public binary API of Kotlin/JVM (and experimentally KLib) libraries. Prevents accidental binary-incompatible changes. Apache 2.0.

**Status:** Maintenance mode (v0.18.1, last released July 2025). Critical fixes and Kotlin version support continue. New capability work targets the [experimental ABI validation built into the Kotlin Gradle Plugin itself](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html) (`kotlin { abiValidation { } }`, since Kotlin 2.2.0) — see `references/kgp-native-abi-validation.md` for that DSL. That feature is still evolving (task names changed between 2.2.0 and 2.4.0), so for a stable setup today, this standalone plugin remains the safer default; evaluate the KGP-native feature per-project once it stabilizes.

### Quick Setup

Apply to the **root project only** — subprojects are configured automatically:

```kotlin
// build.gradle.kts
plugins {
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
}
```

Requires Gradle ≥ 6.1.1 and Kotlin ≥ 1.6.20.

### Two Tasks

| Task | What It Does |
|------|-------------|
| **`apiDump`** | Builds project, dumps public API to `api/` subfolder in human-readable format. Overwrites existing dump. |
| **`apiCheck`** | Builds project, compares current public API against golden `.api` files. **Automatically wired into `check` and `build`** — fails build if API changed. |

For multi-JVM-target projects, subfolders are created (e.g. `api/jvm/`, `api/android/`).

### Recommended Workflow

**One-time setup:**
1. Apply plugin, configure, run `./gradlew apiDump`
2. Manually review the `.api` files
3. Commit `.api` files to VCS

**Regular development:**
- No API changes → nothing extra needed. `check` validates automatically on CI.
- Intentional API changes → `apiCheck` fails → run `apiDump` → review diff in `.api` files → commit diff with code changes.

### Configuration DSL

```kotlin
apiValidation {
    // Packages excluded from API dumps (even if public)
    ignoredPackages.add("kotlinx.coroutines.internal")

    // Subprojects excluded from validation entirely
    ignoredProjects.addAll(listOf("benchmarks", "examples"))

    // Fully-qualified classes excluded from API dumps
    ignoredClasses.add("com.company.BuildConfig")

    // Annotations that mark effectively-internal API
    // (public for technical reasons but not intended as public API)
    nonPublicMarkers.add("my.package.MyInternalApiAnnotation")

    // Programmatically disable validation
    validationDisabled = false

    // Custom dump directory (default: "api")
    apiDumpDirectory = "api"
}
```

### Custom Jar Input

If your build uses shadow plugin, excludes classes, or otherwise transforms the jar, point BCV at the actual output jar instead of `build/classes`:

```kotlin
tasks {
    apiBuild {
        // Use the actual output jar as input (not raw class files)
        inputJar.value(jar.flatMap { it.archiveFile })
        // For multiplatform: jvmJar.flatMap { ... }
        // For shadow: shadowJar.flatMap { ... }
    }
}
```

### What Is Public API?

**Classes** are public if:
- JVM access is `ACC_PUBLIC` or `ACC_PROTECTED`
- Kotlin visibility is public, protected, or internal+`@PublishedApi`
- Not local, not synthetic `$WhenMappings`
- Contained in an effectively public parent class
- Protected members only in non-final classes

**Members** (fields/methods) follow the same visibility rules. `lateinit` field visibility follows its setter's visibility.

### What Breaks Binary Compatibility?

**Classes:** renaming, removing a superclass from chain, removing an implemented interface, lessening visibility, making non-final class final, making non-abstract abstract, changing class↔interface/annotation.

**Members:** renaming, changing erased descriptor (return type, param types), changing field↔method, lessening visibility, making non-final final, making non-abstract abstract, changing static↔instance.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Detailed public API rules (full class and member visibility conditions with JVM access flags, Kotlin visibility interactions, PublishedApi, local/synthetic exclusions, file facades, protected-in-non-final), complete list of binary-incompatible changes (class-level and member-level with all ACC_ flags), experimental KLib ABI validation (setup, strictValidation, dump format, cross-platform host behavior, ABI inference, naming recommendations) | `references/api-rules-and-klib.md` |
| The newer, experimental ABI validation built directly into the Kotlin Gradle Plugin (`kotlin { abiValidation { } }`) — `checkKotlinAbi`/`updateKotlinAbi` tasks, `filters { excluded/included { byNames, annotatedWith } }`, multiplatform target inference, aligning dumps with `maven-publish` output | `references/kgp-native-abi-validation.md` |

</routing>

<reference_index>

**api-rules-and-klib.md** — what constitutes public API (classes: ACC_PUBLIC/ACC_PROTECTED JVM access AND Kotlin public/protected/internal-with-PublishedApi, no Kotlin visibility means no corresponding declaration so included, exclusions for local classes and synthetic $WhenMappings, file-class/multifile-facade only if contains effectively public member, nested classes must be in effectively public parent, protected members only in non-final parent; members: same ACC_PUBLIC/ACC_PROTECTED and Kotlin visibility rules, lateinit field visibility = setter visibility, protected in non-final only, excludes synthetic access methods for private fields). Binary-incompatible changes (class-level: full name change including package/containing classes, superclass no longer in inheritance chain, interface removed from implements set, ACC_PUBLIC/ACC_PROTECTED/ACC_PRIVATE lessening visibility, ACC_FINAL added to non-final, ACC_ABSTRACT added to non-abstract, ACC_INTERFACE class↔interface, ACC_ANNOTATION annotation↔interface; member-level: name change, descriptor change = erased return type or parameter types including field↔method, visibility lessening, ACC_FINAL added, ACC_ABSTRACT added, ACC_STATIC instance↔static). Experimental KLib ABI validation (requires Kotlin ≥ 1.9.20, klib.enabled = true with @OptIn ExperimentalBCVApi, adds dependencies to existing apiDump/apiCheck tasks, dumps to <project>.klib.api combining all targets with target annotations, klib.strictValidation for failing on unsupported targets instead of skipping, Apple targets only on Apple hosts — Linux/Windows infer from available targets + old dump, set rootProject.name for stable naming, all apiValidation options supported for klibs with JVM-format class names).

**kgp-native-abi-validation.md** — the experimental `kotlin { abiValidation { } }` feature built into KGP itself since 2.2.0, as a successor track to the standalone BCV plugin (both currently coexist; task/DSL names already changed once between 2.2.0 and 2.4.0, e.g. `checkLegacyAbi` → `checkKotlinAbi` — verify against current docs before use). Per-module setup (no root-only pattern), `checkKotlinAbi`/`updateKotlinAbi` tasks mapped to BCV's `apiCheck`/`apiDump`, `filters { excluded/included { byNames wildcards **/*/?, annotatedWith } }` replacing BCV's flat ignored*/nonPublicMarkers properties, `keepLocallyUnsupportedTargets` for multiplatform host-inference control (successor to KLib strictValidation), `binariesSource = MAVEN_PUBLICATIONS` for aligning dumps with post-processed published artifacts (no BCV equivalent).

</reference_index>
