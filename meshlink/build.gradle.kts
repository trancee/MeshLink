import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("meshlink.published-library")
    id("meshlink.build-info")
    id("meshlink.robolectric")
    id("co.touchlab.skie")
}

description = "MeshLink: a Bluetooth Low Energy peer-to-peer mesh library for Android and iOS."

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":meshlink-core"))
        implementation(project(":meshlink-crypto-sodium"))
    }
    // ADR 0011, 2026-11-13 amendment "coordinates and names": Swift module MeshLinkKit, bundle ID ch.trancee.meshlink.kit.
    val meshLinkKit = XCFramework("MeshLinkKit")
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "MeshLinkKit"
            binaryOption("bundleId", "ch.trancee.meshlink.kit")
            meshLinkKit.add(this)
        }
    }
}

skie {
    analytics {
        disableUpload.set(true)
    }
    // The XCFramework is distributed, so its Swift module needs library evolution (a .swiftinterface).
    build {
        produceDistributableFramework()
    }
}
