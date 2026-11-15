plugins {
    id("meshlink.internal-library")
    id("meshlink.spec-vectors")
}

description =
    "Internal MeshLink module with no API guarantee, outside the SemVer promise; depend on ch.trancee.meshlink:meshlink."

specVectors {
    files.addAll("advertisement", "capability", "records", "link-frames", "routed-frames", "inner-plaintext")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.coroutines.core)
    }
}
