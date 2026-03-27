plugins {
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("plugin.power-assert") version "2.3.20" apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}
