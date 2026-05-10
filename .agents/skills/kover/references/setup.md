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

### Mixed KMP (Android + JVM)
JVM targets get a report variant named `jvm`. Android targets get per-build-variant report variants. Create custom variants to merge them.
</project_types>

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
