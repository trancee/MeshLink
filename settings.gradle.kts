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
include(":meshlink-reference")
include(":meshlink-reference:android")
include(":meshlink-proof:android")

project(":meshlink-reference").projectDir = file("meshlink-reference")
project(":meshlink-reference:android").projectDir = file("meshlink-reference/android")
project(":meshlink-proof:android").projectDir = file("meshlink-proof/android")
