import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
}

android {
    namespace = "ch.trancee.meshlink.reference.androidapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.trancee.meshlink.reference"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        multiDexEnabled = true
        multiDexKeepProguard = file("multidex-keep.pro")
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    installation { installOptions += "-g" }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

dependencies {
    implementation(project(":meshlink-reference"))
    implementation(libs.androidx.activity.compose)
    implementation("androidx.multidex:multidex:2.0.1")
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
}

tasks.withType<Detekt>().configureEach { jvmTarget = "21" }

ktfmt { kotlinLangStyle() }
