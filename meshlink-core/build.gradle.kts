plugins {
    id("meshlink.internal-library")
}

description =
    "Internal MeshLink module with no API guarantee, outside the SemVer promise; depend on ch.trancee.meshlink:meshlink."

kotlin {
    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.coroutines.core)
    }
}

// The Jazzer driver, only for the fuzz task (ADR 0017 decision 9); never on a test or published classpath.
val jazzer = configurations.create("jazzer") { isCanBeConsumed = false }
dependencies {
    jazzer(libs.jazzer)
    jazzer(libs.jazzer.api)
}

val fuzzCorpus = layout.projectDirectory.dir("src/androidHostTest/fuzz")
val hostTests = tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }
hostTests.configureEach { inputs.dir(fuzzCorpus) }

// Not part of check: `-Pfuzz.seconds=N` bounds each target, and `-Pfuzz.seeds=DIR` minimizes the
// committed corpus with DIR's seeds instead of fuzzing (tools/fuzz/seed_corpus.py writes them).
tasks.register<JavaExec>("fuzz") {
    group = "verification"
    description = "Runs every Jazzer decoder target for a bounded time."
    classpath(jazzer, hostTests.map { it.classpath })
    mainClass.set("ch.trancee.meshlink.fuzz.FuzzLauncherKt")
    args(
        listOfNotNull(
            fuzzCorpus.asFile.path,
            layout.buildDirectory
                .dir("fuzz")
                .get()
                .asFile.path,
            providers.gradleProperty("fuzz.seconds").getOrElse("30"),
            providers.gradleProperty("fuzz.seeds").orNull?.let { rootProject.file(it).path },
        ),
    )
}
