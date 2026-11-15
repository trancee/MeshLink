package ch.trancee.meshlink.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File

/**
 * Makes the protocol test vectors in `docs/spec/vectors/` available to `commonTest`, so one vector
 * runner serves the Android host and the iOS simulator without file access at test time.
 *
 * A module names the vector files it runs in `specVectors { files.add("advertisement") }`.
 * `generateSpecVectors` turns each into Kotlin source, every value as a string (hex or decimal, as
 * in the file): `derivations.json` into `DerivationVector`s, and each layout file, such as
 * `advertisement.json`, into `LayoutVector`s. Nested `decoded` fields are flattened to dotted
 * names: a list `versions` becomes `versions.count` plus `versions.0.version` and so on.
 */
class SpecVectorsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("specVectors", SpecVectorsExtension::class.java)
        val vectorDirectory =
            project.rootProject.layout.projectDirectory
                .dir("docs/spec/vectors")
        val generate =
            project.tasks.register("generateSpecVectors", GenerateSpecVectors::class.java) {
                group = "verification"
                description = "Generates commonTest Kotlin source from the vector files in docs/spec/vectors/."
                vectors.from(extension.files.map { names -> names.map { vectorDirectory.file("$it.json") } })
                outputDirectory.set(project.layout.buildDirectory.dir("generated/meshlink/vectors/commonTest/kotlin"))
            }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            project.extensions.configure(KotlinMultiplatformExtension::class.java) {
                sourceSets.named("commonTest") { kotlin.srcDir(generate.flatMap { it.outputDirectory }) }
            }
        }
        // AGP's lint tasks read the test sources but don't inherit the source directory's task
        // dependency; an app that depends on this module (the lab app) runs them.
        project.tasks
            .matching { it.name.startsWith("lintAnalyze") || it.name.endsWith("LintModel") }
            .configureEach { dependsOn(generate) }
    }
}

/** Which vector files of `docs/spec/vectors/` a module's `commonTest` runs. */
abstract class SpecVectorsExtension {
    /** Vector file names without `.json`, such as `derivations` or `advertisement`. */
    abstract val files: ListProperty<String>
}

/** Writes one Kotlin file per vector file, plus the `LayoutVector` type if any layout file is listed. */
@CacheableTask
abstract class GenerateSpecVectors : DefaultTask() {
    /** The vector files. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val vectors: ConfigurableFileCollection

    /** The Kotlin source root the generated files go in. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = outputDirectory.get().dir("ch/trancee/meshlink/vectors").asFile
        directory.deleteRecursively()
        directory.mkdirs()
        val files = vectors.files.sortedBy { it.name }
        files.forEach { file ->
            if (file.name == "derivations.json") writeDerivations(file, directory) else writeLayout(file, directory)
        }
        if (files.any { it.name != "derivations.json" }) writeLayoutType(directory)
    }

    private fun writeDerivations(
        source: File,
        directory: File,
    ) {
        val lines =
            groups(source).flatMap { group ->
                val derivation = group["derivation"] as String
                tests(group).map { test ->
                    val inputs = mapLiteral((test["inputs"] as Map<*, *>).entries.associate { (k, v) -> k to v })
                    "        DerivationVector(${literal(derivation)}, ${test["tcId"]}, $inputs, " +
                        "${literal(test["output"].toString())}),"
                }
            }
        File(directory, "DerivationVectors.kt").writeText(
            buildString {
                header(source)
                appendLine("/** One derivation test: [inputs] as hex or decimal strings, [output] as hex. */")
                appendLine("internal data class DerivationVector(")
                appendLine("    val derivation: String,")
                appendLine("    val tcId: Int,")
                appendLine("    val inputs: Map<String, String>,")
                appendLine("    val output: String,")
                appendLine(")")
                appendLine()
                appendLine("/** Every test in derivations.json, in file order. */")
                appendLine("internal val derivationVectors: List<DerivationVector> =")
                appendLine("    listOf(")
                lines.forEach(::appendLine)
                appendLine("    )")
            },
        )
    }

    private fun writeLayout(
        source: File,
        directory: File,
    ) {
        val lines =
            groups(source).flatMap { group ->
                val layout = group["layout"] as String
                tests(group).map { test ->
                    val decoded = (test["decoded"] as Map<*, *>?)?.let { mapLiteral(flatten("", it)) } ?: "null"
                    val error = (test["error"] as String?)?.let(::literal) ?: "null"
                    "        LayoutVector(${literal(layout)}, ${test["tcId"]}, ${literal(test["result"] as String)}, " +
                        "${literal(test["encoded"] as String)}, $decoded, $error),"
                }
            }
        val name = source.nameWithoutExtension.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }
        File(directory, "${name}Vectors.kt").writeText(
            buildString {
                header(source)
                appendLine("/** Every test in ${source.name}, in file order. */")
                appendLine("internal val ${name.replaceFirstChar(Char::lowercase)}Vectors: List<LayoutVector> =")
                appendLine("    listOf(")
                lines.forEach(::appendLine)
                appendLine("    )")
            },
        )
    }

    private fun writeLayoutType(directory: File) {
        File(directory, "LayoutVector.kt").writeText(
            buildString {
                appendLine("// Generated by generateSpecVectors. Do not edit.")
                appendLine("package ch.trancee.meshlink.vectors")
                appendLine()
                appendLine("/**")
                appendLine(" * One codec test: [encoded] as hex; [result] is `valid`, `acceptable` or `invalid`. A")
                appendLine(" * decodable test has [decoded] fields as hex or decimal strings; an invalid one has [error].")
                appendLine(" */")
                appendLine("internal data class LayoutVector(")
                appendLine("    val layout: String,")
                appendLine("    val tcId: Int,")
                appendLine("    val result: String,")
                appendLine("    val encoded: String,")
                appendLine("    val decoded: Map<String, String>?,")
                appendLine("    val error: String?,")
                appendLine(")")
            },
        )
    }

    private fun StringBuilder.header(source: File) {
        appendLine("// Generated by generateSpecVectors from docs/spec/vectors/${source.name}.")
        appendLine("// Do not edit; change the vector file and rebuild.")
        appendLine("package ch.trancee.meshlink.vectors")
        appendLine()
    }

    private fun groups(source: File): List<Map<*, *>> =
        ((JsonSlurper().parse(source) as Map<*, *>)["testGroups"] as List<*>).map { it as Map<*, *> }

    private fun tests(group: Map<*, *>): List<Map<*, *>> = (group["tests"] as List<*>).map { it as Map<*, *> }

    // Derivation inputs may be lists, written in Groovy's `[a, b]` form.
    private fun mapLiteral(map: Map<*, *>): String =
        map.entries.joinToString(", ", "mapOf(", ")") { (name, value) ->
            "${literal(name.toString())} to ${literal(value.toString())}"
        }

    private fun flatten(
        prefix: String,
        value: Any?,
    ): Map<String, Any?> =
        when (value) {
            is Map<*, *> -> {
                value.entries.fold(emptyMap()) { all, (k, v) -> all + flatten("$prefix$k.", v) }
            }

            is List<*> -> {
                value.withIndex().fold(mapOf<String, Any?>("${prefix}count" to value.size)) { all, (i, v) ->
                    all + flatten("$prefix$i.", v)
                }
            }

            else -> {
                mapOf(prefix.removeSuffix(".") to value)
            }
        }

    private fun literal(value: String): String {
        require(value.all { it in ' '..'~' && it != '"' && it != '\\' && it != '$' }) {
            "vector strings must be printable ASCII without quotes, backslashes or dollar signs: $value"
        }
        return "\"$value\""
    }
}
