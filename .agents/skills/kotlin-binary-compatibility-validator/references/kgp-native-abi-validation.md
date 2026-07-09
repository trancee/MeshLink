# Binary Compatibility Validation Built into the Kotlin Gradle Plugin (Experimental)

<status>
## Status

Since Kotlin 2.2.0, the Kotlin Gradle Plugin (KGP) has an **experimental**, built-in binary/ABI compatibility validation feature — the eventual successor to the standalone BCV plugin (`org.jetbrains.kotlinx.binary-compatibility-validator`) documented in the rest of this skill. Both currently coexist; the standalone plugin remains maintained during this transition, but new capability work targets the KGP-native feature.

**Treat this as unstable and check current docs before relying on exact syntax:** Kotlin 2.4.0 itself "streamlines the DSL... and deprecates some parts" of what shipped in 2.2.0/2.3.x — for example, the check task was named `checkLegacyAbi` in the 2.2.0 preview and is `checkKotlinAbi` as of 2.4.0. Don't assume task/property names are stable between Kotlin versions; verify against https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html for the Kotlin version actually in use.
</status>

<setup>
## Enabling (Kotlin 2.4.0+ DSL)

Configured per-module, inside `kotlin {}` — unlike the standalone BCV plugin, there's no "apply to root only" pattern; multi-module projects configure each module separately with its own settings.

```kotlin
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()   // no custom config — shorthand
}
```

With configuration:
```kotlin
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        filters {
            excluded {
                byNames.add("**.InternalUtils")
                annotatedWith.add("com.example.annotations.InternalApi")
            }
            included {
                byNames.add("com.example.api.**")
                annotatedWith.add("com.example.annotations.PublicApi")
            }
        }
    }
}
```
</setup>

<tasks>
## Tasks

| Task | Equivalent to (standalone BCV) | What It Does |
|------|-------------------------------|--------------|
| `checkKotlinAbi` | `apiCheck` | Compares current ABI against the reference dump, fails with diffs on mismatch. Runs automatically as part of `check` when the feature is enabled. |
| `updateKotlinAbi` | `apiDump` | Overwrites the reference ABI dump with the current one. Only run this once you've confirmed the changes are intentional and binary-compatible (or an accepted break). |

```bash
./gradlew checkKotlinAbi
./gradlew updateKotlinAbi
```
</tasks>

<filters>
## Filters (Replaces `ignoredPackages`/`ignoredClasses`/`nonPublicMarkers`)

The `filters { excluded { } included { } }` block replaces BCV's flat `ignoredPackages`/`ignoredProjects`/`ignoredClasses`/`nonPublicMarkers` properties with a rule-based approach:

- **`byNames`** — fully qualified name of a class, property, or function, with wildcards: `**` matches zero or more characters including periods (package-spanning); `*` matches zero or more characters excluding periods (single segment); `?` matches exactly one character.
- **`annotatedWith`** — name of an annotation with `BINARY` or `RUNTIME` retention (equivalent role to BCV's `nonPublicMarkers`, but usable for both exclusion and inclusion).

**Matching logic:** a declaration is included in the ABI dump only if it matches no exclusion rule; when inclusion rules are defined, a declaration must match one of them (or have at least one member that does) to be included at all.
</filters>

<multiplatform>
## Multiplatform Target Inference

If the current host can't cross-compile all declared targets, KGP infers ABI for the missing targets from the other targets' dumps plus the previous reference dump — avoiding false failures when switching to a more capable host later.

```kotlin
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        keepLocallyUnsupportedTargets.set(false)   // disable inference; checkKotlinAbi fails instead
    }
}
```
Disabling this (`false`) trades convenience for certainty: `checkKotlinAbi` fails outright on a host that can't produce a complete dump, rather than risk silently missing a binary-incompatible change on an inferred target.
</multiplatform>

<maven_publications>
## Aligning Dumps with Published Artifacts

By default, ABI dumps are generated from Kotlin compilation outputs, which may not match the final published artifact if post-processing (e.g. relocation via `maven-publish`) changes it after compilation.

```kotlin
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        binariesSource.set(MAVEN_PUBLICATIONS)
    }
}
```
Not applicable to Kotlin/Android projects or multiplatform projects with an Android target, since those don't publish JAR files. This capability has no equivalent in the standalone BCV plugin.
</maven_publications>
