package ch.trancee.meshlink.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * The native gates of ADR 0004 "Binding" and ADR 0005's AES invariant, as pure functions over tool
 * output so tests can seed violations.
 */
internal object NativeGates {
    /** ADR 0004: native code is at most 1 MB (decimal) per Android ABI and per iOS slice. */
    const val SIZE_LIMIT_BYTES = 1_000_000L

    private val libsodiumApi = Regex("^_?(sodium_|crypto_|randombytes_)")
    private val hardwareSelectedApi = Regex("(^|_)(aes|aegis|ipcrypt)", RegexOption.IGNORE_CASE)

    /**
     * The symbol violations in POSIX `nm -P` output: an exported libsodium symbol, or any exported or
     * imported `aes`, `aegis` or `ipcrypt` symbol. Each line is `name type [value [size]]`; type `U`
     * is an import, and every other type is a definition.
     */
    fun symbolViolations(nmPosix: String): List<String> =
        nmPosix.lines().filter(String::isNotBlank).mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            val name = fields[0].substringBefore('@')
            val imported = fields.getOrNull(1) == "U"
            when {
                !imported && libsodiumApi.containsMatchIn(name) -> "exports libsodium symbol $name"
                hardwareSelectedApi.containsMatchIn(name) -> "${if (imported) "imports" else "exports"} $name"
                else -> null
            }
        }

    /** The total of the `dec` column in Darwin `size` output, one row per archive member or binary. */
    fun darwinSegmentBytes(sizeOutput: String): Long {
        val rows = sizeOutput.lines().filter(String::isNotBlank)
        val dec =
            rows
                .first()
                .trim()
                .split(Regex("\\s+"))
                .indexOf("dec")
        if (dec < 0) throw GradleException("no 'dec' column in size output: ${rows.first()}")
        return rows.drop(1).sumOf {
            it
                .trim()
                .split(Regex("\\s+"))[dec]
                .toLong()
        }
    }
}

/** Fails if any of [binaries] has a symbol violation (see [NativeGates.symbolViolations]). */
@CacheableTask
abstract class CheckNativeSymbols
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val binaries: ConfigurableFileCollection

        /** The `nm` command up to the file name; the task appends `-P -g <file>`. */
        @get:Input abstract val nm: ListProperty<String>

        @get:OutputFile abstract val report: RegularFileProperty

        @TaskAction
        fun check() {
            val lines =
                binaries.files.sorted().flatMap { binary ->
                    NativeGates.symbolViolations(run(nm.get() + listOf("-P", "-g", binary.absolutePath))).map { "${binary.path}: $it" }
                }
            report
                .get()
                .asFile
                .apply { parentFile.mkdirs() }
                .writeText(lines.joinToString("\n", postfix = "\n"))
            if (lines.isNotEmpty()) throw GradleException("native symbol gate failed:\n${lines.joinToString("\n")}")
            binaries.files.sorted().forEach { logger.lifecycle("native symbols ok: ${it.path}") }
        }

        private fun run(command: List<String>): String {
            val out = ByteArrayOutputStream()
            exec.exec {
                commandLine(command)
                standardOutput = out
            }
            return out.toString(Charsets.UTF_8)
        }
    }

/** How [CheckNativeSize] measures one binary. */
enum class NativeSizeMeasure {
    /** The file's size: a stripped shared library. */
    FILE,

    /** The sum of every member's segments in Darwin `size`: an upper bound of what a static archive adds to a link. */
    DARWIN_SEGMENTS,
}

/** Fails if any of [binaries] measures more than [limit] bytes; writes each size to [report]. */
@CacheableTask
abstract class CheckNativeSize
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val binaries: ConfigurableFileCollection

        @get:Input abstract val measure: Property<NativeSizeMeasure>

        @get:Input abstract val limit: Property<Long>

        @get:OutputFile abstract val report: RegularFileProperty

        init {
            limit.convention(NativeGates.SIZE_LIMIT_BYTES)
        }

        @TaskAction
        fun check() {
            val sizes = binaries.files.sorted().associateWith(::bytes)
            val lines = sizes.map { (binary, bytes) -> "${binary.path}: $bytes B (${measure.get()}, limit ${limit.get()} B)" }
            report
                .get()
                .asFile
                .apply { parentFile.mkdirs() }
                .writeText(lines.joinToString("\n", postfix = "\n"))
            lines.forEach(logger::lifecycle)
            val over = sizes.filterValues { it > limit.get() }
            if (over.isNotEmpty()) {
                throw GradleException(
                    "native size gate failed: ${over.keys.joinToString { it.path }} over ${limit.get()} B",
                )
            }
        }

        private fun bytes(binary: File): Long =
            when (measure.get()) {
                NativeSizeMeasure.FILE -> {
                    binary.length()
                }

                NativeSizeMeasure.DARWIN_SEGMENTS -> {
                    val out = ByteArrayOutputStream()
                    exec.exec {
                        commandLine("xcrun", "size", binary.absolutePath)
                        standardOutput = out
                    }
                    NativeGates.darwinSegmentBytes(out.toString(Charsets.UTF_8))
                }
            }
    }
