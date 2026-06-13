import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.dokka)
    alias(libs.plugins.skie)
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    explicitApi()
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    android {
        namespace = "ch.trancee.meshlink"
        compileSdk = 36
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        withHostTest {}
        withDeviceTestBuilder { sourceSetTreeName = "deviceTest" }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "MeshLink"
            isStatic = true
            binaryOption("bundleId", "ch.trancee.meshlink")
        }
    }
    applyDefaultHierarchyTemplate()

    compilerOptions { progressiveMode.set(true) }

    sourceSets {
        commonMain.dependencies { api(libs.kotlinx.coroutines.core) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        jvmTest.dependencies { implementation(libs.kotlin.test) }
        getByName("androidHostTest").dependencies { implementation(libs.kotlin.test) }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.test.runner)
        }
        iosTest.dependencies { implementation(libs.kotlin.test) }
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
}

tasks.withType<Detekt>().configureEach { jvmTarget = "21" }

ktfmt { kotlinLangStyle() }

dokka {
    dokkaPublications.html {
        moduleName.set("MeshLink")
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    }
}

skie { analytics { disableUpload.set(true) } }

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

tasks.withType<Test>().configureEach {
    systemProperty("meshlink.ci", providers.environmentVariable("CI"))
}
