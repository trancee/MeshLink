import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    id("meshlink.published-library")
    id("meshlink.build-info")
    id("meshlink.robolectric")
    id("meshlink.sodium-host-jni")
    id("co.touchlab.skie")
}

description = "MeshLink: a Bluetooth Low Energy peer-to-peer mesh library for Android and iOS."

val exportedSymbols = layout.projectDirectory.file("MeshLinkKit.exports")

tasks.withType<KotlinNativeLink>().configureEach {
    inputs.file(exportedSymbols).withPathSensitivity(PathSensitivity.NONE)
}

kotlin {
    // iosAdaptersMain holds the iOS thin adapters that both iOS targets share, so the simulator tests
    // in iosAdaptersTest reach them; Dokka suppresses it (below).
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common { group("ios") { group("iosAdapters") { withIos() } } }
    }
    sourceSets.commonMain.dependencies {
        api(project(":meshlink-core"))
        implementation(project(":meshlink-crypto-sodium"))
    }
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
    // ADR 0011, 2026-11-13 amendment "coordinates and names": Swift module MeshLinkKit, bundle ID ch.trancee.meshlink.kit.
    val meshLinkKit = XCFramework("MeshLinkKit")
    targets.withType<KotlinNativeTarget>().configureEach {
        // ADR 0020 "Platform log": the os_log wrapper IosOsLog calls.
        compilations.getByName("main").cinterops.create("oslog") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/oslog.def"))
        }
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

// iosAdaptersMain and the two iOS target source sets hold only thin adapters: internal ones and
// DataBridge, which only the Swift overlay calls and Swift hides (ADR 0007), so they add nothing to
// the docs. Dokka 2.3.0 still resolves the KDoc QueueDispatcher inherits from
// CoroutineDispatcher, whose links don't resolve from iosArm64Main, and on a Linux host, which has no
// iOS platform libraries, it can't resolve the cinterop types in iosAdaptersMain; failOnWarning fails
// on both. Dokka needs the source sets that depend on a suppressed one suppressed too.
dokka {
    val adapters = setOf("iosAdaptersMain", "iosArm64Main", "iosSimulatorArm64Main")
    dokkaSourceSets.configureEach { if (name in adapters) suppress.set(true) }
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

// ADR 0006 decision 3: ConfigReferenceTest generates docs/reference/configuration.md and presets.md, and
// DiagnosticReferenceTest docs/reference/diagnostics.md and docs/agents/diagnostics.json and
// DiagnosticsExportReferenceTest docs/reference/diagnostics-export.md (ADR 0020), from the
// Kotlin source; TroubleshootingReferenceTest fills the code tables of docs/how-to/troubleshoot-*.md. In testAndroidHostTest they fail when a committed file differs; updateReference rewrites them.
val repositoryRoot: String = rootDir.path
val referencePages = rootProject.layout.projectDirectory.dir("docs/reference")
val agentPages = rootProject.layout.projectDirectory.dir("docs/agents")
val howToPages = rootProject.layout.projectDirectory.dir("docs/how-to")
tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    systemProperty("reference.root", repositoryRoot)
    // The pages come from KDoc, which the compiled classes don't carry.
    inputs.dir(layout.projectDirectory.dir("src/commonMain/kotlin")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .files(
            referencePages.file("configuration.md"),
            referencePages.file("presets.md"),
            referencePages.file("diagnostics.md"),
            referencePages.file("diagnostics-export.md"),
            agentPages.file("diagnostics.json"),
            howToPages.file("troubleshoot-peers-not-found.md"),
            howToPages.file("troubleshoot-messages-not-delivered.md"),
            howToPages.file("troubleshoot-high-battery-use.md"),
        ).withPathSensitivity(PathSensitivity.RELATIVE)
}
tasks.register<Test>("updateReference") {
    val hostTests = tasks.named<Test>("testAndroidHostTest").get()
    group = "documentation"
    description = "Rewrites the generated reference pages, troubleshooting tables and docs/agents/diagnostics.json."
    // The collections, not the task's providers, so the stale-page check doesn't run first and fail.
    testClassesDirs = hostTests.testClassesDirs
    classpath = hostTests.classpath
    useJUnit()
    filter {
        includeTestsMatching("*ConfigReferenceTest")
        includeTestsMatching("*DiagnosticReferenceTest")
        includeTestsMatching("*DiagnosticsExportReferenceTest")
        includeTestsMatching("*TroubleshootingReferenceTest")
    }
    systemProperty("reference.root", repositoryRoot)
    systemProperty("reference.update", "true")
    outputs.upToDateWhen { false }
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
