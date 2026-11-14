plugins {
    id("meshlink.kmp-library")
    id("meshlink.spec-vectors")
}

specVectors {
    files.addAll("advertisement", "capability", "records", "link-frames")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.coroutines.core)
    }
}
