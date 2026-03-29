import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.power-assert")
    id("com.android.kotlin.multiplatform.library")
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
    android {
        namespace = "io.meshlink"
        compileSdk = 36
        minSdk = 26

        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }
    }

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

    linuxArm64 {
        compilations.getByName("main") {
            cinterops {
                val bluetooth by creating {
                    defFile("src/nativeInterop/cinterop/bluetooth.def")
                }
            }
        }
    }
    linuxX64 {
        compilations.getByName("main") {
            cinterops {
                val bluetooth by creating {
                    defFile("src/nativeInterop/cinterop/bluetooth.def")
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        getByName("androidDeviceTest").dependencies {
            implementation("androidx.test:runner:1.7.0")
            implementation("androidx.test.ext:junit:1.2.1")
        }
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
                    name.set("The Unlicense")
                    url.set("https://unlicense.org")
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

signing {
    val signingKey = findProperty("signing.key") as String? ?: System.getenv("GPG_SIGNING_KEY")
    val signingPassword = findProperty("signing.password") as String? ?: System.getenv("GPG_SIGNING_PASSWORD")
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications)
}
