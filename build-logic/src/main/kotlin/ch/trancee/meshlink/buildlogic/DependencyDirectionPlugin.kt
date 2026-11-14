package ch.trancee.meshlink.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Fails `check` when a module declares a project dependency outside the ADR 0011 allow-list.
 *
 * Every configuration is read, so test, KMP source-set and Android configurations are covered,
 * except the plugin-owned aggregates in [SYNTHETIC_CONFIGURATIONS]. Test configurations may also use
 * the edges in [TEST_ALLOWED]. A module without an allow-list entry fails closed.
 */
class DependencyDirectionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(LifecycleBasePlugin::class.java)
        val check =
            project.tasks.register("checkDependencyDirection", CheckDependencyDirection::class.java) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Checks project dependencies against the module allow-list."
                modulePath.set(project.path)
                dependencyPaths.set(project.provider { project.projectDependencies(test = false) })
                testDependencyPaths.set(project.provider { project.projectDependencies(test = true) })
            }
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(check) }
    }

    companion object {
        /**
         * Kotlin 2.4 adds every KMP project of the build to this configuration in one project to
         * resolve SwiftPM lock-file metadata; it records no module edge.
         */
        val SYNTHETIC_CONFIGURATIONS: Set<String> =
            setOf("swiftPMDependenciesForLockFilesMetadataClasspathDependencies")

        val ALLOWED: Map<String, Set<String>> =
            mapOf(
                ":meshlink-core" to emptySet(),
                ":meshlink-crypto-sodium" to setOf(":meshlink-core"),
                ":meshlink" to setOf(":meshlink-core", ":meshlink-crypto-sodium"),
                ":meshlink-testing" to setOf(":meshlink"),
            )

        /**
         * Extra edges for test configurations only; they never publish. `:meshlink-testing` tests build
         * fake peers whose mesh digests, NodeIds and advertisements need the real crypto provider.
         */
        val TEST_ALLOWED: Map<String, Set<String>> =
            mapOf(":meshlink-testing" to setOf(":meshlink-crypto-sodium"))

        /** `testImplementation`, `commonTestImplementation`, `androidHostTestApi` and the like. */
        private fun isTestConfiguration(name: String): Boolean = name.startsWith("test") || "Test" in name

        private fun Project.projectDependencies(test: Boolean): Set<String> =
            configurations
                .filterNot { it.name in SYNTHETIC_CONFIGURATIONS }
                .filter { isTestConfiguration(it.name) == test }
                .flatMap { it.dependencies.withType(ProjectDependency::class.java) }
                .map { it.path }
                .filter { it != path }
                .toSet()
    }
}

abstract class CheckDependencyDirection : DefaultTask() {
    @get:Input abstract val modulePath: Property<String>

    @get:Input abstract val dependencyPaths: SetProperty<String>

    @get:Input abstract val testDependencyPaths: SetProperty<String>

    @TaskAction
    fun check() {
        val module = modulePath.get()
        val allowed =
            DependencyDirectionPlugin.ALLOWED[module]
                ?: throw GradleException("Dependency direction unknown: $module has no allow-list entry")
        val testAllowed = allowed + DependencyDirectionPlugin.TEST_ALLOWED[module].orEmpty()
        val violations =
            (dependencyPaths.get().filterNot { it in allowed } + testDependencyPaths.get().filterNot { it in testAllowed })
                .distinct()
                .sorted()
        if (violations.isNotEmpty()) {
            throw GradleException(
                violations.joinToString("\n") { "Dependency direction violated: $module depends on $it" },
            )
        }
    }
}
