plugins {
    kotlin("multiplatform") version "2.4.21"
}

kotlin {
    jvmToolchain(21)
    jvm {
        mainRun { mainClass.set("meshlink.proto.codec.JvmMainKt") }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "CodecBench"
            isStatic = true
            binaryOption("bundleId", "ch.trancee.meshlink.codecbench")
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation("ch.trancee.kompact:kompact:0.10.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.register<Jar>("benchJar") {
    group = "build"
    description = "Fat JAR of the JVM benchmark for d8/app_process on Android."
    archiveFileName.set("codec-bench.jar")
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    from(jvmMain.output.allOutputs)
    dependsOn(jvmMain.compileTaskProvider)
    from({ jvmMain.runtimeDependencyFiles.filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/**")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
