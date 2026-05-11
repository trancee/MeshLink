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
include(":meshlink-sample:android:app")

project(":meshlink-sample:android:app").projectDir = file("meshlink-sample/android/app")
project(":meshlink-sample:android:app").name = "meshlink-sample-android-app"
