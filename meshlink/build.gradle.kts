import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // AGP 9.0+: use the dedicated Android-KMP library plugin (not com.android.library).
    // The android {} block lives inside kotlin {}, not at the top level.
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.bcv)
    alias(libs.plugins.kotlin.power.assert)
}

kotlin {
    // Android-KMP plugin (AGP 9.0+): android {} block is inside kotlin {}, not top-level.
    android {
        namespace = "ch.trancee.meshlink"
        compileSdk = 36
        minSdk = 29

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.flatbuffers)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Kover — coverage rules (100% line + branch) are configured in subsequent tasks.
kover {
}

ktfmt {
    kotlinLangStyle()
}
