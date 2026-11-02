import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform") version "2.4.21"
    id("co.touchlab.skie") version "0.10.16"
}

group = "ch.trancee.meshlink"
version = "0.0.0-prototype"

val sodiumOut = layout.projectDirectory.dir("libsodium/out")

fun KotlinNativeTarget.sodium(platform: String) {
    compilations.getByName("main").cinterops.create("sodium") {
        definitionFile.set(project.file("src/nativeInterop/cinterop/sodium.def"))
        includeDirs(sodiumOut.dir("$platform/include"))
        extraOpts("-libraryPath", sodiumOut.dir("$platform/lib").asFile.path)
    }
}

kotlin {
    jvmToolchain(21)

    val xcFramework = XCFramework("MeshLinkKit")
    iosArm64 {
        sodium("iosArm64")
        binaries.framework {
            baseName = "MeshLinkKit"
            binaryOption("bundleId", "ch.trancee.meshlink.kit")
            xcFramework.add(this)
        }
    }
    macosArm64 {
        sodium("macosArm64")
    }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

skie {
    analytics {
        disableUpload.set(true)
    }
}
