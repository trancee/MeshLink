plugins {
    alias(libs.plugins.android.application)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "ch.trancee.lab.host"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "ch.trancee.lab.host"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1-prototype"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.android)
}
