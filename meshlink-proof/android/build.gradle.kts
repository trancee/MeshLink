import org.jetbrains.kotlin.gradle.dsl.JvmTarget

private val BENCHMARK_ANNOTATION = "ch.trancee.meshlink.proof.android.ProofBenchmarkTest"
private val RUN_PROOF_BENCHMARKS = providers.gradleProperty("meshlinkProofRunBenchmarks").map { it.toBoolean() }.orElse(false)

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ch.trancee.meshlink.proof.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.trancee.meshlink.proof.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (RUN_PROOF_BENCHMARKS.get()) {
            testInstrumentationRunnerArguments["annotation"] = BENCHMARK_ANNOTATION
        } else {
            testInstrumentationRunnerArguments["notAnnotation"] = BENCHMARK_ANNOTATION
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    installation {
        installOptions += "-g"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":meshlink"))

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    enabled = RUN_PROOF_BENCHMARKS.get()
}
