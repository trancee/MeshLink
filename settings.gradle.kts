pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "meshlink"

include(":meshlink-core", ":meshlink-crypto-sodium", ":meshlink", ":meshlink-testing")

// The lab apps (ADR 0017 decision 7): never published, outside Kover, ABI validation and Dokka.
include(":labapp-shared", ":labapp-android")
project(":labapp-shared").projectDir = file("labapp/shared")
project(":labapp-android").projectDir = file("labapp/android")
