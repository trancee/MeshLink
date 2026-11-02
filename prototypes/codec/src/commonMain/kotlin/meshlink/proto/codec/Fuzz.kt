package meshlink.proto.codec

import kotlin.random.Random

class FuzzReport(val sample: String, val codecs: String) {
    var inputs = 0L
    var accepted = 0L
    var exceptions = 0L
    var disagreements = 0L
    var nonCanonical = 0L
    var firstProblem: String? = null
    val errors = mutableMapOf<String, Long>()

    fun problem(msg: String) {
        if (firstProblem == null) firstProblem = msg
    }

    override fun toString() =
        "$sample,$codecs,$inputs,$accepted,$exceptions,$disagreements,$nonCanonical," +
            errors.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" } +
            ",${firstProblem ?: ""}"

    companion object {
        const val HEADER = "sample,codecs,inputs,accepted,exceptions,disagreements,non_canonical,errors,first_problem"
    }
}

private fun safeDecode(c: Codec, s: Sample, input: ByteArray, r: FuzzReport): Any =
    try {
        c.decodeAny(s, input, input.size)
    } catch (t: Throwable) {
        r.exceptions++
        r.problem("${c.name} threw ${t::class.simpleName}: ${t.message} on ${hex(input, 0, input.size)}")
        t
    }

private fun key(x: Any): String = when (x) {
    is Frame -> x.describe()
    is DecodeError -> x.name
    else -> "EXC:" + (x as Throwable)::class.simpleName
}

/**
 * Mutates a valid encoding. Every input is a fresh exact-size array, so a read past the end
 * throws instead of reading stale bytes.
 */
fun mutate(seed: ByteArray, rnd: Random): ByteArray {
    val b = seed.copyOf()
    return when (rnd.nextInt(10)) {
        0, 1 -> { // flip 1..4 bits, biased to the header
            repeat(1 + rnd.nextInt(4)) {
                val i = if (rnd.nextBoolean()) rnd.nextInt(minOf(6, b.size)) else rnd.nextInt(b.size)
                b[i] = (b[i].toInt() xor (1 shl rnd.nextInt(8))).toByte()
            }
            b
        }
        2 -> { // random header bytes, version kept valid half the time
            val n = minOf(b.size, 1 + rnd.nextInt(6))
            for (i in 0 until n) b[i] = rnd.nextInt(256).toByte()
            if (rnd.nextBoolean()) b[0] = ((b[0].toInt() and 0xF8) or (seed[0].toInt() and 7)).toByte()
            b
        }
        3 -> b.copyOf(rnd.nextInt(b.size + 1)) // truncate
        4 -> b + rnd.nextBytes(1 + rnd.nextInt(16)) // extend
        5 -> { // delete one byte
            val i = rnd.nextInt(b.size)
            b.copyOfRange(0, i) + b.copyOfRange(i + 1, b.size)
        }
        6 -> { // insert one byte
            val i = rnd.nextInt(b.size + 1)
            b.copyOfRange(0, i) + byteArrayOf(rnd.nextInt(256).toByte()) + b.copyOfRange(i, b.size)
        }
        7 -> rnd.nextBytes(rnd.nextInt(b.size * 2 + 2)) // fully random
        8 -> { // random bytes with the seed's first byte (valid version and type)
            val r = rnd.nextBytes(rnd.nextInt(b.size * 2 + 2))
            if (r.isNotEmpty()) r[0] = seed[0]
            r
        }
        else -> { // random value in a random byte, then truncate or extend by a little
            b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte()
            val delta = rnd.nextInt(5) - 2
            b.copyOf(maxOf(0, b.size + delta))
        }
    }
}

/** Differential fuzz of B against C: same result for every input, accepted inputs re-encode exactly. */
fun fuzzBC(s: Sample, iterations: Int, seed: Long, cc: CodecC = CodecC()): FuzzReport {
    val r = FuzzReport(s.name, "B|C")
    val valid = CodecB.encodeToArray(s.frame)
    val rnd = Random(seed xor s.name.hashCode().toLong())
    val inputs = sequence {
        for (k in 0 until valid.size) yield(valid.copyOf(k)) // every truncated prefix
        yield(valid + byteArrayOf(0))
        yield(valid + rnd.nextBytes(32))
        repeat(iterations) { yield(mutate(valid, rnd)) }
    }
    val out = ByteArray(8192)
    for (input in inputs) {
        r.inputs++
        val rb = safeDecode(CodecB, s, input, r)
        val rc = safeDecode(cc, s, input, r)
        val kb = key(rb)
        if (kb != key(rc)) {
            r.disagreements++
            r.problem("B=$kb C=${key(rc)} on ${hex(input, 0, input.size)}")
        }
        if (rb is Frame) {
            r.accepted++
            for (codec in listOf(CodecB, cc)) {
                val n = codec.encode(rb, out)
                if (n != input.size || !out.copyOf(n).contentEquals(input)) {
                    r.nonCanonical++
                    r.problem("${codec.name} re-encode differs for ${hex(input, 0, input.size)}")
                }
            }
        } else if (rb is DecodeError) {
            r.errors[rb.name] = (r.errors[rb.name] ?: 0L) + 1
        }
    }
    return r
}

/** A is not part of the codec criterion; fuzzed for exceptions only. */
fun fuzzA(s: Sample, iterations: Int, seed: Long): FuzzReport {
    val r = FuzzReport(s.name, "A")
    val valid = CodecA.encodeToArray(s.frame)
    val rnd = Random(seed xor s.name.hashCode().toLong())
    for (k in 0..valid.size) {
        r.inputs++
        if (safeDecode(CodecA, s, valid.copyOf(k), r) is Frame) r.accepted++
    }
    repeat(iterations) {
        r.inputs++
        val x = safeDecode(CodecA, s, mutate(valid, rnd), r)
        if (x is Frame) r.accepted++ else if (x is DecodeError) r.errors[x.name] = (r.errors[x.name] ?: 0L) + 1
    }
    return r
}

/** Every strict prefix and a one-byte extension of each sample, per codec. */
fun truncationReport(): List<String> = buildList {
    add("sample,codec,size,prefixes,prefixes_accepted,prefix_exceptions,overlong_result")
    val cc = CodecC()
    for (s in samples) {
        for (codec in listOf(CodecA, CodecB, cc)) {
            val valid = codec.encodeToArray(s.frame)
            var accepted = 0
            var exceptions = 0
            for (k in 0 until valid.size) {
                try {
                    if (codec.decodeAny(s, valid.copyOf(k), k) is Frame) accepted++
                } catch (_: Throwable) {
                    exceptions++
                }
            }
            val over = valid + byteArrayOf(0)
            val overResult = try {
                when (val x = codec.decodeAny(s, over, over.size)) {
                    is Frame -> "accepted"
                    is DecodeError -> x.name
                }
            } catch (t: Throwable) {
                "EXC:${t::class.simpleName}"
            }
            add("${s.name},${codec.name},${valid.size},${valid.size},$accepted,$exceptions,$overResult")
        }
    }
}

fun sizeReport(): List<String> = buildList {
    add("sample,A_bytes,B_bytes,C_bytes,saving_B_vs_A,B_equals_C")
    val cc = CodecC()
    for (s in samples) {
        val a = CodecA.encodeToArray(s.frame)
        val b = CodecB.encodeToArray(s.frame)
        val c = cc.encodeToArray(s.frame)
        add("${s.name},${a.size},${b.size},${c.size},${a.size - b.size},${b.contentEquals(c)}")
    }
}
