# Workflow: Add a Dependency

<required_reading>
**Read these reference files NOW:**
1. references/dependencies.md
</required_reading>

<process>
## Step 1: Determine Scope

**Where should the dependency go?**

| Scenario | Source Set |
|----------|-----------|
| Used by all platforms | `commonMain` |
| Used only on Android | `androidMain` |
| Used only on iOS | `iosMain` |
| Used only on JVM | `jvmMain` |
| Used only in tests | `commonTest` (or platform-specific test source set) |

**Rule:** You cannot add platform-specific libraries to `commonMain`. The compiler will reject it because `commonMain` compiles to all targets.

## Step 2: Check If the Library Is KMP-Compatible

- If the library publishes KMP artifacts (e.g., Ktor, kotlinx-*, SQLDelight, Koin), add it to `commonMain` using the **base artifact name** — Gradle resolves the platform variant.
- If the library is platform-only (e.g., `androidx.*`, Java libraries, iOS frameworks), add it to the corresponding platform source set.
- Search [klibs.io](https://klibs.io/) to check KMP compatibility.

## Step 3: Add to Version Catalog (Preferred)

In `gradle/libs.versions.toml`:

```toml
[versions]
ktor = "3.4.1"

[libraries]
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
```

## Step 4: Add to Build Script

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)  // OkHttp engine for Android
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)   // Darwin engine for iOS
        }
    }
}
```

**For KMP libraries in `commonMain`:** Use the base artifact. Platform parts are added automatically to child source sets.

**For platform engines/drivers:** Add the platform-specific artifact to the platform source set.

## Step 5: Sync and Verify

```bash
./gradlew :module:dependencies
```

Check that the dependency resolves for all targets. Run a quick compilation:

```bash
./gradlew :module:compileKotlinMetadata
```
</process>

<success_criteria>
- [ ] Dependency added to the correct source set
- [ ] Version managed in `libs.versions.toml` (preferred) or inline
- [ ] No platform-specific libraries in `commonMain`
- [ ] Gradle sync succeeds
- [ ] `compileKotlinMetadata` passes
</success_criteria>
