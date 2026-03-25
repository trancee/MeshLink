# AGP Kotlin DSL Patterns

## Project Structure Files

### `settings.gradle.kts` (root)
```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MyApp"
include(":app", ":core:network", ":feature:home")
```

### `build.gradle.kts` (root / top-level)
```kotlin
// Apply plugins with `apply false` at root — don't apply them here
plugins {
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
```

### `gradle/libs.versions.toml` (version catalog — modern approach)
```toml
[versions]
agp = "9.1.0"
kotlin = "2.1.0"
compose-bom = "2024.12.01"
hilt = "2.51.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "..." }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

---

## App Module `build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // or via version catalog: alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.myapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.myapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Enable Java 8+ API desugaring for minSdk < 26
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true        // for Jetpack Compose
        viewBinding = true    // for View-based UI
        buildConfig = true    // to generate BuildConfig class
    }

    composeOptions {
        // Only needed if NOT using Kotlin compiler plugin 2.0+
        // kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    coreLibraryDesugaring(libs.android.desugar.jdk.libs)  // if isCoreLibraryDesugaringEnabled

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
```

---

## Library Module `build.gradle.kts`

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.core.network"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // No applicationId, versionCode, or versionName in libraries
        consumerProguardFiles("consumer-rules.pro")  // rules for library consumers
    }

    buildTypes {
        release {
            isMinifyEnabled = false  // Libraries usually don't minify themselves
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}
```

---

## Product Flavors

```kotlin
android {
    flavorDimensions += listOf("environment", "tier")

    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            buildConfigField("String", "API_URL", "\"https://dev.api.example.com\"")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "API_URL", "\"https://api.example.com\"")
        }
        create("free") {
            dimension = "tier"
        }
        create("paid") {
            dimension = "tier"
        }
    }
}
// This produces: devFreeDebug, devFreeRelease, devPaidDebug, devPaidRelease,
//                prodFreeDebug, prodFreeRelease, prodPaidDebug, prodPaidRelease
```

---

## Signing Config (secure pattern)

```kotlin
// Read from local keystore.properties (gitignored) or environment variables
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = java.util.Properties().apply {
    if (keystorePropertiesFile.exists()) load(keystorePropertiesFile.inputStream())
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(
                keystoreProperties["storeFile"] as String?
                    ?: System.getenv("KEYSTORE_PATH")
                    ?: error("No keystore path configured")
            )
            storePassword = keystoreProperties["storePassword"] as String?
                ?: System.getenv("KEYSTORE_PASSWORD")
            keyAlias = keystoreProperties["keyAlias"] as String?
                ?: System.getenv("KEY_ALIAS")
            keyPassword = keystoreProperties["keyPassword"] as String?
                ?: System.getenv("KEY_PASSWORD")
        }
    }
}
```

---

## `gradle.properties` — Build Performance Settings

```properties
# Daemon settings
org.gradle.daemon=true
org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -XX:MaxMetaspaceSize=512m

# Parallel execution
org.gradle.parallel=true

# Build cache (local and remote)
org.gradle.caching=true

# Configuration cache (significant speedup for repeated builds)
org.gradle.configuration-cache=true

# Android-specific
android.useAndroidX=true
android.nonTransitiveRClass=true  # reduces R class size, recommended

# Suppress warnings (use carefully)
# android.suppressUnsupportedCompileSdk=36
```

---

## Dependency Configurations

| Configuration         | When to Use |
|-----------------------|-------------|
| `implementation`      | Standard dependency, not exposed to consumers |
| `api`                 | Dependency exposed to consumers (library modules only) |
| `compileOnly`         | Available at compile time, not packaged (e.g., annotation processors that don't leak) |
| `runtimeOnly`         | Not available at compile time, but packaged |
| `testImplementation`  | Unit tests only |
| `androidTestImplementation` | Instrumentation tests only |
| `debugImplementation` | Debug build type only |
| `releaseImplementation` | Release build type only |
| `coreLibraryDesugaring` | Java 8+ API backport library |
| `kapt` / `ksp`        | Annotation processors (prefer KSP over kapt for Kotlin) |

---

## androidComponents {} — Modern Variant API

Use `androidComponents {}` instead of `android.applicationVariants.all {}` for variant-aware
post-configuration in modern AGP (8.0+):

```kotlin
androidComponents {
    onVariants { variant ->
        // Called for every variant
        if (variant.buildType == "release") {
            variant.outputs.forEach { output ->
                output.outputFileName.set("${variant.name}-${output.versionName.get()}.apk")
            }
        }
    }

    beforeVariants { variantBuilder ->
        // Disable specific variants you don't need
        if (variantBuilder.productFlavors.contains("dev" to "paid")) {
            variantBuilder.enable = false
        }
    }
}
```

---

## Kotlin JVM Toolchain (recommended over manual compileOptions)

```kotlin
kotlin {
    jvmToolchain(17)  // Sets sourceCompatibility, targetCompatibility, AND jvmTarget
}
// This replaces the manual compileOptions + kotlinOptions.jvmTarget pattern
```
