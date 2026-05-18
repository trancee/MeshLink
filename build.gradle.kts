import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.tasks.wrapper.Wrapper

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlin.power.assert) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
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
    ignoredProjects.addAll(listOf("benchmarks", "meshlink-proof-android-app"))
    apiDumpDirectory = "api"
}

dependencies {
    kover(project(":meshlink"))
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

tasks.wrapper {
    gradleVersion = "8.9"
    distributionType = Wrapper.DistributionType.ALL
}
