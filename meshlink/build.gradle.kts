@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalBCVApi::class)

import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.validation.ExperimentalBCVApi
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

        // withHostTest {} suppresses AGP's "androidHostTest source directory exists" warning.
        // The commonTest suite calls createCryptoProvider() which resolves to AndroidCryptoProvider
        // on the Android target — requiring the libsodium JNI library that is not available on a
        // JVM host runner. KMP does not allow test source sets to override main-source-set actuals,
        // so testAndroidHostTest is filtered to zero tests until S02 wires a JVM-backed actual.
        withHostTest {}
    }

    val iosArm64 = iosArm64()
    val iosSimulatorArm64 = iosSimulatorArm64()

    listOf(iosArm64, iosSimulatorArm64).forEach { target ->
        target.compilations.getByName("main").cinterops {
            val libsodium by creating {
                defFile(project.file("src/iosMain/interop/libsodium.def"))
                includeDirs("src/iosMain/interop/include")
                extraOpts("-libraryPath", "${projectDir}/src/iosMain/interop/lib/${target.name}")
            }
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies { implementation(libs.kotlinx.coroutines.core) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // jvmMain is test/build infrastructure only — not a shipping target.
        // flatbuffers-java is JVM-only (no Kotlin/Native variant); moved from commonMain so
        // iOS compilation is not broken. Pure-Kotlin codec in commonMain/wire/ handles runtime
        // serialisation; flatbuffers-java is available here for benchmarks only.
        jvmMain.dependencies {
            implementation(libs.kotlinx.benchmark.runtime)
            implementation(libs.flatbuffers)
        }
    }
}

// testAndroidHostTest: skip until S02 adds a JVM-backed actual for Android host tests.
// All commonTest coverage is provided by jvmTest; no tests are lost by this filter.
tasks
    .matching { it.name == "testAndroidHostTest" }
    .configureEach {
        (this as? org.gradle.api.tasks.testing.Test)?.apply {
            filter.excludeTestsMatching("*")
            filter.isFailOnNoMatchingTests = false
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
                // SodiumJni: JNI bridge object — cannot run on JVM host (requires Android .so).
                // Correctness verified structurally: same libsodium calls, same symbols exported.
                classes("ch.trancee.meshlink.crypto.SodiumJni*")
                classes("ch.trancee.meshlink.crypto.IosCryptoProvider*")
                // JvmCryptoProvider: test-only infrastructure — JDK shim used by jvmTest only.
                // Kover 0.9.8 instruments jvmMain bytecode even with excludedSourceSets("jvmMain").
                classes("ch.trancee.meshlink.crypto.JvmCryptoProvider*")
                classes("ch.trancee.meshlink.benchmark.*")
                // Platform storage stubs — full implementations deferred to M002+.
                // Excluded because they throw NotImplementedError and have no test coverage by
                // design; koverGenerateArtifactAndroid instruments androidMain bytecode regardless
                // of excludedSourceSets.
                classes("ch.trancee.meshlink.storage.AndroidSecureStorage*")
                classes("ch.trancee.meshlink.storage.IosSecureStorage*")
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
benchmark {
    configurations {
        // "main" — used by ./gradlew benchmark / jvmBenchmark for local dev.
        // Reduced from the 10 s per-iteration default; still accurate enough for
        // local profiling of crypto and wire-format hot paths.
        named("main") {
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
        // "ci" — used by ./gradlew jvmCiBenchmark in CI.
        // Short iteration time keeps the benchmark job under ~30 s while still
        // detecting large regressions (>15% per spec/12 §B).
        register("ci") {
            warmups = 2
            iterations = 3
            iterationTime = 300
            iterationTimeUnit = "ms"
        }
    }
    targets { register("jvm") }
}
