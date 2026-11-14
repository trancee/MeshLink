plugins {
    id("meshlink.published-library")
}

description = "An in-memory fake MeshLink mesh for Kotlin and Android tests."

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":meshlink"))
        api(libs.kotlinx.coroutines.test)
    }
}
