pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "MeshLink"
include(":meshlink")
include(":meshlink-sample:android")
include(":meshlink-sample:jvm")
include(":meshlink-sample:linux")
