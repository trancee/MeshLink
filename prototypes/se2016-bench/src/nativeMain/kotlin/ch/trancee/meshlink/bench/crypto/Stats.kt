package ch.trancee.meshlink.bench.crypto

import kotlin.time.TimeSource

/** Order statistics over nanosecond samples; p95 is nearest rank. */
internal class Stats(samples: LongArray) {
    private val sorted = samples.sortedArray()
    val count: Int = sorted.size
    val median: Long = if (count % 2 == 1) sorted[count / 2] else (sorted[count / 2 - 1] + sorted[count / 2]) / 2
    val p95: Long = sorted[((95 * count + 99) / 100 - 1).coerceIn(0, count - 1)]
    val min: Long = sorted.first()
    val max: Long = sorted.last()

    fun line(name: String): String =
        "${name.padEnd(42)} n=$count median=${micros(median)} us  p95=${micros(p95)} us  min=${micros(min)} us  max=${micros(max)} us"

    private fun micros(nanos: Long): String {
        val hundredths = (nanos + 5) / 10
        val text = "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
        return text.padStart(10)
    }
}

/** Accumulates the time one handshake side spends in its own calls. */
internal class SideTimer {
    var nanos: Long = 0
        private set

    inline fun <T> time(block: () -> T): T {
        val mark = TimeSource.Monotonic.markNow()
        val result = block()
        add(mark.elapsedNow().inWholeNanoseconds)
        return result
    }

    fun add(elapsed: Long) {
        nanos += elapsed
    }
}

internal inline fun measure(warmup: Int, iterations: Int, operation: () -> Unit): Stats {
    repeat(warmup) { operation() }
    val samples = LongArray(iterations)
    for (index in 0 until iterations) {
        val mark = TimeSource.Monotonic.markNow()
        operation()
        samples[index] = mark.elapsedNow().inWholeNanoseconds
    }
    return Stats(samples)
}
