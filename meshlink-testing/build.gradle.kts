@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
    signing
    alias(libs.plugins.dokka)
}

kotlin {
    jvm()
    iosArm64("ios")
    sourceSets {
        commonMain.dependencies { api(project(":meshlink")) }
    }
}

group = property("GROUP").toString()
version = property("VERSION").toString()

// Dokka 2.0.0: Generate HTML docs and bundle them as the javadoc JAR required by Maven Central.
val dokkaJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(dokkaJavadocJar)
        pom {
            name.set("MeshLink Testing")
            description.set("Test utilities for MeshLink — Kotlin Multiplatform mesh networking")
            url.set("https://github.com/trancee/meshlink")
            licenses {
                license {
                    name.set("Apache License 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
            developers {
                developer {
                    id.set("ch.trancee")
                    name.set("Trancee")
                    url.set("https://github.com/trancee")
                }
            }
            scm {
                url.set("https://github.com/trancee/meshlink")
                connection.set("scm:git:git://github.com/trancee/meshlink.git")
                developerConnection.set("scm:git:ssh://github.com/trancee/meshlink.git")
            }
        }
    }
    repositories {
        maven {
            name = "OSSRH"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = System.getenv("MAVEN_CENTRAL_USERNAME") ?: ""
                password = System.getenv("MAVEN_CENTRAL_PASSWORD") ?: ""
            }
        }
    }
}

// Signing is conditional — publishToMavenLocal works without keys; release.yml provides them.
signing {
    val signingKey = System.getenv("SIGNING_KEY")
    val signingPassword = System.getenv("SIGNING_PASSWORD")
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications)
}
