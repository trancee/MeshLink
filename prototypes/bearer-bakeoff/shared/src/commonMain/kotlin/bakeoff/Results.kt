package bakeoff

import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/** Platform output: one JSON object per line to the platform log (tag BAKEOFF) and to the results file. */
interface ResultSink {
    fun emit(jsonLine: String)
    fun log(message: String)
}

class PhaseClock {
    private val start = TimeSource.Monotonic.markNow()
    val marks: LinkedHashMap<String, Double> = LinkedHashMap()

    fun elapsedMs(): Double = start.elapsedNow().inWholeMicroseconds / 1000.0

    fun mark(name: String) {
        marks[name] = round3(elapsedMs())
    }
}

fun round3(value: Double): Double = (value * 1000).roundToLong() / 1000.0

class Recorder(private val config: BakeoffConfig, val local: DeviceInfo, private val sink: ResultSink) {
    @OptIn(ExperimentalTime::class)
    fun record(
        test: String,
        outcome: String,
        central: DeviceInfo?,
        peripheral: DeviceInfo?,
        bearer: String? = null,
        phy: String? = null,
        mtu: Int? = null,
        attempt: Int? = null,
        timings: Map<String, Any?> = emptyMap(),
        bytes: Long? = null,
        error: String? = null,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        val fields = linkedMapOf<String, Any?>(
            "run_id" to config.runId,
            "commit" to config.commit,
            "date" to Clock.System.now().toString(),
            "role" to config.role.name.lowercase(),
            "reporter" to device(local),
            "central" to central?.let(::device),
            "peripheral" to peripheral?.let(::device),
            "test" to test,
            "bearer" to bearer,
            "phy" to phy,
            "mtu" to mtu,
            "attempt" to attempt,
            "outcome" to outcome,
            "timings" to timings,
            "bytes" to bytes,
            "error" to error,
        )
        fields.putAll(extra)
        sink.emit(Json.encode(fields))
    }

    fun log(message: String) = sink.log(message)

    private fun device(info: DeviceInfo): Map<String, Any?> =
        mapOf("model" to info.model, "os" to info.os, "platform" to info.platform)
}

object Json {
    fun encode(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> appendString(out, value)
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Double -> if (value.isFinite()) out.append(value.toString()) else out.append("null")
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) out.append(',')
                    appendString(out, entry.key.toString())
                    out.append(':')
                    append(out, entry.value)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }
            else -> appendString(out, value.toString())
        }
    }

    private fun appendString(out: StringBuilder, text: String) {
        out.append('"')
        for (character in text) {
            when {
                character == '"' -> out.append("\\\"")
                character == '\\' -> out.append("\\\\")
                character == '\n' -> out.append("\\n")
                character.code < 0x20 -> out.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                else -> out.append(character)
            }
        }
        out.append('"')
    }
}

class SampleStats(samples: List<Double>) {
    private val sorted = samples.sorted()
    val summary: Map<String, Any?> = if (sorted.isEmpty()) emptyMap() else mapOf(
        "count" to sorted.size,
        "min_ms" to round3(sorted.first()),
        "median_ms" to round3(sorted[sorted.size / 2]),
        "p90_ms" to round3(sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)]),
        "max_ms" to round3(sorted.last()),
        "samples_ms" to samples.map(::round3),
    )
}
