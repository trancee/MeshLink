import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension

plugins {
    id("meshlink.internal-library")
    id("meshlink.libsodium")
    id("meshlink.spec-vectors")
}

description =
    "Internal MeshLink libsodium binding with no API guarantee, outside the SemVer promise; depend on ch.trancee.meshlink:meshlink."

specVectors {
    files.add("derivations")
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
