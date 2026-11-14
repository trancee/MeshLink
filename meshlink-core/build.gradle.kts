plugins {
    id("meshlink.kmp-library")
    id("meshlink.spec-vectors")
}

specVectors {
    files.addAll("advertisement", "capability", "records", "link-frames", "routed-frames", "inner-plaintext")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.coroutines.core)
    }
}
