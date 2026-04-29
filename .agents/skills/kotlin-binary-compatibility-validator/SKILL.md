---
name: kotlin-binary-compatibility-validator
description: Complete reference for the Kotlin Binary Compatibility Validator (BCV) Gradle plugin (org.jetbrains.kotlinx.binary-compatibility-validator, v0.18.1). Dumps and validates public binary API of Kotlin/JVM libraries to prevent accidental binary-incompatible changes. Covers plugin setup (apply to root project, auto-configures subprojects), Gradle tasks (apiDump to generate .api files, apiCheck wired into check/build pipeline), the apiValidation DSL (ignoredPackages, ignoredProjects, ignoredClasses, nonPublicMarkers for @InternalApi annotations, validationDisabled, apiDumpDirectory), using inputJar for shadow/custom jar builds, the recommended workflow (one-time apiDump + commit .api files, then apiCheck on CI, apiDump on intentional changes), experimental KLib ABI validation (klib.enabled, klib.strictValidation, .klib.api dump files, cross-platform host limitations, ABI inference for unsupported targets), what constitutes public API (classes with ACC_PUBLIC/ACC_PROTECTED, Kotlin public/protected/internal+PublishedApi, excluding local/synthetic/$WhenMappings classes; members with same visibility rules, lateinit field visibility follows setter, protected members only in non-final classes), and what makes an incompatible change (class: name change, superclass removal from chain, interface removal, visibility lessening, adding final/abstract, class↔interface change; members: name change, descriptor change including erased types, visibility lessening, adding final/abstract, static↔instance change). Use when setting up BCV in a Kotlin project, configuring API validation, understanding what counts as public API, diagnosing apiCheck failures, excluding internal APIs from validation, working with KLib targets, or any question about binary compatibility validation for Kotlin libraries.
---

<essential_principles>

**Binary Compatibility Validator (BCV)** — Gradle plugin by JetBrains that dumps and validates the public binary API of Kotlin/JVM (and experimentally KLib) libraries. Prevents accidental binary-incompatible changes. Apache 2.0.

**Status:** Maintenance mode (v0.18.1). Critical fixes and Kotlin version support continue. New features go to the [experimental validation in the Kotlin Gradle plugin](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html).

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

</routing>

<reference_index>

**api-rules-and-klib.md** — what constitutes public API (classes: ACC_PUBLIC/ACC_PROTECTED JVM access AND Kotlin public/protected/internal-with-PublishedApi, no Kotlin visibility means no corresponding declaration so included, exclusions for local classes and synthetic $WhenMappings, file-class/multifile-facade only if contains effectively public member, nested classes must be in effectively public parent, protected members only in non-final parent; members: same ACC_PUBLIC/ACC_PROTECTED and Kotlin visibility rules, lateinit field visibility = setter visibility, protected in non-final only, excludes synthetic access methods for private fields). Binary-incompatible changes (class-level: full name change including package/containing classes, superclass no longer in inheritance chain, interface removed from implements set, ACC_PUBLIC/ACC_PROTECTED/ACC_PRIVATE lessening visibility, ACC_FINAL added to non-final, ACC_ABSTRACT added to non-abstract, ACC_INTERFACE class↔interface, ACC_ANNOTATION annotation↔interface; member-level: name change, descriptor change = erased return type or parameter types including field↔method, visibility lessening, ACC_FINAL added, ACC_ABSTRACT added, ACC_STATIC instance↔static). Experimental KLib ABI validation (requires Kotlin ≥ 1.9.20, klib.enabled = true with @OptIn ExperimentalBCVApi, adds dependencies to existing apiDump/apiCheck tasks, dumps to <project>.klib.api combining all targets with target annotations, klib.strictValidation for failing on unsupported targets instead of skipping, Apple targets only on Apple hosts — Linux/Windows infer from available targets + old dump, set rootProject.name for stable naming, all apiValidation options supported for klibs with JVM-format class names).

</reference_index>
