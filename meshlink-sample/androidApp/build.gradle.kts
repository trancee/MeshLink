plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
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

        // FORCE_GATT=true skips L2CAP and uses GATT for all connections (used in S06).
        // Pass via: ./gradlew :meshlink-sample:androidApp:assembleDebug -PFORCE_GATT=true
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
    implementation(project(":meshlink-sample:shared"))
    implementation(libs.androidx.activity.compose)
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
}
