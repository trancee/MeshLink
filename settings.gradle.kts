pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "meshlink"

include(":meshlink")
include(":benchmarks")
include(":samples:proof-android:app")

project(":samples:proof-android:app").projectDir = file("samples/proof-android/app")
project(":samples:proof-android:app").name = "proof-android-app"
