package ch.trancee.meshlink.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Registers `checkChangelog` in `check` (ADR 0019 decision 6): an ABI dump that differs from the merge
 * base with `meshlink.changelog.base` (default `origin/main`) needs a `CHANGELOG.md` change too.
 */
class ChangelogPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(LifecycleBasePlugin::class.java)
        val check =
            project.tasks.register("checkChangelog", CheckChangelog::class.java) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Fails if an ABI dump changed without a CHANGELOG.md change."
                baseRef.set(project.providers.gradleProperty("meshlink.changelog.base").orElse("origin/main"))
                repository.set(project.layout.projectDirectory)
            }
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(check) }
    }
}

abstract class CheckChangelog
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:Input abstract val baseRef: Property<String>

        @get:Internal abstract val repository: DirectoryProperty

        @TaskAction
        fun check() {
            val base = baseRef.get()
            if (git("rev-parse", "--verify", "--quiet", "$base^{commit}") == null) {
                throw GradleException("checkChangelog cannot find the base ref $base; fetch it or set -Pmeshlink.changelog.base")
            }
            val mergeBase = git("merge-base", base, "HEAD")?.trim() ?: throw GradleException("git merge-base $base HEAD failed")
            val changed =
                (lines(git("diff", "--name-only", mergeBase)) + lines(git("ls-files", "--others", "--exclude-standard")))
                    .toSortedSet()
            val dumps = changed.filter { ABI_DUMP.matches(it) }
            if (dumps.isNotEmpty() && CHANGELOG !in changed) {
                throw GradleException("ABI dump changed without a $CHANGELOG change: ${dumps.joinToString()}")
            }
        }

        private fun git(vararg args: String): String? {
            val output = ByteArrayOutputStream()
            val result =
                exec.exec {
                    workingDir = repository.get().asFile
                    commandLine(listOf("git") + args)
                    standardOutput = output
                    errorOutput = ByteArrayOutputStream()
                    isIgnoreExitValue = true
                }
            return if (result.exitValue == 0) output.toString(Charsets.UTF_8) else null
        }

        private fun lines(text: String?): List<String> = text.orEmpty().lines().filter(String::isNotBlank)

        private companion object {
            const val CHANGELOG = "CHANGELOG.md"
            val ABI_DUMP = Regex("""(.+/)?api/(.+/)?[^/]+\.api""")
        }
    }
