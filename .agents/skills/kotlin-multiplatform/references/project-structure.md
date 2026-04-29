# KMP Project Structure

<source_sets>
## Source Sets

A **source set** is a set of source files with its own targets, dependencies, and compiler options. It's the primary mechanism for sharing code in multiplatform projects.

### Key Source Sets

| Source Set | Compiles To | Purpose |
|-----------|-------------|---------|
| `commonMain` | All declared targets | Shared business logic |
| `commonTest` | All declared targets | Shared tests |
| `androidMain` | Android | Android-specific code |
| `iosMain` | All iOS targets (intermediate) | iOS-specific code shared across device + simulator |
| `iosArm64Main` | iOS ARM64 devices | Rarely used — `iosMain` covers both |
| `iosSimulatorArm64Main` | iOS Simulator on Apple Silicon | Rarely used — `iosMain` covers both |
| `jvmMain` | JVM | JVM-specific code |
| `nativeMain` | All native targets (intermediate) | Code shared across all native targets |
| `appleMain` | All Apple targets (intermediate) | Code shared across iOS, macOS, watchOS, tvOS |

### Directory Layout

```
src/
├── commonMain/kotlin/       Shared code (~85% of a typical project)
├── commonTest/kotlin/       Shared tests
├── androidMain/kotlin/      Android-specific code
├── androidUnitTest/kotlin/  Android unit tests
├── iosMain/kotlin/          iOS-specific code (device + simulator)
├── iosArm64Main/kotlin/     (usually empty — iosMain covers both)
├── iosSimulatorArm64Main/kotlin/ (usually empty)
├── iosTest/kotlin/          iOS-specific tests
├── jvmMain/kotlin/          JVM-specific code
└── jvmTest/kotlin/          JVM-specific tests
```

Each `*Main` source set has a corresponding `*Test` source set. The connection is automatic — tests can use APIs from `Main` without configuration.
</source_sets>

<compilation_model>
## How Compilation Works

When Kotlin compiles to a specific target, it collects **all source sets labeled with that target** and compiles them together.

Example: compiling to JVM collects `commonMain` + `jvmMain` → produces JVM `.class` files.

Example: compiling to `iosArm64` collects `commonMain` + `appleMain` + `iosMain` + `iosArm64Main` → produces native binary.

**Key rules:**
- `commonMain` code compiles to **all** declared targets — no platform-specific APIs allowed
- Platform source sets (`jvmMain`, `iosMain`) can use platform-specific libraries
- Platform source sets **can** access declarations from `commonMain`
- `commonMain` **cannot** access code from platform source sets
- The compiler prevents using platform-specific APIs in `commonMain`
</compilation_model>

<intermediate_source_sets>
## Intermediate Source Sets

Intermediate source sets share code among **some but not all** targets. Kotlin creates many by default:

- `appleMain` — shared across all Apple targets (iOS, macOS, watchOS, tvOS)
- `iosMain` — shared across `iosArm64` and `iosSimulatorArm64`/`iosX64`
- `nativeMain` — shared across all Kotlin/Native targets

These enable using platform-group-specific APIs. For example, `iosMain` can use `platform.Foundation.NSUUID` because it only compiles to iOS targets.

**Apple device vs simulator:** There is no single `ios` target. You need both:
- **Device target:** `iosArm64` — generates binaries for physical devices
- **Simulator target:** `iosSimulatorArm64` (Apple Silicon) or `iosX64` (Intel)

The `iosMain` intermediate source set shares code between device and simulator. Platform-specific source sets like `iosArm64Main` are **rarely used** because device and simulator code is normally identical.
</intermediate_source_sets>

<targets>
## Targets

Targets define which platforms Kotlin compiles to. Declared in `build.gradle.kts`:

```kotlin
kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    jvm()
    js(IR) { browser() }
    wasmJs { browser() }
    macosArm64()
    macosX64()
    linuxX64()
    tvosArm64()
    watchosArm64()
}
```

Each target creates a corresponding platform source set. Only declare targets you actually ship to.
</targets>
