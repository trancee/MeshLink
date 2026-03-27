import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.power-assert")
    id("com.android.library")
    `maven-publish`
    signing
}

group = "io.meshlink"
version = findProperty("meshlink.version") as String? ?: "0.1.0"

powerAssert {
    functions = listOf(
        "kotlin.assert",
        "kotlin.test.assertTrue",
        "kotlin.test.assertFalse",
        "kotlin.test.assertEquals",
        "kotlin.test.assertNotEquals",
        "kotlin.test.assertNull",
        "kotlin.test.assertNotNull",
        "kotlin.test.assertIs",
        "kotlin.test.assertContentEquals",
    )
}

kotlin {
    jvm()
    androidTarget()

    val xcf = XCFramework("MeshLink")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "MeshLink"
            isStatic = true
            xcf.add(this)
        }
    }
    listOf(macosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "MeshLink"
            isStatic = true
            xcf.add(this)
        }
    }

    linuxArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

android {
    namespace = "io.meshlink"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

publishing {
    publications.withType<MavenPublication> {
        pom {
            name.set("MeshLink Core")
            description.set("Secure BLE mesh messaging library for Android, iOS, macOS, and Linux")
            url.set("https://github.com/trancee/MeshLink")

            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }

            developers {
                developer {
                    id.set("trancee")
                    name.set("trancee")
                    url.set("https://github.com/trancee")
                }
            }

            scm {
                connection.set("scm:git:git://github.com/trancee/MeshLink.git")
                developerConnection.set("scm:git:ssh://github.com/trancee/MeshLink.git")
                url.set("https://github.com/trancee/MeshLink")
            }
        }
    }

    repositories {
        maven {
            name = "sonatype"
            val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
            credentials {
                username = findProperty("ossrh.username") as String? ?: System.getenv("OSSRH_USERNAME")
                password = findProperty("ossrh.password") as String? ?: System.getenv("OSSRH_PASSWORD")
            }
        }
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

signing {
    val signingKey = findProperty("signing.key") as String? ?: System.getenv("GPG_SIGNING_KEY")
    val signingPassword = findProperty("signing.password") as String? ?: System.getenv("GPG_SIGNING_PASSWORD")
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications)
}
