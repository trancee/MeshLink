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
 * ADR 0006 decision 11: `docs/reference/dependencies.md` names every version catalog entry once in a
 * `Catalog` column, at the catalog's version, and its "CI tools" section names every action the
 * workflows use, at the version their pin comment gives.
 */
internal object DependencyRegister {
    fun configure(
        task: CheckDependencyRegister,
        project: Project,
    ) {
        val root = project.layout.projectDirectory
        task.root.set(root)
        task.catalog.set(root.file("gradle/libs.versions.toml"))
        task.register.set(root.file("docs/reference/dependencies.md"))
        task.workflows.from(project.fileTree(root.dir(".github/workflows")) { include("*.yml") })
    }

    /** Every disagreement between the [catalog], the [register] and the [workflows] (name to text), in a stable order. */
    fun problems(
        catalog: String,
        register: String,
        workflows: Map<String, String>,
    ): List<String> {
        val tables = parseTables(register)
        return catalogProblems(parseCatalog(catalog), tables) + workflowProblems(tables, workflows)
    }

    private fun catalogProblems(
        catalog: Map<String, String>,
        tables: List<Table>,
    ): List<String> {
        val listed =
            tables.filter { CATALOG in it.columns }.flatMap { table ->
                table.rows.flatMap { row ->
                    CODE.findAll(row.getValue(CATALOG)).map { it.groupValues[1] to row.getValue(VERSION) }.toList()
                }
            }
        val names = listed.map { it.first }
        val missing = (catalog.keys - names.toSet()).sorted().map { "missing row: $it" }
        val twice =
            names
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .sorted()
                .map { "listed twice: $it" }
        val unknown =
            names
                .filter { it !in catalog }
                .distinct()
                .sorted()
                .map { "no catalog entry: $it" }
        val mismatched =
            listed.mapNotNull { (name, cell) ->
                catalog[name]?.takeUnless { it.isEmpty() || mentions(cell, it) }?.let {
                    "version mismatch: $name is $it in the catalog, the row says $cell"
                }
            }
        return missing + twice + unknown + mismatched
    }

    private fun workflowProblems(
        tables: List<Table>,
        workflows: Map<String, String>,
    ): List<String> {
        val tools = tables.filter { it.title.startsWith(CI_TOOLS) }.flatMap { it.rows }
        val registered =
            tools
                .mapNotNull { row -> ACTION.find(row.getValue(ACTION_COLUMN)) }
                .associate { repository(it.groupValues[1]) to it.groupValues[2] }
        val lychee = tools.firstOrNull { it[TOOL] == LYCHEE_TOOL }?.getValue(VERSION)
        val problems = mutableListOf<String>()
        val commits = sortedMapOf<String, MutableSet<String>>()
        workflows.toSortedMap().forEach { (name, text) ->
            text.lines().forEachIndexed { index, line ->
                val at = "$name:${index + 1}"
                USES.find(line)?.let { uses ->
                    val pin = PIN.matchEntire(uses.groupValues[1])
                    if (pin == null) {
                        problems += "$at: ${uses.groupValues[2]} is not pinned by commit SHA with a version comment"
                    } else {
                        val repository = repository(pin.groupValues[1])
                        val version = pin.groupValues[3]
                        commits.getOrPut(repository) { sortedSetOf() } += pin.groupValues[2]
                        when (val expected = registered[repository]) {
                            null -> problems += "$at: $repository v$version has no CI tools row"
                            version -> Unit
                            else -> problems += "$at: $repository v$version, but the register says v$expected"
                        }
                    }
                }
                LYCHEE.find(line)?.groupValues?.get(1)?.takeUnless { it == lychee }?.let {
                    problems += "$at: lycheeVersion v$it, but the register says lychee $lychee"
                }
            }
        }
        commits.filterValues { it.size > 1 }.forEach { (repository, shas) ->
            problems += "$repository is pinned to ${shas.size} commits: ${shas.joinToString(", ")}"
        }
        registered.filterKeys { it !in commits }.forEach { (repository, version) ->
            problems += "register row $repository v$version is used by no workflow"
        }
        return problems
    }

    /** The repository of an action path: `actions/cache/restore` is `actions/cache`. */
    private fun repository(action: String) = action.split("/").take(2).joinToString("/")

