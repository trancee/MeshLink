package ch.trancee.meshlink.fuzz

import java.io.File
import kotlin.system.exitProcess

/**
 * The `:meshlink-core:fuzz` entry point. Jazzer fuzzes one target per JVM, so this starts the
 * standalone driver once per [fuzzTargets] entry, on this JVM's classpath.
 *
 * Arguments: the committed corpus directory, a scratch directory, the seconds per target, and
 * optionally a seed directory. Without seeds each target fuzzes for its seconds, reading the
 * committed corpus and writing new inputs, any crash input and its reproducer under the scratch
 * directory. With
 * seeds each target's committed corpus is replaced by the libFuzzer merge of the seeds and the
 * old corpus: the smallest input set with the same coverage.
 */
fun main(args: Array<String>) {
    val corpus = File(args[0])
    val scratch = File(args[1])
    val seconds = args[2]
    val seeds = args.getOrNull(3)?.let(::File)
    val crashes = File(scratch, "crashes").apply { mkdirs() }
    val failed =
        fuzzTargets.filter { target ->
            val committed = File(corpus, target.name).apply { mkdirs() }
            val exit =
                if (seeds == null) {
                    val found = File(scratch, "corpus/${target.name}").apply { mkdirs() }
                    jazzer(
                        crashes,
                        target,
                        "-max_total_time=$seconds",
                        "-artifact_prefix=$crashes/${target.name}-",
                        found.path,
                        committed.path,
                    )
                } else {
                    val merged = File(scratch, "merged/${target.name}").apply { deleteRecursively() }.apply { mkdirs() }
                    val seeded = File(seeds, target.name)
                    jazzer(crashes, target, "-merge=1", merged.path, seeded.path, committed.path).also {
                        if (it == 0) replace(committed, merged)
                    }
                }
            exit != 0
        }
    if (failed.isNotEmpty()) {
        System.err.println("fuzz targets failed: ${failed.joinToString { it.name }}")
        exitProcess(1)
    }
}

/** Runs Jazzer in [directory], where it also writes a `Crash_<sha1>.java` reproducer. */
private fun jazzer(
    directory: File,
    target: FuzzTarget,
    vararg options: String,
): Int {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val command =
        listOf(java, "-Xmx$HEAP_MB" + "m") + JVM_OPTIONS +
            listOf("-cp", System.getProperty("java.class.path"), "com.code_intelligence.jazzer.Jazzer") +
            listOf(
                "--target_class=${target.target.java.name}",
                "--instrumentation_includes=ch.trancee.meshlink.**",
                "-timeout=$TIMEOUT_SECONDS",
                "-rss_limit_mb=$RSS_LIMIT_MB",
            ) + options
    return ProcessBuilder(command)
        .directory(directory)
        .inheritIO()
        .start()
        .waitFor()
}

private fun replace(
    committed: File,
    merged: File,
) {
    committed.deleteRecursively()
    merged.copyRecursively(committed)
}

/** A single input that runs longer than this is a hang. */
private const val TIMEOUT_SECONDS = 5

/** The heap and the process limit; a decoder allocating past them is an unbounded allocation. */
private const val HEAP_MB = 1_024
private const val RSS_LIMIT_MB = 2_560

// Jazzer's recommended JVM options (docs/advanced.md, "Recommended JVM Options").
private val JVM_OPTIONS =
    listOf("-XX:-OmitStackTraceInFastThrow", "-XX:+UseParallelGC", "-XX:+EnableDynamicAgentLoading")
