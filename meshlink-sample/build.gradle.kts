plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ch.trancee.meshlink.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.trancee.meshlink.sample"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // FORCE_GATT=true skips L2CAP and uses GATT for all connections.
        // Pass via: ./gradlew :meshlink-sample:assembleDebug -PFORCE_GATT=true
        buildConfigField(
            "boolean",
            "FORCE_GATT",
            project.findProperty("FORCE_GATT")?.toString() ?: "false",
        )
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":meshlink"))
    implementation(libs.kotlinx.coroutines.core)
}
