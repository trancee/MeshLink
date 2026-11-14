plugins {
    id("meshlink.published-library")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":meshlink"))
    }
}
