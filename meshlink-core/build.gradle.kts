plugins {
    id("meshlink.internal-library")
}

description =
    "Internal MeshLink module with no API guarantee, outside the SemVer promise; depend on ch.trancee.meshlink:meshlink."

kotlin {
    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.coroutines.core)
    }
}
