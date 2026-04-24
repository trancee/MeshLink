@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
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
    // allopen must precede benchmark — JMH requires benchmark classes to be open.
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
}

kotlin {
    // Android-KMP plugin (AGP 9.0+): android {} block is inside kotlin {}, not top-level.
    android {
        namespace = "ch.trancee.meshlink"
        compileSdk = 36
        minSdk = 29

        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.flatbuffers)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        // jvmMain is test/build infrastructure only — not a shipping target.
        // kotlinx-benchmark-runtime is required here for PlaceholderBenchmark and future
        // benchmarks.
        jvmMain.dependencies { implementation(libs.kotlinx.benchmark.runtime) }
    }
}

// Kover — 100% line + branch coverage on shipping source sets.
// jvmMain is test-only infrastructure and excluded from measurement.
kover {
    // jvmMain: test-only infrastructure — not a shipping target.
    // androidMain: S02 will add JNI implementation + Android unit tests; excluded until then.
    // iosMain: S03 will add cinterop implementation + iOS tests; excluded until then.
    currentProject { sources { excludedSourceSets.addAll("jvmMain", "androidMain", "iosMain") } }
    reports {
        // Exclude Android/iOS platform stubs; covered by platform test suites in S02/S03.
        // Exclude benchmark infrastructure — benchmarks are not production code and have
        // no test coverage by design. Same mechanism needed as for Android/iOS stubs because
        // Kover 0.9.8 instruments the full JVM compilation even when jvmMain is in
        // excludedSourceSets.
        filters {
            excludes {
                classes("ch.trancee.meshlink.crypto.AndroidCryptoProvider*")
                classes("ch.trancee.meshlink.crypto.IosCryptoProvider*")
                classes("ch.trancee.meshlink.benchmark.*")
            }
        }
        verify {
            rule("100% line coverage") {
                bound {
                    minValue = 100
                    coverageUnits = CoverageUnit.LINE
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                }
            }
            rule("100% branch coverage") {
                bound {
                    minValue = 100
                    coverageUnits = CoverageUnit.BRANCH
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}

ktfmt { kotlinLangStyle() }

// Detekt — static analysis configuration. Config at detekt.yml in project root.
detekt {
    config.setFrom(rootDir.resolve("detekt.yml"))
    buildUponDefaultConfig = true
}

// BCV — public ABI tracking.
// KLib validation (iOS) requires macOS/Xcode; disabled automatically on Linux CI.
// On macOS, iOS KLib artifacts are produced and validated per spec/13 §4.
val isMacOs = System.getProperty("os.name").contains("Mac OS X", ignoreCase = true)

apiValidation { klib { enabled = isMacOs } }

// Kotlin Power Assert — transform kotlin.test assertions for richer failure diagnostics.
powerAssert {
    functions =
        listOf(
            "kotlin.assert",
            "kotlin.test.assertEquals",
            "kotlin.test.assertNotEquals",
            "kotlin.test.assertTrue",
            "kotlin.test.assertFalse",
            "kotlin.test.assertNull",
            "kotlin.test.assertNotNull",
        )
}

// allopen — JMH requires benchmark classes to be open (non-final).
// The benchmark plugin uses JMH under the hood on JVM; @State-annotated classes must be open.
allOpen { annotation("org.openjdk.jmh.annotations.State") }

// kotlinx-benchmark — JVM target only for M001 bootstrap.
// Native benchmarks are post-v1 per spec/12 §B.
// The umbrella task `benchmark` delegates to `jvmBenchmark`.
benchmark { targets { register("jvm") } }
