import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.tasks.wrapper.Wrapper

plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlin.power.assert) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.benchmark) apply false
    alias(libs.plugins.binary.compatibility)
    alias(libs.plugins.kover)
}

allprojects {
    group = "ch.trancee.meshlink"
    version = "0.1.0-SNAPSHOT"
}

apiValidation {
    ignoredProjects.addAll(listOf("benchmarks", "meshlink-reference", "meshlink-proof-android-app"))
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

val checkGeneratedPublicApiReference by
    tasks.registering(Exec::class) {
        group = "verification"
        description = "Fails if docs/reference/generated-public-api.md is out of sync with the API dump."
        workingDir = rootDir
        commandLine("python3", "scripts/check_generated_public_api_reference.py")
    }

val checkMarkdownLinks by
    tasks.registering(Exec::class) {
        group = "verification"
        description = "Fails if repository markdown files contain broken relative links."
        workingDir = rootDir
        commandLine("python3", "scripts/check_markdown_links.py")
    }

val verifyDocs by
    tasks.registering {
        group = "verification"
        description = "Runs the repository documentation verification checks."
        dependsOn(checkGeneratedPublicApiReference, checkMarkdownLinks)
    }

tasks.named("check") {
    dependsOn(verifyDocs)
}

tasks.wrapper {
    gradleVersion = "8.11.1"
    distributionType = Wrapper.DistributionType.BIN
}
