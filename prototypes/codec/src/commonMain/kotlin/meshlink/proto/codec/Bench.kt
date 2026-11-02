package meshlink.proto.codec

import kotlin.time.TimeSource

/** Keeps decoded results reachable so the optimizer cannot drop the work. */
var sink: Any? = null
var sinkInt = 0

class BenchResult(val sample: String, val codec: String, val op: String, val medianNs: Double, val minNs: Double) {
    override fun toString() = "$sample,$codec,$op,${fmt(medianNs)},${fmt(minNs)}"

    companion object {
        const val HEADER = "sample,codec,op,median_ns,min_ns"
    }
}

fun fmt(x: Double): String {
    val r = kotlin.math.round(x * 10) / 10
    return r.toString()
}

private inline fun timeBatch(iters: Int, body: () -> Unit): Double {
    val mark = TimeSource.Monotonic.markNow()
    repeat(iters) { body() }
    return mark.elapsedNow().inWholeNanoseconds.toDouble() / iters
}

/**
 * JMH-free loop. Each body gets a batch size of about [batchMs]; after [warmupBatches] rounds,
 * [batches] timed rounds run the bodies interleaved (A, B, C, A, B, C, ...) so JIT or clock
 * drift hits every codec alike. Returns (median, min) ns/op per body.
 */
fun measureInterleaved(batches: Int, warmupBatches: Int, batchMs: Int, bodies: List<() -> Unit>): List<Pair<Double, Double>> {
    val iters = bodies.map { body ->
        var n = 64
        while (timeBatch(n, body) * n < batchMs * 1_000_000.0 / 4 && n < 1 shl 26) n *= 2
        n * 4
    }
    repeat(warmupBatches) { bodies.forEachIndexed { i, b -> timeBatch(iters[i], b) } }
    val xs = List(bodies.size) { DoubleArray(batches) }
    for (k in 0 until batches) bodies.forEachIndexed { i, b -> xs[i][k] = timeBatch(iters[i], b) }
    return xs.map { it.sort(); it[batches / 2] to it[0] }
}

fun runBench(
    codecs: List<Codec>,
    sampleNames: Set<String>?,
    batches: Int = 15,
    warmup: Int = 5,
    batchMs: Int = 40,
    log: (String) -> Unit = {},
): List<BenchResult> = buildList {
    val outs = codecs.map { ByteArray(8192) }
    for (s in samples) {
        if (sampleNames != null && s.name !in sampleNames) continue
        val advert = isAdvert(s)
        val decodes = codecs.map { codec ->
            val bytes = codec.encodeToArray(s.frame)
            val body: () -> Unit = {
                sink = if (advert) codec.decodeAdvert(bytes, bytes.size) else codec.decode(bytes, bytes.size)
            }
            body
        }
        val encodes = codecs.mapIndexed { i, codec ->
            val body: () -> Unit = { sinkInt += codec.encode(s.frame, outs[i]) }
            body
        }
        measureInterleaved(batches, warmup, batchMs, decodes).forEachIndexed { i, (m, min) ->
            add(BenchResult(s.name, codecs[i].name, "decode", m, min).also { log(it.toString()) })
        }
        measureInterleaved(batches, warmup, batchMs, encodes).forEachIndexed { i, (m, min) ->
            add(BenchResult(s.name, codecs[i].name, "encode", m, min).also { log(it.toString()) })
        }
    }
}

val benchSampleNames = setOf(
    "unicast", "broadcast+sig", "fragment+data20", "receipt", "ihave8", "graft", "digest10", "handshake+key", "advert",
)
