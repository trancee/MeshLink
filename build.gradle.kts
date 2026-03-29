plugins {
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("plugin.power-assert") version "2.3.20" apply false
    id("com.android.library") version "8.13.2" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/detekt.yml"))
    source.setFrom(
        files(
            "meshlink/src/commonMain/kotlin",
            "meshlink/src/androidMain/kotlin",
            "meshlink/src/jvmMain/kotlin",
            "meshlink/src/iosMain/kotlin",
            "meshlink/src/appleMain/kotlin",
            "meshlink/src/linuxMain/kotlin",
            "meshlink/src/nativeMain/kotlin",
        )
    )
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
}