    /** Whether [cell] names [version] as a whole word: `1.0` is not in `1.0.22`. */
    private fun mentions(
        cell: String,
        version: String,
    ) = Regex("""(?<![\w.])${Regex.escape(version)}(?![\w.])""").containsMatchIn(cell)

    /** Each library alias, `plugin:` plugin alias and `version:` key no alias uses, with its version (empty when managed). */
    private fun parseCatalog(text: String): Map<String, String> {
        val versions = mutableMapOf<String, String>()
        val entries = mutableMapOf<String, String>()
        val used = mutableSetOf<String>()
        var section = ""
        text.lines().map(String::trim).forEach { line ->
            val header = SECTION.matchEntire(line)
            val entry = ENTRY.matchEntire(line)
            when {
                header != null -> {
                    section = header.groupValues[1]
                }

                entry == null -> {
                    Unit
                }

                section == "versions" -> {
                    versions[entry.groupValues[1]] = entry.groupValues[2].trim('"')
                }

                else -> {
                    val ref = REF.find(entry.groupValues[2])?.groupValues?.get(1)
                    ref?.let(used::add)
                    val version = ref?.let(versions::getValue) ?: LITERAL.find(entry.groupValues[2])?.groupValues?.get(1)
                    val prefix = if (section == "plugins") "plugin:" else ""
                    entries[prefix + entry.groupValues[1]] = version.orEmpty()
                }
            }
        }
        (versions - used).forEach { (key, version) -> entries["version:$key"] = version }
        return entries
    }

    /** Every Markdown table, with the `##` heading it sits under. */
    private fun parseTables(text: String): List<Table> {
        val tables = mutableListOf<Table>()
        var title = ""
        var block = mutableListOf<List<String>>()
        (text.lines() + "").forEach { line ->
            if (line.startsWith("|")) {
                block +=
                    line
                        .trim()
                        .removePrefix("|")
                        .removeSuffix("|")
                        .split("|")
                        .map(String::trim)
            } else {
                if (block.size > 2) tables += Table(title, block[0], block.drop(2).map { block[0].zip(it).toMap() })
                block = mutableListOf()
                if (line.startsWith("## ")) title = line.removePrefix("## ")
            }
        }
        return tables
    }

    private class Table(
        val title: String,
        val columns: List<String>,
        val rows: List<Map<String, String>>,
    )

    private const val CATALOG = "Catalog"
    private const val VERSION = "Version"
    private const val TOOL = "Tool"
    private const val ACTION_COLUMN = "Action"
    private const val CI_TOOLS = "CI tools"
    private const val LYCHEE_TOOL = "lychee"
    private val CODE = Regex("`([^`]+)`")
    private val ACTION = Regex("""^`([\w.-]+/[\w./-]+)` v([^\s,]+)""")
    private val USES = Regex("""^\s*(?:-\s+)?uses:\s*((?!\./)(\S+).*?)\s*$""")
    private val PIN = Regex("""^([\w.-]+/[\w./-]+)@([0-9a-f]{40})\s+#\s+v(\S+)$""")
    private val LYCHEE = Regex("""lycheeVersion:\s*v?([\w.]+)""")
    private val SECTION = Regex("""^\[(\w+)]$""")
    private val ENTRY = Regex("""^([\w-]+)\s*=\s*(.+)$""")
    private val REF = Regex("""version\.ref\s*=\s*"([^"]+)"""")
    private val LITERAL = Regex("""version\s*=\s*"([^"]+)"""")
}

/** Fails when the dependency register, the version catalog and the workflows disagree (ADR 0006 decision 11). */
@DisableCachingByDefault(because = "Reads a few text files; nothing to cache.")
abstract class CheckDependencyRegister : DefaultTask() {
    @get:Internal abstract val root: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalog: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val register: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val workflows: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val problems =
            DependencyRegister.problems(
                catalog.get().asFile.readText(),
                register.get().asFile.readText(),
                workflows.files.associate { it.name to it.readText() },
            )
        if (problems.isNotEmpty()) {
            val base = root.get().asFile
            throw GradleException(
                "${register.get().asFile.relativeTo(base).invariantSeparatorsPath} disagrees with " +
                    "${catalog.get().asFile.relativeTo(base).invariantSeparatorsPath} or .github/workflows " +
                    "(ADR 0006 decision 11):\n${problems.joinToString("\n")}",
            )
        }
    }
}
