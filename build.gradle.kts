import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.wrapper.Wrapper

buildscript {
    dependencies {
        classpath("org.slf4j:slf4j-nop:2.0.18")
    }
}

private fun isAgpDependencyResolutionWarningListener(listener: Any): Boolean {
    if (listener::class.java.name.startsWith("com.android.build.gradle.internal.DependencyResolutionChecksKt$")) {
        return true
    }
    val wrappedActionField =
        runCatching { listener::class.java.getDeclaredField("val\$action") }.getOrNull() ?: return false
    wrappedActionField.isAccessible = true
    val wrappedAction: Any = wrappedActionField.get(listener) ?: return false
    return wrappedAction::class.java.name.startsWith(
        "com.android.build.gradle.internal.DependencyResolutionChecksKt$"
    )
}

private fun Project.stripAgpDependencyResolutionWarningListeners(): Unit {
    configurations.configureEach {
        val configuration: org.gradle.api.artifacts.Configuration = this
        val dependencyResolutionListeners: Any =
            runCatching {
                    val getter = (configuration as Any)::class.java.getMethod("getDependencyResolutionListeners")
                    getter.invoke(configuration)
                }
                .getOrNull() ?: return@configureEach
        val visitListenersUntyped =
            dependencyResolutionListeners.javaClass.methods.firstOrNull {
                it.name == "visitListenersUntyped" && it.parameterCount == 1
            } ?: return@configureEach
        val removeListener =
            dependencyResolutionListeners.javaClass.methods.firstOrNull {
                it.name == "remove" && it.parameterCount == 1
            } ?: return@configureEach

        val listenersToRemove = mutableListOf<Any>()
        visitListenersUntyped.invoke(
            dependencyResolutionListeners,
            object : Action<Any> {
                override fun execute(listener: Any): Unit {
                    if (isAgpDependencyResolutionWarningListener(listener)) {
                        listenersToRemove += listener
                    }
                }
            },
        )
        listenersToRemove.forEach { listener -> removeListener.invoke(dependencyResolutionListeners, listener) }
    }
}

allprojects {
    // AGP registers configuration-time dependency warning listeners that currently fire for
    // upstream KMP, Dokka, SKIE, and related plugin task graphs. Strip only that AGP warning
    // listener so the repo stops reporting non-actionable upstream noise while leaving the task
    // graph itself unchanged.
    pluginManager.withPlugin("com.android.application") {
        stripAgpDependencyResolutionWarningListeners()
    }
    pluginManager.withPlugin("com.android.library") {
        stripAgpDependencyResolutionWarningListeners()
    }
    pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
        stripAgpDependencyResolutionWarningListeners()
    }
}

plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlin.power.assert) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.benchmark) apply false
    alias(libs.plugins.binary.compatibility)
    alias(libs.plugins.kover)
}

val versionName: String = providers.gradleProperty("VERSION_NAME").orElse("0.1.0-SNAPSHOT").get()

allprojects {
    group = "ch.trancee.meshlink"
    version = versionName
}

apiValidation {
    ignoredProjects.addAll(
        listOf(
            "benchmarks",
            "meshlink-reference",
            "android",
        )
    )
    apiDumpDirectory = "api"
}

dependencies {
    kover(project(":meshlink"))
    kover(project(":meshlink-reference"))
}

kover {
    reports {
        verify {
            rule("line coverage") {
                bound {
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                    coverageUnits = CoverageUnit.LINE
                    minValue = 100
                }
            }
            rule("branch coverage") {
                bound {
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                    coverageUnits = CoverageUnit.BRANCH
                    minValue = 100
                }
            }
        }
    }
}

val checkGeneratedPublicApiReference =
    tasks.register<Exec>("checkGeneratedPublicApiReference") {
        group = "verification"
        description = "Fails if docs/reference/generated-public-api.md is out of sync with the API dump."
        workingDir = rootDir
        commandLine("python3", "scripts/check_generated_public_api_reference.py")
    }

val checkMarkdownLinks =
    tasks.register<Exec>("checkMarkdownLinks") {
        group = "verification"
        description = "Fails if repository markdown files contain broken relative links."
        workingDir = rootDir
        commandLine("python3", "scripts/check_markdown_links.py")
    }

val checkAgp9Invariants =
    tasks.register<Exec>("checkAgp9Invariants") {
        group = "verification"
        description =
            "Fails if the post-migration AGP 9 module shape, plugin wiring, or compatibility tasks drift."
        workingDir = rootDir
        commandLine("python3", "scripts/check_agp9_invariants.py")
    }

val codeqlJavaKotlinBuild =
    tasks.register("codeqlJavaKotlinBuild") {
        group = "verification"
        description =
            "Builds the repository subset CodeQL needs for Java/Kotlin analysis without duplicating the workflow task graph."
        dependsOn(
            ":meshlink:jvmJar",
            ":meshlink:androidJar",
            ":benchmarks:jvmJar",
            ":meshlink-reference:jvmJar",
            ":meshlink-reference:bundleAndroidMainAar",
            ":meshlink-reference:android:compileDebugKotlin",
            ":meshlink-proof:android:compileDebugKotlin",
        )
    }

val cleanJvmSmokeBenchmarkReports =
    tasks.register<Delete>("cleanJvmSmokeBenchmarkReports") {
        group = "verification"
        description = "Deletes stale JVM smoke benchmark reports before the next verified smoke run."
        delete(layout.projectDirectory.dir("benchmarks/build/reports/benchmarks/smoke"))
    }

project(":benchmarks").tasks.configureEach {
    if (name == "jvmSmokeBenchmark") {
        dependsOn(cleanJvmSmokeBenchmarkReports)
    }
}

val checkJvmSmokeBenchmarkReport =
    tasks.register<Exec>("checkJvmSmokeBenchmarkReport") {
        group = "verification"
        description =
            "Fails if the latest JVM smoke benchmark report is missing any declared benchmark results."
        workingDir = rootDir
        dependsOn(":benchmarks:jvmSmokeBenchmark")
        commandLine("python3", "scripts/check_jvm_smoke_benchmark_report.py")
    }

val verifyJvmSmokeBenchmarks =
    tasks.register("verifyJvmSmokeBenchmarks") {
        group = "verification"
        description = "Runs the JVM smoke benchmark suite and verifies that every declared benchmark completed."
        dependsOn(checkJvmSmokeBenchmarkReport)
    }

val verifyDocs =
    tasks.register("verifyDocs") {
        group = "verification"
        description = "Runs the repository documentation verification checks."
        dependsOn(checkGeneratedPublicApiReference, checkMarkdownLinks)
    }

val dokkaGenerateAllHtml =
    tasks.register("dokkaGenerateAllHtml") {
        group = "documentation"
        description =
            "Generates Dokka HTML for the SDK and the shared reference-app module."
        dependsOn(
            ":meshlink:dokkaGenerateHtml",
            ":meshlink-reference:dokkaGenerateHtml",
        )
    }

tasks.named("check") {
    dependsOn(verifyDocs, checkAgp9Invariants)
}

tasks.wrapper {
    gradleVersion = "9.5.0"
    distributionType = Wrapper.DistributionType.BIN
}
