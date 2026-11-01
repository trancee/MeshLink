import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform") version "2.4.21"
    id("co.touchlab.skie") version "0.10.16"
}

group = "ch.trancee.meshlink"
version = "0.0.0-prototype"

kotlin {
    explicitApi()
    jvmToolchain(21)

    jvm()

    val xcFramework = XCFramework("MeshLinkKit")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "MeshLinkKit"
            binaryOption("bundleId", "ch.trancee.meshlink.kit")
            xcFramework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}

skie {
    analytics {
        disableUpload.set(true)
    }
    build {
        produceDistributableFramework()
    }
}
