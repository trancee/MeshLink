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
include(":meshlink-proof:android:app")

project(":meshlink-proof:android:app").projectDir = file("meshlink-proof/android/app")
project(":meshlink-proof:android:app").name = "meshlink-proof-android-app"
