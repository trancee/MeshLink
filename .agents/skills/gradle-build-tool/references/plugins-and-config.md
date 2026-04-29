# Gradle Plugins & Build Configuration

<plugins>
## Applying Plugins

Plugins add tasks, configurations, and conventions to your project.

### `plugins {}` Block (Preferred)

```kotlin
// build.gradle.kts
plugins {
    id("java-library")                           // Core Gradle plugin (no version needed)
    id("application")                            // Core plugin
    kotlin("jvm") version "2.3.21"               // Kotlin shorthand
    id("com.google.protobuf") version "0.9.4"    // Community plugin
}
```

### Version Catalog Aliases

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.application)
}
```

### Plugin Management (settings)

Declare plugin repositories and default versions in `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.3.21"
    }
}
```
</plugins>

<common_plugins>
## Common Plugins

| Plugin | ID | What it does |
|--------|------|-------------|
| Java Library | `java-library` | Compile Java, `api`/`implementation` configurations |
| Application | `application` | Adds `run` task, generates start scripts |
| Kotlin JVM | `org.jetbrains.kotlin.jvm` | Kotlin compilation for JVM |
| Kotlin Multiplatform | `org.jetbrains.kotlin.multiplatform` | KMP targets |
| Android Application | `com.android.application` | Android app module |
| Android Library | `com.android.library` | Android library module |
| Maven Publish | `maven-publish` | Publish to Maven repos |
| Java Test Fixtures | `java-test-fixtures` | Shared test utilities |
</common_plugins>

<kotlin_dsl>
## Kotlin DSL Tips

### Type-Safe Accessors

Plugins create type-safe accessors for configurations and extensions:

```kotlin
// After applying java-library plugin:
dependencies {
    implementation("...")      // Type-safe — IDE auto-completes
    api("...")
    testImplementation("...")
}

// After applying kotlin("jvm"):
kotlin {
    jvmToolchain(17)
}
```

### When Accessors Aren't Available

Use the string-based API:

```kotlin
// In allprojects/subprojects blocks (no type-safe accessors)
subprojects {
    apply(plugin = "java-library")
    dependencies {
        "implementation"("com.example:lib:1.0")
    }
}
```

### Lazy Property Assignment

```kotlin
tasks.jar {
    archiveBaseName.set("my-app")       // Property<String>.set()
    archiveBaseName = "my-app"          // Assignment operator (Gradle 8.2+)
}
```
</kotlin_dsl>

<multi_project>
## Multi-Project Builds

### Structure

```
my-project/
├── settings.gradle.kts    # include("app", "core", "util")
├── build.gradle.kts       # Root — shared config (optional)
├── app/
│   └── build.gradle.kts
├── core/
│   └── build.gradle.kts
└── util/
    └── build.gradle.kts
```

### Settings

```kotlin
// settings.gradle.kts
rootProject.name = "my-project"
include("app", "core", "util")

// Nested: include("services:api") → services/api/
```

### Cross-Project Dependencies

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":core"))
    implementation(project(":util"))
}
```

### Convention Plugins (Shared Build Logic)

Use `buildSrc/` or a `build-logic` included build to share common configuration:

```kotlin
// build-logic/src/main/kotlin/my-kotlin-conventions.gradle.kts
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

tasks.test {
    useJUnitPlatform()
}
```

```kotlin
// app/build.gradle.kts — just apply the convention
plugins {
    id("my-kotlin-conventions")
}
```
</multi_project>

<composite_builds>
## Composite Builds

Include entire separate builds instead of subprojects:

```kotlin
// settings.gradle.kts
includeBuild("my-utils")       // Include another Gradle build
includeBuild("build-logic")    // Common pattern for shared build logic
```

Composite builds allow substitution — when your code depends on `com.example:my-utils:1.0`, Gradle automatically substitutes the included build's output instead of fetching from a repository.
</composite_builds>
