plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("io.meshlink.sample.MainKt")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":meshlink"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
