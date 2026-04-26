import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // iOS targets — source structure required; native compilation skipped on Linux
    // via kotlin.native.ignoreDisabledTargets=true in gradle.properties.
    iosArm64 {
        binaries.framework {
            baseName = "MeshLinkSample"
            isStatic = true
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "MeshLinkSample"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.navigation.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(project(":meshlink"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}

// Top-level android {} block — application module DSL (NOT inside kotlin{}).
// AGP 9.0+: do NOT apply org.jetbrains.kotlin.android alongside com.android.application.
android {
    namespace = "ch.trancee.meshlink.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.trancee.meshlink.sample"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // FORCE_GATT=true skips L2CAP and uses GATT for all connections (used in S06).
        // Pass via: ./gradlew :meshlink-sample:assembleDebug -PFORCE_GATT=true
        buildConfigField(
            "boolean",
            "FORCE_GATT",
            project.findProperty("FORCE_GATT")?.toString() ?: "false",
        )
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
