import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.dokka)
    alias(libs.plugins.skie)
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

    android {
        namespace = "ch.trancee.meshlink.reference"
        compileSdk = 36
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        androidResources { enable = true }
        withHostTest {}
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "MeshLinkReference"
            isStatic = true
            binaryOption("bundleId", "ch.trancee.meshlink.reference")
            export(project(":meshlink"))
        }
    }

    applyDefaultHierarchyTemplate()

    compilerOptions { progressiveMode.set(true) }

    sourceSets {
        commonMain.dependencies {
            api(project(":meshlink"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
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
            implementation(libs.compose.ui.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(compose.desktop.currentOs)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        iosTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
    exclude("**/build/**")
    exclude { element ->
        element.file.invariantSeparatorsPath.contains(
            "/meshlink-reference/build/generated/compose/resourceGenerator/"
        )
    }
}

ktfmt { kotlinLangStyle() }

dokka {
    dokkaPublications.html {
        moduleName.set("MeshLink Reference App Shared")
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    }
}

skie { analytics { disableUpload.set(true) } }

compose.resources {
    publicResClass = true
    packageOfResClass = "ch.trancee.meshlink.reference.resources"
}

val localCheck by tasks.registering {
    group = "verification"
    description =
        "Runs the fast local verification bundle for the reference app without release packaging."
    dependsOn("check", "linkDebugFrameworkIosArm64", "linkDebugFrameworkIosSimulatorArm64")
}

tasks.named("ktfmtFormat") { dependsOn(":meshlink-reference:android:ktfmtFormat") }

tasks.named("check") { dependsOn(":meshlink-reference:android:check") }

tasks.named("build") { dependsOn(":meshlink-reference:android:build") }

val installDebug by tasks.registering {
    group = "install"
    description = "Installs the Android debug reference app from the nested Android host module."
    dependsOn(":meshlink-reference:android:installDebug")
}

val connectedDebugAndroidTest by tasks.registering {
    group = "verification"
    description = "Runs Android connected debug tests for the nested reference Android host module."
    dependsOn(":meshlink-reference:android:connectedDebugAndroidTest")
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
    includedSourceSets = listOf("commonTest", "jvmTest", "androidHostTest", "iosTest")
}
