# Gradle Dependency Management

<dependency_basics>
## Declaring Dependencies

Dependencies are declared in the `dependencies {}` block using **configurations** (buckets that define scope):

```kotlin
dependencies {
    // Production code
    implementation("com.google.guava:guava:32.1.2-jre")
    api("org.apache.juneau:juneau-marshall:8.2.0")

    // Compile-only (not at runtime)
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")

    // Runtime-only (not at compile time)
    runtimeOnly("org.postgresql:postgresql:42.7.1")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Project dependency (subproject)
    implementation(project(":core"))
}
```
</dependency_basics>

<configurations>
## Dependency Configurations

### Java / Kotlin JVM Configurations

| Configuration | Compile | Runtime | Exposed to consumers? |
|--------------|---------|---------|----------------------|
| `implementation` | ✓ | ✓ | No — internal |
| `api` | ✓ | ✓ | Yes — on consumer's classpath |
| `compileOnly` | ✓ | ✗ | No |
| `runtimeOnly` | ✗ | ✓ | No |
| `testImplementation` | ✓ (test) | ✓ (test) | No |
| `testRuntimeOnly` | ✗ | ✓ (test) | No |

**Rule of thumb:** Use `implementation` by default. Use `api` only when the dependency's types appear in your library's public API.

### Android Variant-Aware Configurations

AGP adds per-variant configurations: `debugImplementation`, `releaseImplementation`, `androidTestImplementation`, etc.

### Kotlin Multiplatform Configurations

KMP creates source-set-scoped configs: `commonMainImplementation`, `commonTestImplementation`, `androidMainImplementation`, `iosArm64MainImplementation`, etc.
</configurations>

<version_catalog>
## Version Catalog (`gradle/libs.versions.toml`)

Centralized, consistent dependency management across the build:

```toml
[versions]
kotlin = "2.3.21"
coroutines = "1.9.0"
junit = "5.10.0"

[libraries]
coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version.ref = "junit" }

[bundles]
coroutines = ["coroutines-core", "coroutines-test"]

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

**Usage in build scripts:**

```kotlin
// settings.gradle.kts — catalog loaded automatically from gradle/libs.versions.toml

// build.gradle.kts
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.bundles.coroutines)
    testImplementation(libs.junit.jupiter)
}
```
</version_catalog>

<repositories>
## Repositories

```kotlin
repositories {
    mavenCentral()                  // Primary public repo
    google()                        // Android/Google libraries
    gradlePluginPortal()            // Gradle plugins
    maven("https://jitpack.io")    // Custom Maven repo
    mavenLocal()                    // ~/.m2/repository (avoid in production)
}
```
</repositories>

<inspecting>
## Inspecting Dependencies

```bash
# Full dependency tree
./gradlew dependencies

# For a specific configuration
./gradlew dependencies --configuration runtimeClasspath

# For a specific subproject
./gradlew :app:dependencies

# Find where a dependency comes from
./gradlew dependencyInsight --dependency guava --configuration runtimeClasspath
```
</inspecting>

<constraints>
## Dependency Constraints & Conflict Resolution

```kotlin
dependencies {
    // Force a minimum version
    constraints {
        implementation("com.google.guava:guava:32.1.2-jre") {
            because("CVE-2023-xxxxx fix")
        }
    }

    // Strictly lock a version
    implementation("com.example:lib") {
        version { strictly("1.2.3") }
    }

    // Exclude a transitive dependency
    implementation("com.example:lib:1.0") {
        exclude(group = "org.unwanted", module = "bad-dep")
    }
}
```

Gradle resolves version conflicts by selecting the **highest requested version** by default.
</constraints>
