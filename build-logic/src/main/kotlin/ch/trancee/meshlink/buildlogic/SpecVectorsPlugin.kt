package ch.trancee.meshlink.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import java.security.MessageDigest

/**
 * Embeds JSON vector files in `commonTest`, so one vector runner serves the Android host and the iOS
 * simulator without file access at test time.
 *
 * A module lists the files in `specVectors { files.from(…); vendored.from(…) }`. `generateSpecVectors`
 * writes each file's JSON, without whitespace outside strings, as string constants, and a map
 * `vectorFiles` from `<directory>/<file>` (such as `vectors/advertisement.json`) to its text. A
 * vendored file must match its line in the `SHA256SUMS` file next to it.
 */
class SpecVectorsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("specVectors", SpecVectorsExtension::class.java)
        val generate =
            project.tasks.register("generateSpecVectors", GenerateSpecVectors::class.java) {
                group = "verification"
                description = "Embeds the JSON vector files in commonTest Kotlin source."
                files.from(extension.files)
                vendored.from(extension.vendored)
                checksums.from(extension.vendored.elements.map { all -> all.map { File(it.asFile.parentFile, SUMS) } })
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

    internal companion object {
        const val SUMS = "SHA256SUMS"
    }
}

/** Which JSON vector files a module's `commonTest` embeds. */
abstract class SpecVectorsExtension {
    /** Files kept in this repository: the published vectors in `docs/spec/vectors`. */
    abstract val files: ConfigurableFileCollection

    /** Files copied from elsewhere; each must match the `SHA256SUMS` file in its directory. */
    abstract val vendored: ConfigurableFileCollection
}

/** Writes one Kotlin object per vector file and the `vectorFiles` map. */
@CacheableTask
abstract class GenerateSpecVectors : DefaultTask() {
    /** The repository's own vector files. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val files: ConfigurableFileCollection

    /** The vendored vector files. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val vendored: ConfigurableFileCollection

    /** The `SHA256SUMS` file of each vendored file's directory. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val checksums: ConfigurableFileCollection

    /** The Kotlin source root the generated files go in. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        vendored.files.forEach(::verifyChecksum)
        val sources = (files.files + vendored.files).sortedBy(::vectorName)
        val duplicates = sources.groupBy(::vectorName).filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) throw GradleException("Vector file names must be unique: $duplicates")
        val directory = outputDirectory.get().dir("ch/trancee/meshlink/vectors").asFile
        directory.deleteRecursively()
        directory.mkdirs()
        sources.forEachIndexed { index, source -> writeFile(source, "VectorFile$index", directory) }
        File(directory, "VectorFiles.kt").writeText(
            buildString {
                appendLine(HEADER)
                appendLine("package ch.trancee.meshlink.vectors")
                appendLine()
                appendLine("/** Each embedded vector file's JSON, by `<directory>/<file>`. */")
                appendLine("internal val vectorFiles: Map<String, () -> String> =")
                appendLine("    mapOf(")
                sources.forEachIndexed { index, source ->
                    appendLine("        ${literal(vectorName(source))} to VectorFile$index::text,")
                }
                appendLine("    )")
            },
        )
    }

    private fun verifyChecksum(file: File) {
        val sums = File(file.parentFile, SpecVectorsPlugin.SUMS)
        if (!sums.isFile) throw GradleException("Vendored vector file without ${sums.path}: ${file.path}")
        val expected =
            sums
                .readLines()
                .map { it.split(Regex("\\s+"), limit = 2) }
                .firstOrNull { it.size == 2 && it[1].removePrefix("*") == file.name }
                ?.first()
                ?: throw GradleException("${sums.path} has no line for ${file.name}")
        val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw GradleException("${file.path}: SHA-256 $actual differs from $expected in ${sums.path}")
        }
    }

    private fun writeFile(
        source: File,
        name: String,
        directory: File,
    ) {
        val chunks = minify(source.readText()).chunked(CHUNK)
        File(directory, "$name.kt").writeText(
            buildString {
                appendLine(HEADER)
                appendLine("package ch.trancee.meshlink.vectors")
                appendLine()
                appendLine("/** ${vectorName(source)}, without whitespace outside strings. */")
                appendLine("internal object $name {")
                appendLine("    fun text(): String =")
                appendLine("        arrayOf(")
                chunks.forEach { appendLine("            ${literal(it)},") }
                appendLine("        ).joinToString(\"\")")
                appendLine("}")
            },
        )
    }

    private fun vectorName(file: File): String = "${file.parentFile.name}/${file.name}"

    /** Drops whitespace outside JSON strings; the text stays equivalent JSON. */
    private fun minify(json: String): String =
        buildString {
            var inString = false
            var escaped = false
            json.forEach { char ->
                when {
                    escaped -> escaped = false
                    inString && char == '\\' -> escaped = true
                    char == '"' -> inString = !inString
                }
                if (inString || char == '"' || !char.isWhitespace()) append(char)
            }
        }

    private fun literal(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                when {
                    char == '\\' || char == '"' || char == '$' -> append('\\').append(char)
                    char in ' '..'~' -> append(char)
                    else -> append("\\u%04x".format(char.code))
                }
            }
            append('"')
        }

    private companion object {
        const val HEADER = "// Generated by generateSpecVectors. Do not edit; change the vector file and rebuild."

        // Each chunk stays below the JVM's 65,535-byte constant limit even if every character takes 3 bytes.
        const val CHUNK = 16_384
    }
}
