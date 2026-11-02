package meshlink.proto.codec

import java.lang.management.ManagementFactory

/** JVM-only: bytes allocated per op from com.sun.management.ThreadMXBean. */
object Alloc {
    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun bytesPerOp(iters: Int, body: () -> Unit): Double {
        repeat(iters) { body() } // warm up and let the JIT settle
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(iters) { body() }
        return (bean.getThreadAllocatedBytes(id) - before).toDouble() / iters
    }

    fun report(): List<String> = buildList {
        add("sample,codec,decode_bytes_per_op,encode_bytes_per_op")
        val out = ByteArray(8192)
        for (s in samples) {
            if (s.name !in benchSampleNames) continue
            for (codec in listOf(CodecA, CodecB, CodecC())) {
                val bytes = codec.encodeToArray(s.frame)
                val d = bytesPerOp(2_000_000) { sink = codec.decodeAny(s, bytes, bytes.size) }
                val e = bytesPerOp(2_000_000) { sinkInt += codec.encode(s.frame, out) }
                add("${s.name},${codec.name},${fmt(d)},${fmt(e)}")
            }
        }
    }
}
