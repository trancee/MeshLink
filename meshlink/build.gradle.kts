import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.power.assert)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.dokka)
    alias(libs.plugins.skie)
    alias(libs.plugins.mavenPublish)
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    explicitApi()
    val meshLinkXCFramework = XCFramework("MeshLink")
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
            meshLinkXCFramework.add(this)
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

// The detekt Gradle plugin registers a separate, type-resolution-enabled task per Kotlin target
// and source set for multiplatform projects (detektAndroidMain, detektJvmMain,
// detektMetadataCommonMain, ...) instead of wiring them all into the single top-level `detekt`
// task -- that top-level task has no source files configured at all for this module and always
// no-ops (`NO-SOURCE`). CI must invoke the real per-variant tasks (or the `detektAll` aggregate
// registered below) instead of the top-level `detekt` task, which passes trivially regardless of
// code quality. See docs/explanation/detekt-ci-gate-gap.md for the full history of why this was
// needed.
tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
    // The plugin's default baseline-file naming keys only off the Kotlin source set name (e.g.
    // both detektAndroidMain and detektJvmMain default to `detekt-baseline-main.xml`), so without
    // an explicit per-task path here, generating a baseline for one target's `main` source set
    // would silently overwrite another target's baseline for its own, completely different `main`
    // source set. Keying off the unique task name instead avoids that collision.
    //
    // Only set when the file already exists: Gradle validates a configured `baseline` RegularFile
    // input actually exists once a task has real source to analyze (a NO-SOURCE task skips that
    // check, which is why this is safe to leave unset for targets that cannot be analyzed at all
    // on the machine currently configuring the build, e.g. iOS/Apple/Native targets on a Linux
    // CI runner or dev machine without Xcode). A target that *does* gain source on some other
    // machine (a Mac, say) and has no baseline file yet simply runs with zero pre-existing issues
    // suppressed, which is the correct, honest default rather than silently reusing an unrelated
    // target's baseline or failing the build outright.
    val baselineFile =
        layout.projectDirectory.file("config/detekt/baseline-${baselineVariantName(name)}.xml")
    if (baselineFile.asFile.exists()) {
        baseline.set(baselineFile)
    }
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    baseline.set(
        layout.projectDirectory.file(
            "config/detekt/baseline-${baselineVariantName(name, isBaselineTask = true)}.xml"
        )
    )
}

// detektAll is the real, CI-facing aggregate: every per-variant Detekt task (detektAndroidMain,
// detektJvmMain, detektMetadataCommonMain, detektIosArm64Main, ...), always run together so a
// change can't pass CI by accident just because CI only happened to invoke one variant, the way
// the no-op top-level `detekt` task did before this change.
tasks.register("detektAll") { dependsOn(tasks.withType<Detekt>()) }

ktfmt { kotlinLangStyle() }

dokka {
    dokkaPublications.html {
        moduleName.set("MeshLink")
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    }
}

skie { analytics { disableUpload.set(true) } }

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml")))
    pom {
        name.set("MeshLink")
        description.set("Offline-first BLE mesh SDK for Android and iOS.")
        inceptionYear.set("2026")
        url.set("https://github.com/trancee/MeshLink")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        scm {
            url.set("https://github.com/trancee/MeshLink")
            connection.set("scm:git:https://github.com/trancee/MeshLink.git")
            developerConnection.set("scm:git:ssh://git@github.com:trancee/MeshLink.git")
        }
        developers {
            developer {
                id.set("trancee")
                name.set("Trancee")
                url.set("https://github.com/trancee")
            }
        }
    }
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

tasks.withType<Test>().configureEach {
    systemProperty("meshlink.ci", providers.environmentVariable("CI"))
}

// Maps a Detekt/DetektCreateBaselineTask Gradle task name to a stable, unique baseline-file
// variant slug, e.g. "detektAndroidMain" -> "androidMain", "detektBaselineJvmTest" ->
// "jvmTest". Used to give every per-target/source-set task its own baseline file (see the
// `tasks.withType<Detekt>()`/`tasks.withType<DetektCreateBaselineTask>()` configuration above).
fun baselineVariantName(taskName: String, isBaselineTask: Boolean = false): String {
    val variant =
        if (isBaselineTask) taskName.removePrefix("detektBaseline")
        else taskName.removePrefix("detekt")
    return variant.replaceFirstChar { it.lowercase() }
}
