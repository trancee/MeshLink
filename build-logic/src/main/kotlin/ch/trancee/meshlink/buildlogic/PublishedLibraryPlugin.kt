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
 * dumps checked by `check`, Dokka with undocumented-API reporting that fails on any warning and the
 * module's `Module.md` as its module page (linking the prose docs, ADR 0006 decision 8), and Maven
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
            dokkaSourceSets.configureEach {
                reportUndocumented.set(true)
                includes.from(project.file("Module.md"))
                // ADR 0011: internals live in ch.trancee.meshlink.internal, split across modules the
                // reference doesn't cover, so Dokka's links into it name pages it never generates.
                perPackageOption {
                    matchingRegex.set("""ch\.trancee\.meshlink\.internal(\..*)?""")
                    suppress.set(true)
                }
            }
        }
        project.configureMavenPublishing(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"))
    }
}
