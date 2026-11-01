plugins {
    kotlin("multiplatform") version "2.4.21"
}

repositories { mavenCentral() }

kotlin {
    jvmToolchain(21)
    jvm()
}

// Copies the simulator jar and the Kotlin stdlib into build/sim/lib so long runs use plain `java`.
val simLib by tasks.registering(Sync::class) {
    dependsOn("jvmJar")
    from(tasks.named("jvmJar"))
    from(kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles)
    into(layout.buildDirectory.dir("sim/lib"))
}
