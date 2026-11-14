package ch.trancee.meshlink.buildlogic

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Configures Maven publishing for a MeshLink module (ADR 0011 decision 1, ADR 0019 decision 1): groupId
 * `ch.trancee.meshlink`, the module name as artifact ID, one version from the root `meshlink.version`
 * Gradle property, and a complete POM under The Unlicense. The module's `description` becomes the POM
 * description. Signing and Maven Central upload are configured by the release process, not here.
 */
internal fun Project.configureMavenPublishing(javadocJar: JavadocJar) {
    pluginManager.apply("com.vanniktech.maven.publish.base")
    val version = meshLinkVersion()
    val description =
        provider { project.description ?: throw GradleException("$path needs a description for its POM") }
    extensions.configure(MavenPublishBaseExtension::class.java) {
        configure(KotlinMultiplatform(javadocJar = javadocJar, sourcesJar = SourcesJar.Sources()))
        coordinates(MESHLINK_GROUP, project.name, version)
        pom {
            name.set(project.name)
            this.description.set(description)
            url.set(REPOSITORY)
            inceptionYear.set("2026")
            licenses {
                license {
                    name.set("The Unlicense")
                    url.set("https://unlicense.org")
                    distribution.set("repo")
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
                url.set(REPOSITORY)
                connection.set("scm:git:$REPOSITORY.git")
                developerConnection.set("scm:git:$REPOSITORY.git")
            }
        }
    }
}

private const val REPOSITORY = "https://github.com/trancee/MeshLink"
