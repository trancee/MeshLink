plugins {
    id("meshlink.kmp-library")
    id("meshlink.spec-vectors")
}

specVectors {
    files.add("advertisement")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.coroutines.core)
    }
}
