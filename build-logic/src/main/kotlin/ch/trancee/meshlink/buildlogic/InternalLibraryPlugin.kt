package ch.trancee.meshlink.buildlogic

import com.vanniktech.maven.publish.JavadocJar
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Configures an internal module that publishes only so `ch.trancee.meshlink:meshlink` resolves
 * (ADR 0011 decision 1, 2026-11-15 amendment): the shared Maven publishing with an empty javadoc JAR,
 * and no ABI validation or Dokka, because the artifact is outside the ABI and SemVer promise of ADR 0019.
 */
class InternalLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(KmpLibraryPlugin::class.java)
        project.configureMavenPublishing(javadocJar = JavadocJar.Empty())
    }
}
