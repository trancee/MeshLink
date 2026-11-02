package meshlink.proto.codec

import kotlin.random.Random

/** A representative placeholder frame. [headerOnly] marks the variants used for the size table. */
class Sample(val name: String, val frame: Frame, val headerOnly: Boolean, val variableBytes: Int = 0)

private fun bytes(seed: Int, n: Int): ByteArray = Random(seed).nextBytes(n)

private fun ids(seed: Int, n: Int): Pair<LongArray, IntArray> {
    val r = Random(seed)
    return LongArray(n) { r.nextLong() } to IntArray(n) { r.nextInt() }
}

fun isAdvert(s: Sample) = s.frame is Advert

/** Values span the full allowed ranges, including tags with the top bit set. */
val samples: List<Sample> = buildList {
    val empty = ByteArray(0)
    add(
        Sample(
            "unicast", Unicast(
                UnicastFlags.ACK_REQUESTED or UnicastFlags.E2E, 255, MAX_LIFETIME, -0x1234_5678_9ABC_DEF0L,
                0x0FED_CBA9_8765_4321L, -2L, -3, empty, 0, 0, 0, 0,
            ), headerOnly = true,
        ),
    )
    val body = bytes(1, 3 + 40)
    add(
        Sample(
            "unicast+ext+payload40", Unicast(
                UnicastFlags.EXTENSION or UnicastFlags.PRIORITY, 7, 120, 1L, 2L, 3L, 4, body, 0, 3, 3, 40,
            ), headerOnly = false, variableBytes = 1 + 3 + 40,
        ),
    )
    add(
        Sample(
            "broadcast+sig", Broadcast(
                BroadcastFlags.SIGNED, 200, 600, Long.MIN_VALUE, Long.MAX_VALUE, Int.MIN_VALUE, bytes(2, 64),
                empty, 0, 0, 0, 0,
            ), headerOnly = true,
        ),
    )
    add(
        Sample(
            "broadcast-unsigned", Broadcast(0, 1, 120, 5L, 6L, 7, null, empty, 0, 0, 0, 0),
            headerOnly = true,
        ),
    )
    add(
        Sample(
            "fragment+data20", Fragment(MAX_FRAGMENT_INDEX, true, MAX_OFFSET - 19, 9L, 10, bytes(3, 20), 0, 20),
            headerOnly = false, variableBytes = 20,
        ),
    )
    add(Sample("receipt", Receipt(MAX_RECEIPT_STATUS, 255, -11L, -12), headerOnly = true))
    for (n in listOf(1, 8, 32)) {
        val (lo, hi) = ids(10 + n, n)
        add(Sample("ihave$n", IHave(255, lo, hi), headerOnly = true))
    }
    add(Sample("graft", Graft(3, 13L, 14), headerOnly = true))
    for (n in listOf(0, 10, 100)) {
        val (lo, hi) = ids(20 + n, n)
        add(Sample("digest$n", Digest(true, 60, lo, hi), headerOnly = true))
    }
    add(Sample("handshake+key", Handshake(15, 0xFFF, 0xFFFF, bytes(4, 32)), headerOnly = true))
    add(Sample("handshake-nokey", Handshake(0, 0x0A5, 300, null), headerOnly = true))
    add(Sample("advert", Advert(15, 0x1F, 2, 0x7FFFF, -1, Long.MIN_VALUE + 1), headerOnly = true))
}

/** One sample per frame type for fuzzing and benchmarks. */
val fuzzSamples: List<Sample> = samples.filter {
    it.name in setOf(
        "unicast+ext+payload40", "broadcast+sig", "fragment+data20", "receipt", "ihave8", "graft", "digest10",
        "handshake+key", "advert",
    )
}

fun Codec.decodeAny(s: Sample, buf: ByteArray, len: Int): DecodeResult =
    if (isAdvert(s)) decodeAdvert(buf, len) else decode(buf, len)

fun Codec.encodeToArray(frame: Frame): ByteArray {
    val out = ByteArray(8192)
    val n = encode(frame, out)
    return out.copyOf(n)
}
