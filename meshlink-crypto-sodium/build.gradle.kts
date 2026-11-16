import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension

plugins {
    id("meshlink.internal-library")
    id("meshlink.libsodium")
    id("meshlink.spec-vectors")
}

description =
    "Internal MeshLink libsodium binding with no API guarantee, outside the SemVer promise; depend on ch.trancee.meshlink:meshlink."

specVectors {
    files.from(
        rootProject.fileTree("docs/spec/vectors") {
            include("*.json")
            exclude("*.schema.json")
        },
    )
    vendored.from(rootProject.fileTree("third_party/vectors") { include("**/*.json", "cacophony/cacophony.txt") })
}

kotlin {
    extensions.configure<KotlinMultiplatformAndroidLibraryExtension> {
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.file("consumer-rules.pro")
        }
    }
    sourceSets.commonMain.dependencies {
        api(project(":meshlink-core"))
    }
}
