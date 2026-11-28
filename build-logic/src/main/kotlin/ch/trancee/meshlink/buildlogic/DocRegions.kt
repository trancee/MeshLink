package ch.trancee.meshlink.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * ADR 0006 decision 5: every code block in the tutorial and how-tos is a copy of a marked region of a
 * compiled and executed test. A line `<!-- region: <path>#<name> -->` directly above a fenced block ties
 * the block to the lines between `// region <name>` and `// endregion` in that test file (a Kotlin test
 * source set `src/<name>Test` or a Swift `Tests` directory), with their common indentation removed.
 */
internal object DocRegions {
    private val MARKER = Regex("""^<!-- region: (\S+)#([\w-]+) -->$""")
    private val FENCE = Regex("""^```\w*$""")
    private val TEST_SOURCE = Regex("""(^|/)src/\w+Test/.+\.kt$|(^|/)Tests/.+\.swift$""")
    private val EXCLUDED = listOf("**/build/**", "**/.build/**", "**/node_modules/**", ".scratch/**", "third_party/**")

    fun configure(
        task: DocRegionsTask,
        project: Project,
    ) {
        val root = project.layout.projectDirectory
        task.root.set(root)
        task.documents.from(
            project.fileTree(root) {
                include("**/*.md")
                exclude(EXCLUDED)
            },
        )
        task.sources.from(
            project.fileTree(root) {
                include("**/src/*Test/**/*.kt", "**/Tests/**/*.swift")
                exclude(EXCLUDED)
            },
        )
    }

    /** Each document's text with every tied block replaced by its region, and the problems found. */
    fun resolve(
        root: File,
        path: String,
        text: String,
    ): Pair<String, List<String>> {
        val lines = text.lines()
        val out = mutableListOf<String>()
        val problems = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            out += line
            index++
            val marker = MARKER.matchEntire(line) ?: continue
            val (source, name) = marker.destructured
            val at = "$path:$index"
            if (index >= lines.size || !FENCE.matches(lines[index])) {
                problems += "$at: orphan region marker: no fenced block on the next line"
                continue
            }
            val close = (index + 1 until lines.size).firstOrNull { lines[it] == "```" }
            if (close == null) {
                problems += "$at: fenced block is not closed"
                continue
            }
            val block = lines.subList(index + 1, close)
            out += lines[index]
            val region = region(root, source, name)
            when {
                region.isFailure -> problems += "$at: ${region.exceptionOrNull()!!.message}"
                region.getOrThrow() != block -> problems += "$at: block differs from $source#$name"
            }
            out += region.getOrDefault(block)
            out += lines[close]
            index = close + 1
        }
        return out.joinToString("\n") to problems
    }

    private fun region(
        root: File,
        source: String,
        name: String,
    ): Result<List<String>> =
        runCatching {
            if (!TEST_SOURCE.containsMatchIn(source)) throw GradleException("$source is not a test source")
            val file = root.resolve(source)
            if (!file.isFile) throw GradleException("no file $source")
            val lines = file.readLines()
            val start = lines.indexOfFirst { it.trim() == "// region $name" }
            if (start < 0) throw GradleException("no region $name in $source")
            val end = (start + 1 until lines.size).firstOrNull { lines[it].trim() == "// endregion" }
            if (end == null) throw GradleException("region $name in $source has no // endregion")
            dedent(lines.subList(start + 1, end))
        }

    private fun dedent(lines: List<String>): List<String> {
        val indent = lines.filter(String::isNotBlank).minOfOrNull { it.length - it.trimStart().length } ?: 0
        return lines.map { if (it.isBlank()) "" else it.substring(indent).trimEnd() }
    }
}

/** Rewrites every tied block from its region when [update] is set; otherwise fails naming each block that differs. */
@DisableCachingByDefault(because = "Reads a few text files; nothing to cache.")
abstract class DocRegionsTask : DefaultTask() {
    @get:Input abstract val update: Property<Boolean>

    @get:Internal abstract val root: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val documents: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val base = root.get().asFile
        val problems =
            documents.files.sorted().flatMap { file ->
                val text = file.readText()
                val (resolved, problems) = DocRegions.resolve(base, file.relativeTo(base).invariantSeparatorsPath, text)
                if (update.get() && resolved != text) file.writeText(resolved)
                problems.filter { !update.get() || "differs" !in it }
            }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Doc code blocks disagree with their test regions (ADR 0006 decision 5): run ./gradlew updateDocRegions\n" +
                    problems.joinToString("\n"),
            )
        }
    }
}
