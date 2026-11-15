import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("meshlink.kmp-library")
}

description = "The MeshLink lab scenarios, shared by the Android and iOS lab apps; never published."

kotlin {
    compilerOptions {
        optIn.add("ch.trancee.meshlink.InternalMeshLinkApi")
    }
    sourceSets.commonMain.dependencies {
        implementation(project(":meshlink"))
        implementation(libs.kotlinx.coroutines.core)
    }
    // The iOS lab app links LabKit, which embeds MeshLink; a static framework needs no embedding step.
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "LabKit"
            isStatic = true
            binaryOption("bundleId", "ch.trancee.lab.kit")
        }
    }
}
