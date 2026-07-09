# Kover Setup & Project Types

<apply>
## Applying the Kover Plugin

```kotlin
// build.gradle.kts
plugins {
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
}
```

In multi-module builds, apply in the root module (even without source/tests). Submodules don't need the version:
```kotlin
// submodule/build.gradle.kts
plugins {
    id("org.jetbrains.kotlinx.kover")  // version inherited from root
}
```

Requires `mavenCentral()` in the repositories list.
</apply>

<project_types>
## Project Types

### Single-Module JVM / KMP
No additional config needed. Just apply the plugin. KMP: only JVM target coverage is measured; non-JVM source sets are ignored.

### Multi-Module JVM / KMP
Choose a **merging module** (recommended: root) and add `kover` dependencies to include other modules' classes in merged reports:

```kotlin
// root build.gradle.kts
dependencies {
    kover(project(":moduleA"))
    kover(project(":moduleB"))
}
```

Running `:koverHtmlReport` in the merging module triggers tests in all `kover`-dependent modules and merges their coverage.

### Android Projects
For each Android build variant (e.g., `debug`, `release`), Kover creates a matching **report variant**. Tasks are suffixed with the variant name:

| Total (all variants) | Per-variant (e.g., debug) |
|----------------------|--------------------------|
| `koverHtmlReport` | `koverHtmlReportDebug` |
| `koverXmlReport` | `koverXmlReportDebug` |
| `koverVerify` | `koverVerifyDebug` |
| `koverLog` | `koverLogDebug` |

**On-device instrumented tests are NOT supported** — only local unit tests.

### Custom Report Variants
Combine multiple build variants into one report:
```kotlin
kover {
    currentProject {
        createVariant("custom") {
            add("debug")     // Android build variant
            add("jvm")       // JVM target (KMP)
        }
    }
}
```
Then use: `koverHtmlReportCustom`, `koverVerifyCustom`, etc.

Other `currentProject { }` variant functions:
- **`copyVariant("newName", "originalName")`** — clone an existing variant's coverage data under a new name so it can get its own filters/rules via `reports { variant("newName") { ... } }`, without merging in more classes.
- **`providedVariant("jvm") { sources { ... } }`** — reconfigure an auto-created variant (e.g. the `jvm` KMP target or an Android build variant) in place.
- **`totalVariant { sources { ... } }`** — reconfigure the always-present total variant (all code in the current project).

### Mixed KMP (Android + JVM)
JVM targets get a report variant named `jvm`. Android targets get per-build-variant report variants. Create custom variants to merge them.
</project_types>

<merging_shortcut>
## Automatic Merging Shortcut

Instead of manually applying the plugin to every module and wiring `kover(project(...))` dependencies, the merging module can delegate that setup with `merge { }`:

```kotlin
// root build.gradle.kts (merging module)
kover {
    merge {
        subprojects()           // all subprojects of this project
        // allProjects()         // every project in the build, including this one
        // subprojects { it.name != "uncovered" }  // filtered
        // projects("moduleA", ":moduleB")          // explicit list, by name or path
    }
}
```
This applies the Kover plugin to every matched project and adds the equivalent `kover(project(...))` dependency automatically — equivalent to writing the manual `kover()` dependency block for each one. `sources { }`, `instrumentation { }`, and `createVariant(name) { }` blocks inside `merge { }` apply to every included project (each receives the current `project` via `KoverProjectAware` for per-project conditionals).

**Danger:** `merge { }` reads and configures other projects directly, which **breaks project isolation and disables the configuration cache**. For configuration-cache-compatible multi-module setups, apply the plugin per-module explicitly and use `currentProject { }` in each, or the manual `kover(project(...))` dependency pattern above.

</merging_shortcut>

<disabling>
## Disabling Kover Entirely

To turn off all Kover instrumentation and tasks for a project (e.g. while isolating an instrumentation bug):
```kotlin
kover {
    disable()
}
```
</disabling>

<tasks>
## Gradle Tasks

| Task | Description |
|------|-------------|
| `koverHtmlReport` | Generate HTML coverage report |
| `koverXmlReport` | Generate JaCoCo-compatible XML report |
| `koverVerify` | Check verification rules (fails build if violated) |
| `koverBinaryReport` | Generate binary report (IC format) |
| `koverLog` | Print coverage to console |

All tasks automatically trigger test execution. Use full paths in multi-module builds (e.g., `:koverHtmlReport` not `koverHtmlReport`).

### Named variant tasks
Append the variant name: `koverHtmlReportDebug`, `koverVerifyRelease`, etc.
</tasks>
