plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        mainRun {
            mainClass.set("ch.trancee.meshlink.tui.MainKt")
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":meshlink"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// Fat JAR for running outside Gradle (recommended for TUI apps — Gradle's I/O
// wrapping prevents JLine from accessing the terminal in raw mode).
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles a fat JAR with all runtime dependencies for direct execution."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "ch.trancee.meshlink.tui.MainKt") }

    val jvmMain = kotlin.jvm().compilations["main"]
    from(jvmMain.output.allOutputs)
    dependsOn(jvmMain.compileTaskProvider)
    from({
        jvmMain.runtimeDependencyFiles.filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    })
}

// Connect stdin for the jvmRun task.
tasks.withType<JavaExec>().configureEach {
    if (name == "jvmRun") {
        standardInput = System.`in`
    }
}
