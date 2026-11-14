package ch.trancee.meshlink.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Configures the root build: the merged Kover report over every module with 100% line and branch
 * rules, the Dokka aggregate over the published modules, Spotless with ktlint, `checkChangelog` and
 * `checkThinAdapters`.
 */
class RootPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project.pluginManager) {
            apply(ChangelogPlugin::class.java)
            apply("org.jetbrains.kotlinx.kover")
            apply("org.jetbrains.dokka")
            apply("com.diffplug.spotless")
        }
        MODULES.forEach { project.dependencies.add("kover", project.dependencyFactory.createProjectDependency(it)) }
        PUBLISHED_MODULES.forEach { project.dependencies.add("dokka", project.dependencyFactory.createProjectDependency(it)) }
        project.extensions.configure(KoverProjectExtension::class.java) {
            reports {
                verify {
                    rule {
                        minBound(COVERAGE_PERCENT, CoverageUnit.LINE)
                        minBound(COVERAGE_PERCENT, CoverageUnit.BRANCH)
                    }
                }
            }
        }
        val ktlint =
            project.extensions
                .getByType(VersionCatalogsExtension::class.java)
                .named("libs")
                .findVersion("ktlint")
                .get()
                .requiredVersion
        project.extensions.configure(SpotlessExtension::class.java) {
            kotlin {
                target("**/*.kt")
                targetExclude("**/build/**")
                ktlint(ktlint)
            }
            kotlinGradle {
                target("**/*.gradle.kts")
                targetExclude("**/build/**")
                ktlint(ktlint)
            }
        }
        val thinAdapters =
            project.tasks.register("checkThinAdapters", CheckThinAdapters::class.java) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Fails unless config/thin-adapters.txt names exactly the Kotlin/Native main source files."
                list.set(project.layout.projectDirectory.file("config/thin-adapters.txt"))
                ThinAdapters.configure(this, project)
            }
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn("spotlessCheck", thinAdapters) }
    }

    internal companion object {
        const val COVERAGE_PERCENT = 100
        val MODULES = listOf(":meshlink-core", ":meshlink-crypto-sodium", ":meshlink", ":meshlink-testing")
        val PUBLISHED_MODULES = listOf(":meshlink", ":meshlink-testing")
    }
}
