# KGP Configuration & Targets

<apply_plugin>
## Applying the Kotlin Gradle Plugin

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.3.21"              // Kotlin/JVM
    // Or for other targets:
    // kotlin("multiplatform") version "2.3.21"  // Multiplatform
    // kotlin("android") version "2.3.21"        // Android
    // kotlin("js") version "2.3.21"             // JavaScript
}
```

In Groovy DSL, use the full plugin ID:
```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version '2.3.21'
}
```

### Version Catalog

```toml
# gradle/libs.versions.toml
[versions]
kotlin = "2.3.21"

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}
```
</apply_plugin>

<compatibility>
## Version Compatibility

| KGP version | Gradle min–max | AGP min–max |
|------------|---------------|-------------|
| 2.3.20–2.3.21 | 7.6.3–9.3.0 | 8.2.2–9.0.0 |
| 2.3.0–2.3.10 | 7.6.3–9.0.0 | 8.2.2–8.13.0+ |
| 2.2.0–2.2.21 | 7.6.3–8.14 | 7.3.1–8.11.1 |
| 2.1.0–2.1.21 | 7.6.3–8.12.1 | 7.3.1–8.7.2 |
| 2.0.0–2.0.21 | 6.8.3–8.8 | 7.1.3–8.5 |

KGP and Kotlin share the same version number.
</compatibility>

<jvm_target>
## Targeting the JVM

### Source Layout

```
project/src/
  main/
    kotlin/       # Kotlin sources
    java/         # Java sources (do NOT put .java in kotlin/)
    resources/
  test/
    kotlin/
    java/
```

### Java Toolchain (Recommended)

Sets JDK version for both Kotlin and Java compilation:

```kotlin
kotlin {
    jvmToolchain(17)
}
```

Add toolchain resolver in `settings.gradle.kts`:
```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}
```

### JVM Target Validation

KGP checks that `jvmTarget` (Kotlin) and `targetCompatibility` (Java) match. Mismatches cause build failures on Gradle 8.0+. Fix by using a toolchain or aligning manually:

```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
```
</jvm_target>

<configure_dependencies>
## Configuring Dependencies

```kotlin
dependencies {
    // Kotlin standard library is added automatically by KGP (since 1.4)
    // No need to declare kotlin-stdlib explicitly

    // Kotlin test
    testImplementation(kotlin("test"))          // Auto-selects JUnit 5/4/TestNG

    // Kotlinx libraries
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")

    // Annotation processing
    // Prefer KSP over kapt:
    ksp("com.google.dagger:dagger-compiler:2.51")
    // Legacy kapt:
    // kapt("com.google.dagger:dagger-compiler:2.51")
}

tasks.test {
    useJUnitPlatform()  // For JUnit 5
}
```

### Kotlin Standard Library

- Added automatically as a dependency by KGP — no explicit declaration needed.
- Version matches the KGP version.
- For JVM: `kotlin-stdlib` (includes JDK 7/8 extensions since Kotlin 1.8).
</configure_dependencies>

<serialization_allopen>
## Common Kotlin Plugins

### Serialization

```kotlin
plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
}
```

### All-Open / Spring Support

```kotlin
plugins {
    kotlin("plugin.spring") version "2.3.21"  // Opens Spring-annotated classes
    // Or more general:
    // kotlin("plugin.allopen") version "2.3.21"
}

// Custom all-open annotations:
allOpen {
    annotation("com.example.MyAnnotation")
}
```

### No-Arg (JPA)

```kotlin
plugins {
    kotlin("plugin.jpa") version "2.3.21"
}
```

### KSP (Kotlin Symbol Processing)

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.3.21-1.0.30"
}

dependencies {
    ksp("com.example:my-processor:1.0")
}
```
</serialization_allopen>
