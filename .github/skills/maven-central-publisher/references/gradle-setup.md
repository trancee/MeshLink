# Gradle Setup

Configuration for publishing to Maven Central from Gradle projects. There is no official Sonatype Gradle plugin, so you'll use one of several community plugins.

## Table of Contents

- [Choosing a plugin](#choosing-a-plugin)
- [Option 1: vanniktech/gradle-maven-publish-plugin](#option-1-vanniktechgradle-maven-publish-plugin)
- [Option 2: gradle-nexus/publish-plugin with OSSRH Staging API](#option-2-gradle-nexuspublish-plugin-with-ossrh-staging-api)
- [Option 3: JReleaser](#option-3-jreleaser)
- [Option 4: GradleUp/nmcp](#option-4-gradleupnmcp)
- [Common: POM metadata in Gradle](#common-pom-metadata-in-gradle)
- [Common: Signing configuration](#common-signing-configuration)
- [Common: Source and Javadoc JARs](#common-source-and-javadoc-jars)
- [Multi-module Gradle projects](#multi-module-gradle-projects)

## Choosing a plugin

| Plugin | Approach | Best for |
|--------|----------|----------|
| **No plugin (custom)** | Portal API directly | Full control, zero third-party dependencies |
| `vanniktech/gradle-maven-publish-plugin` | Direct Portal API or OSSRH | All-in-one solution, handles POM, signing, sources/javadoc |
| `gradle-nexus/publish-plugin` | OSSRH Staging API | Projects already using this plugin, migrating from OSSRH |
| JReleaser | Portal API via its own release engine | Complex release workflows, multi-platform |
| `GradleUp/nmcp` | Portal API | Lightweight, focused on Central Portal |

> **Version note:** Plugin versions in this guide were current as of early 2025. Always check the plugin's GitHub releases page for the latest version before configuring. Key versions used here: vanniktech `0.30.0`, JReleaser `1.15.0`, nmcp `0.0.9`.

If you want zero third-party dependencies and full control, see `references/custom-gradle-integration.md` for a complete implementation using only Gradle built-ins + custom tasks that call the Portal API.

If starting fresh with a plugin, `vanniktech/gradle-maven-publish-plugin` is the most complete and best-maintained option — it handles POM metadata, signing, source/javadoc JARs, and publishing in a single plugin.

## Option 1: vanniktech/gradle-maven-publish-plugin

This is the most popular community plugin. It handles signing, POM generation, source/javadoc JARs, and publishing.

**settings.gradle.kts:**
```kotlin
pluginManagement {
    plugins {
        id("com.vanniktech.maven.publish") version "0.30.0"
    }
}
```

**build.gradle.kts:**
```kotlin
plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    signAllPublications()

    coordinates("com.example", "my-library", "1.0.0")

    pom {
        name.set("My Library")
        description.set("A library that does useful things")
        url.set("https://github.com/username/my-library")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                name.set("Your Name")
                email.set("you@example.com")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/username/my-library.git")
            developerConnection.set("scm:git:ssh://github.com:username/my-library.git")
            url.set("https://github.com/username/my-library/tree/main")
        }
    }
}
```

**gradle.properties:**
```properties
mavenCentralUsername=<token-username>
mavenCentralPassword=<token-password>
signing.keyId=<last-8-chars-of-gpg-key>
signing.password=<gpg-passphrase>
signing.secretKeyRingFile=<path-to-secring.gpg>
```

For CI, use environment variables instead:
```properties
# These are read from environment variables automatically
# ORG_GRADLE_PROJECT_mavenCentralUsername
# ORG_GRADLE_PROJECT_mavenCentralPassword
# ORG_GRADLE_PROJECT_signingInMemoryKey (ASCII-armored GPG key, NOT base64-encoded)
# ORG_GRADLE_PROJECT_signingInMemoryKeyId
# ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
```

**Publish command:**
```bash
./gradlew publish
```

The plugin automatically creates source and javadoc JARs — no additional configuration needed.

## Option 2: gradle-nexus/publish-plugin with OSSRH Staging API

This approach uses the OSSRH Staging API, which is a compatibility layer that lets older Nexus-based plugins work with the new Central Publisher Portal.

**build.gradle.kts:**
```kotlin
plugins {
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
            password.set(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                // ... same POM metadata as above
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}
```

**Publish command:**
```bash
./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
```

This plugin requires you to separately configure source/javadoc JARs and signing (see Common sections below).

## Option 3: JReleaser

JReleaser is a comprehensive release tool. It's more powerful but also more complex.

**build.gradle.kts:**
```kotlin
plugins {
    `maven-publish`
    signing
    id("org.jreleaser") version "1.15.0"
}

jreleaser {
    deploy {
        maven {
            mavenCentral {
                create("sonatype") {
                    active.set(Active.ALWAYS)
                    url.set("https://central.sonatype.com/api/v1/publisher")
                    stagingRepository("build/staging-deploy")
                }
            }
        }
    }
}
```

JReleaser is best when you need full release orchestration (changelogs, GitHub releases, announcements) in addition to Maven Central publishing.

## Option 4: GradleUp/nmcp

A lightweight plugin focused specifically on the Central Portal.

**build.gradle.kts:**
```kotlin
plugins {
    id("com.gradleup.nmcp") version "0.0.9"
}

nmcp {
    publishAllPublications {
        username.set(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
        password.set(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
    }
}
```

## Common: POM metadata in Gradle

When using `maven-publish` directly (not the vanniktech plugin which has its own DSL), configure POM metadata in the publication:

```kotlin
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("My Library")
                description.set("A library that does useful things")
                url.set("https://github.com/username/my-library")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        name.set("Your Name")
                        email.set("you@example.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/username/my-library.git")
                    developerConnection.set("scm:git:ssh://github.com:username/my-library.git")
                    url.set("https://github.com/username/my-library/tree/main")
                }
            }
        }
    }
}
```

## Common: Signing configuration

```kotlin
signing {
    // Option 1: Key ring file (local development)
    useGpgCmd()

    // Option 2: In-memory key (CI)
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKey, signingPassword)

    sign(publishing.publications["mavenJava"])
}
```

For CI, export the GPG key as an ASCII-armored string:
```bash
gpg --armor --export-secret-keys <KEY_ID>
```

Pass it as `ORG_GRADLE_PROJECT_signingKey` environment variable. The Gradle `signing` plugin accepts the armored key directly — no base64 encoding needed.

## Common: Source and Javadoc JARs

When not using the vanniktech plugin (which handles this automatically):

```kotlin
java {
    withSourcesJar()
    withJavadocJar()
}
```

For Kotlin projects, use Dokka for proper KDoc documentation:

```kotlin
plugins {
    id("org.jetbrains.dokka") version "1.9.20"
}

val dokkaJavadocJar by tasks.registering(Jar::class) {
    dependsOn(tasks.dokkaJavadoc)
    from(tasks.dokkaJavadoc.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(dokkaJavadocJar)
        }
    }
}
```

## Multi-module Gradle projects

For multi-module projects, define shared publishing configuration in a convention plugin or the root `build.gradle.kts` using `subprojects`/`allprojects`:

```kotlin
// buildSrc/src/main/kotlin/publish-conventions.gradle.kts
plugins {
    `maven-publish`
    signing
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    pom {
        // Shared metadata...
    }
}
```

Then apply in each publishable module:

```kotlin
plugins {
    id("publish-conventions")
}
```

Skip modules that shouldn't be published by not applying the convention plugin to them.
