plugins {
    id("meshlink.published-library")
    id("meshlink.sodium-host-jni")
    id("meshlink.spec-vectors")
}

description = "An in-memory fake MeshLink mesh for Kotlin and Android tests."

// The routed-frame vectors a relay test forwards.
specVectors {
    files.from(rootProject.file("docs/spec/vectors/routed-frames.json"))
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":meshlink"))
        api(libs.kotlinx.coroutines.test)
    }
    // Fake peers derive mesh digests, NodeIds and advertisements with the real provider.
    sourceSets.commonTest.dependencies {
        implementation(project(":meshlink-crypto-sodium"))
    }
}
