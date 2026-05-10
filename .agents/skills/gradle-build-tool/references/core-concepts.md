# Gradle Core Concepts & Project Structure

<project_structure>
## Project Structure

```
project/
├── gradle/                          # Wrapper files, version catalog
│   ├── wrapper/
│   │   ├── gradle-wrapper.jar
│   │   └── gradle-wrapper.properties
│   └── libs.versions.toml           # Version catalog
├── gradlew                          # Wrapper script (Unix)
├── gradlew.bat                      # Wrapper script (Windows)
├── settings.gradle.kts              # Defines root name + subprojects
├── build.gradle.kts                 # Root build script (optional)
├── gradle.properties                # Build-wide properties
├── app/
│   ├── build.gradle.kts             # Subproject build script
│   └── src/
└── lib/
    ├── build.gradle.kts
    └── src/
```

**Presence of `gradlew` / `gradlew.bat` = this is a Gradle project.**
</project_structure>

<core_concepts>
## Core Concepts

| Concept | Meaning |
|---------|---------|
| **Build** | The process of producing outputs. Includes one or more projects and their build scripts. |
| **Project** | A piece of software (app, library). A build may have a root project + subprojects. |
| **Task** | A unit of work — compiling code, running tests, creating JARs. Declared in build scripts or added by plugins. |
| **Build Script** | `build.gradle(.kts)` — configures tasks, dependencies, plugins for a project. |
| **Plugin** | Extends Gradle (e.g., `java`, `kotlin("jvm")`). Adds tasks and conventions. |
| **Dependency** | External/internal resource required by a project. Gradle resolves automatically. |
| **Wrapper** | Script that invokes a declared Gradle version. **Always use `./gradlew`, not `gradle`**. |
</core_concepts>

<settings_file>
## Settings File (`settings.gradle.kts`)

Evaluated during initialization phase. Defines the build's project structure.

```kotlin
// settings.gradle.kts
rootProject.name = "my-project"

// Include subprojects
include("app", "core", "util")

// Nested subproject (maps to services/api/ directory)
include("services:api")

// Plugin management (restrict where plugins come from)
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

// Dependency resolution management
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
```
</settings_file>

<build_file>
## Build File (`build.gradle.kts`)

Evaluated during configuration phase. Configures the project.

```kotlin
// build.gradle.kts
plugins {
    id("java-library")
    kotlin("jvm") version "2.3.21"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.guava:guava:32.1.2-jre")
    api("org.apache.juneau:juneau-marshall:8.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.test {
    useJUnitPlatform()
}
```
</build_file>

<wrapper>
## Gradle Wrapper

**Always use the wrapper** — ensures consistent Gradle version across the team.

```bash
# Use the wrapper (recommended)
./gradlew build

# Update wrapper version
./gradlew wrapper --gradle-version 9.4.1

# Wrapper files (commit all of these to VCS)
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
gradlew
gradlew.bat
```
</wrapper>

<gradle_properties>
## `gradle.properties`

Build-wide settings. Checked into VCS.

```properties
# JVM memory
org.gradle.jvmargs=-Xmx2g -XX:+HeapDumpOnOutOfMemoryError

# Performance
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true

# Project properties
myProp=myValue
```

Property precedence (highest first):
1. Command line (`-P` flags)
2. `gradle.properties` in `GRADLE_USER_HOME` (`~/.gradle/`)
3. `gradle.properties` in project root
4. Environment variables (`ORG_GRADLE_PROJECT_*`)
</gradle_properties>
