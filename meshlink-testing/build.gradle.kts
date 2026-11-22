import java.time.Duration

plugins {
    id("meshlink.published-library")
    id("meshlink.sodium-host-jni")
    id("meshlink.spec-vectors")
    alias(libs.plugins.cucumber.kmp)
}

description = "An in-memory fake MeshLink mesh for Kotlin and Android tests."

// The routed-frame vectors a relay test forwards.
specVectors {
    files.from(rootProject.file("docs/spec/vectors/routed-frames.json"))
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":meshlink"))
        api(libs.kotlinx.coroutines.test)
    }
    // Fake peers derive mesh digests, NodeIds and advertisements with the real provider.
    sourceSets.commonTest.dependencies {
        implementation(project(":meshlink-crypto-sodium"))
        implementation(libs.cucumber.kmp.core)
    }
}

cucumberKmp {
    stepRegistry.set("ch.trancee.meshlink.internal.acceptanceSteps")
}

// The scheduled density suite (ADR 0017 decision 11, ticket 42): not part of check. It runs the
// DensityCellTest of the Android host tests alone, cell by cell; `-Pdensity.cells=n200-high,n50-low`
// and `-Pdensity.seeds=1,2` narrow it; each cell writes build/density/<cell>.{json,md} and each seed
// <cell>-seed<N>.run. `-Pdensity.pool=DIR` runs no mesh and pools the .run files under DIR (relative to the repository root);
// `-Pdensity.measureOnly` writes the reports without checking the bands (the per-seed CI jobs).
tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    filter { excludeTestsMatching("*DensityCellTest") }
}
tasks.register<Test>("densityTest") {
    val hostTests = tasks.named<Test>("testAndroidHostTest")
    group = "verification"
    description = "Runs the scheduled density cells and checks the routing simulation's bands."
    testClassesDirs = files(hostTests.map { it.testClassesDirs })
    classpath = files(hostTests.map { it.classpath })
    jvmArgumentProviders.addAll(hostTests.get().jvmArgumentProviders)
    useJUnit()
    filter { includeTestsMatching("*DensityCellTest") }
    // One 200-node seed holds every node's state; a seed takes up to about 15 min.
    maxHeapSize = "8g"
    timeout.set(Duration.ofMinutes(90))
    val out = layout.buildDirectory.dir("density")
    outputs.dir(out)
    outputs.upToDateWhen { false }
    systemProperty("density.out", out.get().asFile.path)
    providers.gradleProperty("density.cells").orNull?.let { systemProperty("density.cells", it) }
    providers.gradleProperty("density.seeds").orNull?.let { systemProperty("density.seeds", it) }
    providers.gradleProperty("density.pool").orNull?.let {
        systemProperty(
            "density.pool",
            layout.settingsDirectory
                .dir(it)
                .asFile.path,
        )
    }
    providers.gradleProperty("density.measureOnly").orNull?.let { systemProperty("density.measureOnly", it) }
    testLogging { showStandardStreams = true }
}
