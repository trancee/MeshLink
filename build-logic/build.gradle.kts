plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(libs.versions.jdk.toolchain.get().toInt())
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.android.gradle.plugin)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit4)
}

gradlePlugin {
    plugins {
        register("dependencyDirection") {
            id = "meshlink.dependency-direction"
            implementationClass = "ch.trancee.meshlink.buildlogic.DependencyDirectionPlugin"
        }
        register("kmpLibrary") {
            id = "meshlink.kmp-library"
            implementationClass = "ch.trancee.meshlink.buildlogic.KmpLibraryPlugin"
        }
    }
}
