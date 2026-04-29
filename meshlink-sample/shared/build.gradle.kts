import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Android target — uses com.android.kotlin.multiplatform.library DSL (no top-level android{}).
    android {
        namespace = "ch.trancee.meshlink.sample.shared"
        compileSdk = 36
        minSdk = 31
    }

    // iOS targets — native compilation only runs on macOS; skipped on Linux via
    // kotlin.native.ignoreDisabledTargets=true in gradle.properties.
    iosArm64("ios") {
        binaries.framework {
            baseName = "MeshLinkSample"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.material.icons.extended)
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
