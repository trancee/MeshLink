plugins {
    id("meshlink.lab-app")
}

android {
    namespace = "ch.trancee.lab.host"
    defaultConfig {
        applicationId = "ch.trancee.lab.host"
        versionCode = 1
        versionName = "0.1.0-lab"
    }
}

dependencies {
    implementation(project(":labapp-shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
}
