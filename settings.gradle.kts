pluginManagement {
    // Resolve plugin ids directly to their implementation artifacts so CI does not depend on
    // separate marker-module lookups during cold starts.
    val directPluginImplementationModules =
        mapOf(
            "org.jetbrains.kotlin.multiplatform" to "org.jetbrains.kotlin:kotlin-gradle-plugin",
            "org.jetbrains.kotlin.plugin.allopen" to "org.jetbrains.kotlin:kotlin-allopen",
            "org.jetbrains.kotlin.plugin.power-assert" to "org.jetbrains.kotlin:kotlin-power-assert",
            "org.jetbrains.kotlin.plugin.serialization" to "org.jetbrains.kotlin:kotlin-serialization",
            "org.jetbrains.kotlin.plugin.compose" to "org.jetbrains.kotlin:compose-compiler-gradle-plugin",
            "com.android.kotlin.multiplatform.library" to "com.android.tools.build:gradle",
            "com.android.application" to "com.android.tools.build:gradle",
            "org.jetbrains.compose" to "org.jetbrains.compose:compose-gradle-plugin",
            "io.gitlab.arturbosch.detekt" to "io.gitlab.arturbosch.detekt:detekt-gradle-plugin",
            "com.ncorti.ktfmt.gradle" to "com.ncorti.ktfmt.gradle:plugin",
            "org.jetbrains.dokka" to "org.jetbrains.dokka:dokka-gradle-plugin",
            "co.touchlab.skie" to "co.touchlab.skie:gradle-plugin",
            "org.jetbrains.kotlinx.benchmark" to "org.jetbrains.kotlinx:kotlinx-benchmark-plugin",
            "org.jetbrains.kotlinx.binary-compatibility-validator" to
                "org.jetbrains.kotlinx:binary-compatibility-validator",
            "org.jetbrains.kotlinx.kover" to "org.jetbrains.kotlinx:kover-gradle-plugin",
        )

    resolutionStrategy {
        eachPlugin {
            val moduleCoordinate = directPluginImplementationModules[requested.id.id] ?: return@eachPlugin
            val pluginVersion = requested.version ?: return@eachPlugin
            useModule("$moduleCoordinate:$pluginVersion")
        }
    }

    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "meshlink"

include(":meshlink")
include(":benchmarks")
include(":meshlink-reference")
include(":meshlink-reference:android")
include(":meshlink-proof:android")

project(":meshlink-reference").projectDir = file("meshlink-reference")
project(":meshlink-reference:android").projectDir = file("meshlink-reference/android")
project(":meshlink-proof:android").projectDir = file("meshlink-proof/android")
