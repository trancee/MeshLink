plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(
        libs.versions.jdk.toolchain
            .get()
            .toInt(),
    )
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.android.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)
    implementation(libs.skie.gradle.plugin)
    implementation(libs.maven.publish.gradle.plugin)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit4)
}

gradlePlugin {
    plugins {
        register("dependencyDirection") {
            id = "meshlink.dependency-direction"
            implementationClass = "ch.trancee.meshlink.buildlogic.DependencyDirectionPlugin"
        }
        register("changelog") {
            id = "meshlink.changelog"
            implementationClass = "ch.trancee.meshlink.buildlogic.ChangelogPlugin"
        }
        register("publishedLibrary") {
            id = "meshlink.published-library"
            implementationClass = "ch.trancee.meshlink.buildlogic.PublishedLibraryPlugin"
        }
        register("buildInfo") {
            id = "meshlink.build-info"
            implementationClass = "ch.trancee.meshlink.buildlogic.BuildInfoPlugin"
        }
        register("root") {
            id = "meshlink.root"
            implementationClass = "ch.trancee.meshlink.buildlogic.RootPlugin"
        }
        register("robolectric") {
            id = "meshlink.robolectric"
            implementationClass = "ch.trancee.meshlink.buildlogic.RobolectricPlugin"
        }
        register("kmpLibrary") {
            id = "meshlink.kmp-library"
            implementationClass = "ch.trancee.meshlink.buildlogic.KmpLibraryPlugin"
        }
    }
}
