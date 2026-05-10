# KMP Dependencies

<common_dependencies>
## Adding Dependencies to Common Code

Dependencies in `commonMain` are available to **all** source sets. Use the library's base artifact name — Gradle resolves the correct platform variant automatically.

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("io.ktor:ktor-client-core:3.4.1")
        }
    }
}
```

**Rule:** You cannot add platform-specific libraries to `commonMain`. If a library only exists for one platform (e.g., `java.io`, Android SDK, UIKit), it goes in the corresponding platform source set.
</common_dependencies>

<platform_dependencies>
## Platform-Specific Dependencies

Add platform-only libraries to the matching source set:

```kotlin
kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation("androidx.core:core-ktx:1.15.0")
            implementation("com.google.android.material:material:1.12.0")
        }
        iosMain.dependencies {
            // iOS-only KMP libraries
            implementation("com.squareup.sqldelight:native-driver:2.2.1")
        }
        jvmMain.dependencies {
            implementation("com.zaxxer:HikariCP:6.2.1")
        }
    }
}
```
</platform_dependencies>

<test_dependencies>
## Test Dependencies

The `kotlin.test` library provides cross-platform test APIs. Add it once in `commonTest`:

```kotlin
kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Brings JUnit for JVM, XCTest for iOS, etc. automatically
        }
    }
}
```

Platform-specific test dependencies (e.g., JUnit 5 for JVM) go in the corresponding test source set:

```kotlin
jvmTest.dependencies {
    implementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
```
</test_dependencies>

<standard_library>
## Standard Library

A dependency on `stdlib` is added automatically to every source set. The version matches the `kotlin-multiplatform` plugin version. You don't need to declare it.
</standard_library>

<multiproject>
## Multiplatform Project Dependencies

Reference another module in the same project:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared-module"))
        }
    }
}
```

Gradle resolves the correct platform variant for each target automatically.
</multiproject>

<version_catalogs>
## Version Catalogs

Use `libs.versions.toml` for all dependency versions:

```toml
# gradle/libs.versions.toml
[versions]
coroutines = "1.10.2"
ktor = "3.4.1"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
```

Then in `build.gradle.kts`:

```kotlin
commonMain.dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
}
```
</version_catalogs>

<key_libraries>
## Popular KMP Libraries

| Library | Artifact | Purpose |
|---------|----------|---------|
| kotlinx-coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-core` | Async/concurrency |
| kotlinx-serialization | `org.jetbrains.kotlinx:kotlinx-serialization-json` | JSON serialization |
| Ktor Client | `io.ktor:ktor-client-core` | HTTP client |
| SQLDelight | `app.cash.sqldelight:*` | SQL database |
| Koin | `io.insert-koin:koin-core` | Dependency injection |
| kotlinx-datetime | `org.jetbrains.kotlinx:kotlinx-datetime` | Date/time |

Search for KMP libraries at [klibs.io](https://klibs.io/).
</key_libraries>
