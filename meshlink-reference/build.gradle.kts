import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ReferenceAppShared"
            isStatic = true
            binaryOption("bundleId", "ch.trancee.meshlink.reference")
        }
    }

    applyDefaultHierarchyTemplate()

    compilerOptions { progressiveMode.set(true) }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":meshlink"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.material.icons.extended)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            @OptIn(ExperimentalComposeLibrary::class) implementation(compose.uiTest)
        }
        androidMain.dependencies { implementation(libs.androidx.activity.compose) }
        androidInstrumentedTest.dependencies {
            implementation("androidx.test:core:1.6.1")
            implementation("androidx.test.ext:junit:1.2.1")
            implementation("androidx.test:runner:1.6.2")
        }
        androidUnitTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        iosTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "ch.trancee.meshlink.reference"
    compileSdk = 35

    defaultConfig {
        applicationId = "ch.trancee.meshlink.reference"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    installation { installOptions += "-g" }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
}

tasks.withType<Detekt>().configureEach { jvmTarget = "17" }

ktfmt { kotlinLangStyle() }

compose.resources {
    publicResClass = true
    packageOfResClass = "ch.trancee.meshlink.reference.resources"
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    functions =
        listOf(
            "kotlin.assert",
            "kotlin.test.assertEquals",
            "kotlin.test.assertFalse",
            "kotlin.test.assertTrue",
        )
    includedSourceSets = listOf("commonTest", "androidUnitTest", "iosTest")
}
