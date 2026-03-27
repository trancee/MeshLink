plugins {
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("plugin.power-assert") version "2.3.20" apply false
    id("com.android.library") version "8.10.1" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
