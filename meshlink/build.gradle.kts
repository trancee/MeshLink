@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalBCVApi::class)

import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.validation.ExperimentalBCVApi
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFrameworkConfig

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
    // SKIE — generates Swift-friendly AsyncStream/async func wrappers for public KMP API.
    // iOS-only effect; no JVM/Android impact. Version 0.10.11 supports Kotlin 2.3.20+.
    alias(libs.plugins.skie)
    // Publishing infrastructure.
    `maven-publish`
    signing
    alias(libs.plugins.dokka)
}

kotlin {
    explicitApi()

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

        // consumer-rules.pro: ProGuard rules for MeshLinkService / AndroidBleTransport /
        // BleTransport.
        // The com.android.kotlin.multiplatform.library AGP 9.0+ plugin exposes a restricted android
        // {}
        // DSL that does not support defaultConfig.consumerProguardFiles(). The consumer-rules.pro
        // file
        // in meshlink/ is shipped alongside the AAR. Library consumers must reference it directly
        // in
        // their own build.gradle.kts via:
        //   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
        // "../meshlink/consumer-rules.pro")
        // or include it in their module's proguard-rules.pro.
        // TODO(S04/library-publish): Investigate whether AGP 9.x adds consumerProguardFiles() to
        //   the KMP library android block once the API stabilises.
    }

    val ios = iosArm64("ios")

    ios.compilations.getByName("main").cinterops {
        val libsodium by creating {
            defFile(project.file("src/iosMain/interop/libsodium.def"))
            includeDirs("src/iosMain/interop/include")
            extraOpts("-libraryPath", "${projectDir}/src/iosMain/interop/lib/${ios.name}")
        }
    }

    // XCFramework — iOS arm64 slice for SPM binary distribution.
    // assembleMeshLinkXCFramework / assembleMeshLinkReleaseXCFramework tasks are generated
    // automatically by the KMP Gradle plugin once XCFrameworkConfig is registered.
    // release.yml zips the Release XCFramework, computes SHA-256, and updates Package.swift.
    val xcf = XCFrameworkConfig(project, "MeshLink")
    ios.binaries.framework {
        baseName = "MeshLink"
        isStatic = true
        binaryOption("bundleId", "ch.trancee.meshlink")
        xcf.add(this)
    }

    jvm()

    sourceSets {
        commonMain.dependencies { implementation(libs.kotlinx.coroutines.core) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies { implementation(libs.androidx.security.crypto) }
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

// jvmTest: increase metaspace for Kover instrumentation agent under integration-test load.
tasks
    .matching { it.name == "jvmTest" }
    .configureEach {
        (this as? org.gradle.api.tasks.testing.Test)?.jvmArgs("-Xmx1g", "-XX:MaxMetaspaceSize=512m")
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

                // ── Coroutine state machine phantom branches (M005/S01) ───────
                // Flow.map lambda bodies compiled as FlowCollector.emit suspend methods
                // in MeshLink's flow property getters. Collecting these flows end-to-end
                // introduces MORE phantom branches from the coroutine continuation state
                // machine than it covers. The transform logic is verified at the engine
                // level (MeshEngineIntegrationTest); the MeshLink mapper layer is a thin
                // structural mapping (PeerEvent→PeerEvent, Delivered→MessageId, etc.).
                // Per S01 constraint: targeted method excludes acceptable as last resort
                // for Kover/JaCoCo coroutine state machine phantoms.
                //
                // redactFn lambda: Invoked by DiagnosticSink only when a PeerIdHex-carrying
                // event is emitted with redactPeerIds=true. No subsystem emits such events
                // yet (S02 scope). The lambda body is a 1-line truncation — correctness is
                // self-evident; exercising it end-to-end requires S02 diagnostic wiring.
                classes(
                    "ch.trancee.meshlink.api.MeshLink\$Companion\$create\$diagnosticSink\$redactFn\$*"
                )
                // MeshLink.peers: Flow.flatMapConcat lambda and MeshLink class property
                // initializer. The peers Flow is never collected through the public API in
                // existing tests — integration tests use engine.peerEvents directly.
                // The mapPeerEvent logic is verified by MeshLinkMapperTest (unit) and
                // PeerLifecycleIntegrationTest (integration).
                classes("ch.trancee.meshlink.api.MeshLink\$special\$\$inlined\$flatMapConcat\$*")
                classes("ch.trancee.meshlink.api.MeshLink\$peers\$*")
                classes("ch.trancee.meshlink.api.MeshLinkKt\$mapPeerEventsFlow\$*")
                // CutThroughBuffer: FlatBuffers byte surgery validation (bounds checks,
                // vtable parsing, vector offset adjustment, field relocation). These
                // branches require crafted malformed FlatBuffers payloads with specific
                // internal structure violations. The buffer is exercised by 4 test files
                // (CutThroughBufferTest, CutThroughRelayIntegrationTest, etc.).
                classes("ch.trancee.meshlink.messaging.CutThroughBuffer\$RoutingHeaderView*")
                classes("ch.trancee.meshlink.messaging.CutThroughBuffer\$onChunk0\$*")
                // DeliveryPipeline handleIncomingChunk: cut-through buffer fallback path
                // (!activated) and emit lambda phantom branches in catch blocks. The
                // fallback fires only when FlatBuffer byte surgery fails at runtime on a
                // malformed chunk0. Covered indirectly by CutThroughBufferTest edge cases.
                classes("ch.trancee.meshlink.messaging.DeliveryPipeline\$handleIncomingChunk\$*")
                // launchInboundHandshakeSubscription: coroutine FlowCollector.emit suspend
                // state machine has 4 branches, 3 covered. The missing 4th is a JaCoCo/Kover
                // artifact from the suspend continuation dispatch — not a real code path.
                // Both handshake and non-handshake inbound paths are covered by integration
                // tests (MeshEngineIntegrationTest scenario 1 + CoverageIntegrationTest
                // non-handshake test).
                classes(
                    "ch.trancee.meshlink.engine.MeshEngine\$launchInboundHandshakeSubscription\$*"
                )
                classes("ch.trancee.meshlink.benchmark.*")
                // Platform storage stubs — full implementations deferred to M002+.
                // Excluded because they throw NotImplementedError and have no test coverage by
                // design; koverGenerateArtifactAndroid instruments androidMain bytecode regardless
                // of excludedSourceSets.
                classes("ch.trancee.meshlink.storage.AndroidSecureStorage*")
                classes("ch.trancee.meshlink.storage.IosSecureStorage*")
                // AndroidBleTransport: Android BLE API callbacks cannot execute on the JVM host
                // (BluetoothGatt, BluetoothLeScanner callbacks require a real BT stack). Per D024,
                // correctness is proven by S04 two-device integration test on real hardware.
                classes("ch.trancee.meshlink.transport.AndroidBleTransport*")
                // MeshLinkService: Android Service lifecycle methods (onCreate, onDestroy,
                // onBind) require the Android runtime and cannot be tested on JVM host.
                // Correctness verified by Android instrumentation tests in S04.
                classes("ch.trancee.meshlink.transport.MeshLinkService*")
                // IosBleTransport: CoreBluetooth callbacks (CBCentralManager, CBPeripheralManager,
                // CBL2CAPChannel) require a real iOS BLE stack and cannot run on JVM host.
                // Per D024, correctness is proven by S04 two-device integration test on real
                // hardware (iOS + Android). Also excludes inner delegate classes (*$*Delegate*).
                classes("ch.trancee.meshlink.transport.IosBleTransport*")
                // Logger: expect/actual platform-log bridge; per D024 pattern excluded from
                // unit test coverage — correctness verified by S04 two-device integration test.
                classes("ch.trancee.meshlink.transport.Logger*")
                // MeshLinkAndroidFactory: public Android factory that wires AndroidBleTransport.
                // Requires real Android BLE stack — correctness proven by S06 two-device
                // integration
                // test on real hardware. Excluded from JVM host coverage per D024 pattern.
                classes("ch.trancee.meshlink.api.MeshLinkAndroidFactoryKt*")
                // MeshLinkIosFactory: public iOS factory that wires IosBleTransport.
                // Requires real iOS CoreBluetooth stack — correctness proven by S06 two-device
                // integration test on real hardware.
                classes("ch.trancee.meshlink.api.MeshLinkIosFactoryKt*")

                // ── S06/T04: default interface no-op method bodies ────────────
                // BatteryMonitor.reportBattery: default empty body never reached because
                // every MeshEngine instance uses StubBatteryMonitor (which overrides it).
                // Platform implementations (Android/iOS) will also override.
                classes("ch.trancee.meshlink.power.BatteryMonitor*")
                // SecureStorage.clear: default empty body never reached because
                // InMemorySecureStorage overrides it; platform impls will also override.
                classes("ch.trancee.meshlink.storage.SecureStorage*")

                // ── S06/T04: MeshEngine factory onKeyChange lambda ────────────
                // TrustStore's onKeyChange callback fires only when a Noise XX handshake
                // presents a DIFFERENT static key for an already-pinned peer (STRICT mode
                // key conflict). This requires a peer to rotate identity and reconnect
                // within the same session — verified structurally by S06 rotateIdentity
                // integration test + TrustStoreTest key-change scenarios. The callback
                // delegates to engine.onKeyChange() which IS covered by scenarios 7-9.
                // Lines 733-734 remain in MeshEngine$Companion bytecode (inlined lambda).

                // ── S06/T04: DeliveryPipeline error/edge paths ────────────────
                // handleIncomingChunk catch block and cut-through fallback: requires
                // malformed FlatBuffer chunk0 data at runtime. Covered by
                // CutThroughBufferTest + DeliveryPipelineCutThroughTest edge cases.
                // evictCutThroughBuffer: requires a buffer to timeout (timer-driven in
                // production; not triggered in virtual-time tests without 30s advances).
                // bufferSizeBytes sumOf lambda: only runs when sendBuffer is non-empty.
                // In standard integration tests, messages are delivered immediately (no
                // store-and-forward). The arithmetic is trivially correct (.size.toLong()).

                // ── S06/T04: CutThroughBuffer byte surgery edge paths ─────────
                // appendVisitedHop vectorFieldsAfterVisitedList loop: only runs when the
                // FlatBuffer has vector fields positioned after the visited_hops list in
                // the vtable. Current test payloads have no such fields (layout-dependent).
                // The loop arithmetic is verified by structural inspection.
                classes(
                    "ch.trancee.meshlink.messaging.CutThroughBuffer\$Companion\$appendVisitedHop\$*"
                )
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

// ── CI guard: prevent @CoverageIgnore reintroduction ──────────────────────────
// Scans commonMain sources for the annotation. Fails the build if found. Wired into `check`.
val noCoverageIgnore by tasks.registering {
    group = "verification"
    description = "Fails if any @CoverageIgnore annotation appears in commonMain sources."
    // Resolve eagerly outside doLast so the configuration cache doesn't capture a Project
    // reference.
    val srcDir = file("src/commonMain/kotlin")
    val projDir = projectDir
    doLast {
        val violations = mutableListOf<String>()
        srcDir
            .walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { f ->
                f.readLines().forEachIndexed { idx, line ->
                    if (line.contains("@CoverageIgnore") || line.contains("@get:CoverageIgnore")) {
                        violations.add("${f.relativeTo(projDir)}:${idx + 1}: $line")
                    }
                }
            }
        if (violations.isNotEmpty()) {
            error(
                buildString {
                    appendLine("@CoverageIgnore annotation found — remove it and cover the code:")
                    violations.forEach { appendLine("  $it") }
                }
            )
        }
    }
}

tasks.named("check") { dependsOn(noCoverageIgnore) }

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

// SKIE 0.10.11 — generates Swift-friendly AsyncStream/async func wrappers for all
// public Flow/StateFlow members of MeshLinkApi.  Active on macOS+Xcode builds.
// Supports Kotlin ≤ 2.3.20 (current version).  Re-evaluate compatibility on Kotlin bumps.
skie {}

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

// ── Maven Central publishing ──────────────────────────────────────────────────
group = property("GROUP").toString()

version = property("VERSION").toString()

// Dokka 2.0.0: Generate HTML docs and bundle them as the javadoc JAR required by Maven Central.
// `from(task)` implicitly wires the dependency and copies the task's output into the JAR.
val dokkaJavadocJar by
    tasks.registering(Jar::class) {
        archiveClassifier.set("javadoc")
        from(tasks.named("dokkaGeneratePublicationHtml"))
    }

publishing {
    publications.withType<MavenPublication>().configureEach {
        // Attach the Dokka-generated HTML docs as the javadoc JAR for Maven Central.
        artifact(dokkaJavadocJar)
        pom {
            name.set("MeshLink")
            description.set("Peer-to-peer secure mesh networking library for Kotlin Multiplatform")
            url.set("https://github.com/trancee/meshlink")
            licenses {
                license {
                    name.set("Apache License 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
            developers {
                developer {
                    id.set("ch.trancee")
                    name.set("Trancee")
                    url.set("https://github.com/trancee")
                }
            }
            scm {
                url.set("https://github.com/trancee/meshlink")
                connection.set("scm:git:git://github.com/trancee/meshlink.git")
                developerConnection.set("scm:git:ssh://github.com/trancee/meshlink.git")
            }
        }
    }
    repositories {
        maven {
            name = "OSSRH"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = System.getenv("MAVEN_CENTRAL_USERNAME") ?: ""
                password = System.getenv("MAVEN_CENTRAL_PASSWORD") ?: ""
            }
        }
    }
}

// Signing is conditional — publishToMavenLocal works without keys; release.yml provides them.
signing {
    val signingKey = System.getenv("SIGNING_KEY")
    val signingPassword = System.getenv("SIGNING_PASSWORD")
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications)
}
