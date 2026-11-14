plugins {
    id("meshlink.kmp-library")
    id("meshlink.libsodium")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":meshlink-core"))
    }
}
