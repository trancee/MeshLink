import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension

plugins {
    id("meshlink.kmp-library")
    id("meshlink.libsodium")
    id("meshlink.spec-vectors")
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
