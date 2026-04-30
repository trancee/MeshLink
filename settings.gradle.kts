pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MeshLink"
include(":meshlink")
include(":meshlink-testing")
include(":meshlink-tui")
include(":meshlink-sample")
include(":meshlink-sample:shared")
include(":meshlink-sample:androidApp")
