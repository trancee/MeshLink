import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    id("meshlink.published-library")
    id("meshlink.build-info")
    id("meshlink.robolectric")
    id("co.touchlab.skie")
}

description = "MeshLink: a Bluetooth Low Energy peer-to-peer mesh library for Android and iOS."

val exportedSymbols = layout.projectDirectory.file("MeshLinkKit.exports")

tasks.withType<KotlinNativeLink>().configureEach {
    inputs.file(exportedSymbols).withPathSensitivity(PathSensitivity.NONE)
}

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
            // ADR 0004 "Binding": the framework exports only its Objective-C and Swift API, never the
            // libsodium (or Kotlin runtime) symbols linked into it.
            linkerOpts("-exported_symbols_list", exportedSymbols.asFile.absolutePath)
            binaryOption("bundleId", "ch.trancee.meshlink.kit")
            meshLinkKit.add(this)
        }
    }
}

// ADR 0004 "Binding": the release XCFramework's slices export no libsodium symbol and touch no AES,
// AEGIS or IPCrypt symbol. Needs xcrun, so the macOS CI job runs it by name.
tasks.register<ch.trancee.meshlink.buildlogic.CheckNativeSymbols>("checkFrameworkSymbols") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if a MeshLinkKit slice exports libsodium or touches AES, AEGIS or IPCrypt symbols."
    // The release link outputs, which the XCFramework copies: re-assembling the XCFramework in a
    // second Gradle invocation fails while :meshlink has no Swift API (SKIE leaves an empty
    // swiftmodule directory behind).
    kotlin.targets.withType<KotlinNativeTarget>().forEach { target ->
        val framework = target.binaries.getFramework(NativeBuildType.RELEASE)
        binaries.from(framework.linkTaskProvider.map { it.outputFile.get().resolve("MeshLinkKit") })
    }
    nm.set(listOf("xcrun", "nm"))
    report.set(layout.buildDirectory.file("reports/native/framework-symbols.txt"))
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
