# KMP Gradle Configuration

<basic_setup>
## Minimal KMP Module Configuration

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.android.library") // only if targeting Android
}

kotlin {
    // Declare targets
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    jvm()

    // Source sets and dependencies
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
```
</basic_setup>

<target_declaration>
## Target Declaration

Targets are declared with DSL calls inside the `kotlin {}` block:

```kotlin
kotlin {
    // Mobile
    androidTarget()
    iosArm64()               // Physical iOS devices
    iosSimulatorArm64()      // iOS simulator on Apple Silicon
    iosX64()                 // iOS simulator on Intel

    // Desktop
    jvm()
    macosArm64()
    macosX64()
    linuxX64()
    mingwX64()               // Windows

    // Web
    js(IR) { browser() }
    wasmJs { browser() }
}
```

Each declared target creates corresponding `*Main` and `*Test` source sets automatically.
</target_declaration>

<source_set_configuration>
## Source Set Configuration

Access and configure source sets inside `kotlin.sourceSets {}`:

```kotlin
kotlin {
    sourceSets {
        // Common
        commonMain.dependencies { /* ... */ }
        commonTest.dependencies { /* ... */ }

        // Platform-specific
        androidMain.dependencies { /* ... */ }
        iosMain.dependencies { /* ... */ }
        jvmMain.dependencies { /* ... */ }

        // Custom intermediate source set (rare)
        val mobileMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(mobileMain)
        iosMain.get().dependsOn(mobileMain)
    }
}
```
</source_set_configuration>

<compiler_options>
## Compiler Options

```kotlin
kotlin {
    compilerOptions {
        // Apply to all source sets
        freeCompilerArgs.add("-Xexpect-actual-classes")  // Enable expect/actual classes (Beta)
    }

    // Per-target options
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}
```
</compiler_options>

<ios_framework>
## iOS Framework Export

```kotlin
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SharedModule"
            isStatic = true
        }
    }
}
```
</ios_framework>

<hierarchical_structure>
## Default Hierarchy Template

Kotlin automatically creates intermediate source sets based on declared targets. For a project with `androidTarget()`, `iosArm64()`, and `iosSimulatorArm64()`:

```
commonMain
├── androidMain
├── nativeMain
│   └── appleMain
│       └── iosMain
│           ├── iosArm64Main
│           └── iosSimulatorArm64Main
```

This hierarchy is created automatically — you rarely need to configure it manually.
</hierarchical_structure>

<android_specifics>
## Android-Specific Configuration

```kotlin
android {
    namespace = "com.example.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```
</android_specifics>
