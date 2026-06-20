import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.benchmark)
    alias(libs.plugins.ktfmt)
}

kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":meshlink"))
            implementation(libs.kotlinx.benchmark.runtime)
        }
    }
}

allOpen { annotation("org.openjdk.jmh.annotations.State") }

benchmark {
    targets { register("jvm") }
    configurations {
        named("main") {
            iterations = 5
            iterationTime = 300
            iterationTimeUnit = "ms"
            warmups = 3
        }
        register("smoke") {
            iterations = 2
            iterationTime = 150
            iterationTimeUnit = "ms"
            warmups = 1
        }
    }
}

ktfmt { kotlinLangStyle() }
