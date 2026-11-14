plugins {
    id("meshlink.kmp-library")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":meshlink"))
    }
}
