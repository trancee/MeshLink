plugins {
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("plugin.power-assert") version "2.3.20" apply false
    id("com.android.application") version "9.1.0" apply false
    id("com.android.kotlin.multiplatform.library") version "9.1.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

// Force patched versions for transitive build-tool dependencies (AGP bundletool, gRPC, etc.)
buildscript {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "io.netty") useVersion("4.1.132.Final")
            if (requested.group == "org.bitbucket.b_c" && requested.name == "jose4j") useVersion("0.9.6")
            if (requested.group == "org.jdom" && requested.name == "jdom2") useVersion("2.0.6.1")
            if (requested.group == "org.apache.commons" && requested.name == "commons-lang3") useVersion("3.18.0")
            if (requested.group == "org.apache.httpcomponents" && requested.name == "httpclient") useVersion("4.5.14")
        }
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }

    configurations.all {
        resolutionStrategy.eachDependency {
            // Netty: fix CVEs up to 4.1.132.Final (HTTP/2 DoS, request smuggling, CRLF injection, etc.)
            if (requested.group == "io.netty") {
                useVersion("4.1.132.Final")
            }
            // jose4j: fix compressed JWE DoS (CVE-2023-51775)
            if (requested.group == "org.bitbucket.b_c" && requested.name == "jose4j") {
                useVersion("0.9.6")
            }
            // JDOM2: fix XXE injection (CVE-2021-33813)
            if (requested.group == "org.jdom" && requested.name == "jdom2") {
                useVersion("2.0.6.1")
            }
            // Commons Lang3: fix uncontrolled recursion (CVE-2024-10397)
            if (requested.group == "org.apache.commons" && requested.name == "commons-lang3") {
                useVersion("3.18.0")
            }
            // HttpClient: fix XSS (CVE-2020-13956)
            if (requested.group == "org.apache.httpcomponents" && requested.name == "httpclient") {
                useVersion("4.5.14")
            }
        }
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
