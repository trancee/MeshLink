plugins {
    id("meshlink.published-library")
    id("meshlink.robolectric")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":meshlink-core"))
        implementation(project(":meshlink-crypto-sodium"))
    }
}
