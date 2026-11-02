package meshlink.proto.codec

import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.TimeSource

/** Entry point for the Swift shell: runs sizes, truncation, a fuzz pass and the benchmark; returns CSV text. */
@OptIn(ExperimentalNativeApi::class)
object CodecBenchEntry {
    fun run(fuzzIterations: Int, batches: Int): String = buildString {
        val start = TimeSource.Monotonic.markNow()
        appendLine("# runtime=Kotlin/Native ${KotlinVersion.CURRENT} os=${Platform.osFamily} ${Platform.cpuArchitecture} debug=${Platform.isDebugBinary}")
        appendLine("## sizes")
        sizeReport().forEach(::appendLine)
        appendLine("## truncation")
        truncationReport().forEach(::appendLine)
        appendLine("## fuzz seed=0x4d455348 iterations=$fuzzIterations")
        appendLine(FuzzReport.HEADER)
        val cc = CodecC()
        for (s in fuzzSamples) {
            appendLine(fuzzBC(s, fuzzIterations, 0x4D455348L, cc).toString())
            appendLine(fuzzA(s, fuzzIterations, 0x4D455348L).toString())
        }
        appendLine("## bench")
        appendLine(BenchResult.HEADER)
        runBench(listOf(CodecA, CodecB, CodecC()), benchSampleNames, batches = batches) { appendLine(it) }
        appendLine("# total_ms=${start.elapsedNow().inWholeMilliseconds}")
    }
}
