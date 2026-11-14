package ch.trancee.meshlink.buildlogic

import com.vanniktech.maven.publish.JavadocJar
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.AbiValidationExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

/**
 * Configures a module inside the ABI and SemVer promise (ADR 0019): KGP ABI validation with committed
 * dumps checked by `check`, Dokka with undocumented-API reporting that fails on any warning, and Maven
 * publishing with the Dokka HTML as its javadoc JAR.
 */
class PublishedLibraryPlugin : Plugin<Project> {
    @OptIn(ExperimentalAbiValidation::class)
    override fun apply(project: Project) {
        with(project.pluginManager) {
            apply(KmpLibraryPlugin::class.java)
            apply("org.jetbrains.dokka")
        }
        project.extensions.configure(KotlinMultiplatformExtension::class.java) {
            abiValidation {
                project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(checkTaskProvider) }
            }
        }
        project.extensions.configure(DokkaExtension::class.java) {
            dokkaPublications.configureEach { failOnWarning.set(true) }
            dokkaSourceSets.configureEach { reportUndocumented.set(true) }
        }
        project.configureMavenPublishing(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"))
    }
}
