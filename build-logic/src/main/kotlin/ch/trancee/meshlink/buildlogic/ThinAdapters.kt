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
 * Constitution T3 and ADR 0005 "Coverage": no CI coverage tool measures Kotlin/Native, so every
 * Kotlin/Native main source file is a thin adapter named in the list file.
 */
internal object ThinAdapters {
    /** The Kotlin/Native main source sets: `iosMain`, `iosArm64Main`, `appleMain`, `nativeMain` and so on. */
    private val NATIVE_MAIN = listOf("*/src/ios*Main/**/*.kt", "*/src/apple*Main/**/*.kt", "*/src/native*Main/**/*.kt")

    fun configure(
        task: CheckThinAdapters,
        project: Project,
    ) {
        task.root.set(project.layout.projectDirectory)
        task.sources.from(project.fileTree(project.layout.projectDirectory) { include(NATIVE_MAIN) })
    }
}

/** Fails unless the list file names exactly the Kotlin/Native main source files. */
@DisableCachingByDefault(because = "Compares paths; nothing to cache.")
abstract class CheckThinAdapters : DefaultTask() {
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
        val actual = sources.files.map { it.relativeTo(base).invariantSeparatorsPath }.toSortedSet()
        val listed =
            list
                .get()
                .asFile
                .readLines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSortedSet()
        val problems = (actual - listed).map { "unlisted: $it" } + (listed - actual).map { "stale: $it" }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "${list.get().asFile.name} does not match the Kotlin/Native main sources:\n${problems.joinToString("\n")}",
            )
        }
    }
}
