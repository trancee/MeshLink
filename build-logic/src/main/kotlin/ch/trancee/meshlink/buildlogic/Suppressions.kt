package ch.trancee.meshlink.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Constitution Q2 (5.0.0): production code suppresses nothing, except `@Suppress("DEPRECATION")` in
 * the legacy platform API files the list names.
 */
internal object Suppressions {
    /** Every main source set: `commonMain`, `androidMain`, `iosMain` and so on. */
    private const val MAIN_SOURCES = "*/src/*Main/**/*.kt"

    fun configure(
        task: CheckSuppressions,
        project: Project,
    ) {
        task.root.set(project.layout.projectDirectory)
        task.sources.from(project.fileTree(project.layout.projectDirectory) { include(MAIN_SOURCES) })
    }
}

/** Fails on any production suppression other than `@Suppress("DEPRECATION")` in a listed file. */
@DisableCachingByDefault(because = "Scans a few source files; nothing to cache.")
abstract class CheckSuppressions : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val list: RegularFileProperty

    @get:Internal abstract val root: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val base = root.get().asFile
        val listed =
            list
                .get()
                .asFile
                .readLines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSortedSet()
        val allowedIn = mutableSetOf<String>()
        val problems = mutableListOf<String>()
        sources.files.sorted().forEach { file ->
            val path = file.relativeTo(base).invariantSeparatorsPath
            file.readLines().forEachIndexed { index, line ->
                val code = line.trim()
                when {
                    !SUPPRESSION.containsMatchIn(code) -> Unit
                    code == ALLOWED && path in listed -> allowedIn += path
                    else -> problems += "$path:${index + 1}: $code"
                }
            }
        }
        problems += (listed - allowedIn).map { "stale: $it" }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Production suppressions outside ${list.get().asFile.name} (Constitution Q2):\n${problems.joinToString("\n")}",
            )
        }
    }

    private companion object {
        val SUPPRESSION = Regex("""@(file:)?Suppress(Warnings)?\b""")
        const val ALLOWED = "@Suppress(\"DEPRECATION\")"
    }
}
