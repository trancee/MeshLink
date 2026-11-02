package meshlink.proto.codec

import meshlink.proto.codec.DecodeError.OUT_OF_RANGE
import meshlink.proto.codec.DecodeError.RESERVED_BITS_SET
import meshlink.proto.codec.DecodeError.TRAILING_BYTES
import meshlink.proto.codec.DecodeError.TRUNCATED

/**
 * B: bit-packed layout, hand-written. Sub-byte fields live in a little-endian header word,
 * LSB-first (Kompact's bit order); byte strings follow on byte boundaries.
 */
object CodecB : Codec {
    override val name = "B-handwritten"

    private fun bits(word: Long, shift: Int, width: Int): Int = ((word ushr shift) and ((1L shl width) - 1)).toInt()

    private fun head(type: Int): Long = VERSION.toLong() or (type.toLong() shl 3)

    override fun encode(frame: Frame, out: ByteArray): Int {
        frame.validate()
        return when (frame) {
            is Unicast -> encUnicast(frame, out)
            is Broadcast -> encBroadcast(frame, out)
            is Fragment -> encFragment(frame, out)
            is Receipt -> encReceipt(frame, out)
            is IHave -> encIHave(frame, out)
            is Graft -> encGraft(frame, out)
            is Digest -> encDigest(frame, out)
            is Handshake -> encHandshake(frame, out)
            is Advert -> encAdvert(frame, out)
        }
    }

    override fun decode(buf: ByteArray, len: Int): DecodeResult {
        if (len < 1) return TRUNCATED
        val b0 = buf[0].toInt() and 0xFF
        if (b0 and 7 != VERSION) return DecodeError.UNSUPPORTED_VERSION
        return when (b0 ushr 3) {
            FrameType.UNICAST -> decUnicast(buf, len)
            FrameType.BROADCAST -> decBroadcast(buf, len)
            FrameType.FRAGMENT -> decFragment(buf, len)
            FrameType.RECEIPT -> decReceipt(buf, len)
            FrameType.IHAVE -> decIHave(buf, len)
            FrameType.GRAFT -> decGraft(buf, len)
            FrameType.DIGEST -> decDigest(buf, len)
            FrameType.HANDSHAKE -> decHandshake(buf, len)
            else -> DecodeError.UNKNOWN_TYPE
        }
    }

    private fun encUnicast(f: Unicast, o: ByteArray): Int {
        putLE(
            o, 0, 6,
            head(FrameType.UNICAST) or (f.flags.toLong() shl 8) or (f.hopLimit.toLong() shl 12) or
                (f.lifetime.toLong() shl 20) or (f.payloadLength.toLong() shl 32),
        )
        putLE(o, 6, 8, f.source)
        putLE(o, 14, 8, f.destination)
        putLE(o, 22, 8, f.idLo)
        putLE(o, 30, 4, f.idHi.toLong())
        var p = 34
        if (f.flags and UnicastFlags.EXTENSION != 0) {
            o[p++] = f.extLength.toByte()
            f.body.copyInto(o, p, f.extOffset, f.extOffset + f.extLength)
            p += f.extLength
        }
        f.body.copyInto(o, p, f.payloadOffset, f.payloadOffset + f.payloadLength)
        return p + f.payloadLength
    }

    private fun decUnicast(b: ByteArray, len: Int): DecodeResult {
        if (len < 34) return TRUNCATED
        val h = getLE(b, 0, 6)
        val flags = bits(h, 8, 4)
        val lifetime = bits(h, 20, 12)
        if (lifetime < MIN_LIFETIME) return OUT_OF_RANGE
        val payloadLength = bits(h, 32, 14)
        if (h ushr 46 != 0L) return RESERVED_BITS_SET
        var p = 34
        var extLength = 0
        var extOffset = 0
        if (flags and UnicastFlags.EXTENSION != 0) {
            if (len < p + 1) return TRUNCATED
            extLength = b[p++].toInt() and 0xFF
            extOffset = p
            if (len - p < extLength) return TRUNCATED
            p += extLength
        }
        if (len - p < payloadLength) return TRUNCATED
        if (len - p > payloadLength) return TRAILING_BYTES
        return Unicast(
            flags, bits(h, 12, 8), lifetime, getLE(b, 6, 8), getLE(b, 14, 8), getLE(b, 22, 8),
            getLE(b, 30, 4).toInt(), b, extOffset, extLength, p, payloadLength,
        )
    }

    private fun encBroadcast(f: Broadcast, o: ByteArray): Int {
        putLE(
            o, 0, 6,
            head(FrameType.BROADCAST) or (f.flags.toLong() shl 8) or (f.hopLimit.toLong() shl 12) or
                (f.lifetime.toLong() shl 20) or (f.payloadLength.toLong() shl 32),
        )
        putLE(o, 6, 8, f.origin)
        putLE(o, 14, 8, f.idLo)
        putLE(o, 22, 4, f.idHi.toLong())
        var p = 26
        if (f.signature != null) {
            f.signature.copyInto(o, p)
            p += 64
        }
        if (f.flags and BroadcastFlags.EXTENSION != 0) {
            o[p++] = f.extLength.toByte()
            f.body.copyInto(o, p, f.extOffset, f.extOffset + f.extLength)
            p += f.extLength
        }
        f.body.copyInto(o, p, f.payloadOffset, f.payloadOffset + f.payloadLength)
        return p + f.payloadLength
    }

    private fun decBroadcast(b: ByteArray, len: Int): DecodeResult {
        if (len < 26) return TRUNCATED
        val h = getLE(b, 0, 6)
        val flags = bits(h, 8, 4)
        if (flags and BroadcastFlags.ALL.inv() != 0) return RESERVED_BITS_SET
        val lifetime = bits(h, 20, 12)
        if (lifetime < MIN_LIFETIME) return OUT_OF_RANGE
        val payloadLength = bits(h, 32, 14)
        if (h ushr 46 != 0L) return RESERVED_BITS_SET
        var p = 26
        var signature: ByteArray? = null
        if (flags and BroadcastFlags.SIGNED != 0) {
            if (len - p < 64) return TRUNCATED
            signature = b.copyOfRange(p, p + 64)
            p += 64
        }
        var extLength = 0
        var extOffset = 0
        if (flags and BroadcastFlags.EXTENSION != 0) {
            if (len < p + 1) return TRUNCATED
            extLength = b[p++].toInt() and 0xFF
            extOffset = p
            if (len - p < extLength) return TRUNCATED
            p += extLength
        }
        if (len - p < payloadLength) return TRUNCATED
        if (len - p > payloadLength) return TRAILING_BYTES
        return Broadcast(
            flags, bits(h, 12, 8), lifetime, getLE(b, 6, 8), getLE(b, 14, 8), getLE(b, 22, 4).toInt(), signature,
            b, extOffset, extLength, p, payloadLength,
        )
    }

    private fun encFragment(f: Fragment, o: ByteArray): Int {
        val last = if (f.last) 1L else 0L
        putLE(
            o, 0, 5,
            head(FrameType.FRAGMENT) or (f.index.toLong() shl 8) or (last shl 18) or (f.offset.toLong() shl 19),
        )
        putLE(o, 5, 8, f.idLo)
        putLE(o, 13, 4, f.idHi.toLong())
        f.body.copyInto(o, 17, f.dataOffset, f.dataOffset + f.dataLength)
        return 17 + f.dataLength
    }

    private fun decFragment(b: ByteArray, len: Int): DecodeResult {
        if (len < 17) return TRUNCATED
        val h = getLE(b, 0, 5)
        val offset = bits(h, 19, 14)
        if (h ushr 33 != 0L) return RESERVED_BITS_SET
        val dataLength = len - 17
        if (dataLength < 1) return TRUNCATED
        if (offset + dataLength > MAX_MESSAGE) return OUT_OF_RANGE
        return Fragment(bits(h, 8, 10), bits(h, 18, 1) == 1, offset, getLE(b, 5, 8), getLE(b, 13, 4).toInt(), b, 17, dataLength)
    }

    private fun encReceipt(f: Receipt, o: ByteArray): Int {
        putLE(o, 0, 3, head(FrameType.RECEIPT) or (f.status.toLong() shl 8) or (f.hops.toLong() shl 11))
        putLE(o, 3, 8, f.idLo)
        putLE(o, 11, 4, f.idHi.toLong())
        return 15
    }

    private fun decReceipt(b: ByteArray, len: Int): DecodeResult {
        if (len < 15) return TRUNCATED
        val h = getLE(b, 0, 3)
        val status = bits(h, 8, 3)
        if (status > MAX_RECEIPT_STATUS) return OUT_OF_RANGE
        if (h ushr 19 != 0L) return RESERVED_BITS_SET
        if (len > 15) return TRAILING_BYTES
        return Receipt(status, bits(h, 11, 8), getLE(b, 3, 8), getLE(b, 11, 4).toInt())
    }

    private fun encIHave(f: IHave, o: ByteArray): Int {
        putLE(o, 0, 3, head(FrameType.IHAVE) or (f.idLo.size.toLong() shl 8) or (f.round.toLong() shl 14))
        var p = 3
        for (i in f.idLo.indices) {
            putLE(o, p, 8, f.idLo[i])
            putLE(o, p + 8, 4, f.idHi[i].toLong())
            p += 12
        }
        return p
    }

    private fun decIHave(b: ByteArray, len: Int): DecodeResult {
        if (len < 3) return TRUNCATED
        val h = getLE(b, 0, 3)
        val n = bits(h, 8, 6)
        if (n !in 1..MAX_IHAVE) return OUT_OF_RANGE
        if (h ushr 22 != 0L) return RESERVED_BITS_SET
        if (len - 3 < n * 12) return TRUNCATED
        if (len - 3 > n * 12) return TRAILING_BYTES
        val lo = LongArray(n)
        val hi = IntArray(n)
        for (i in 0 until n) {
            lo[i] = getLE(b, 3 + 12 * i, 8)
            hi[i] = getLE(b, 11 + 12 * i, 4).toInt()
        }
        return IHave(bits(h, 14, 8), lo, hi)
    }

    private fun encGraft(f: Graft, o: ByteArray): Int {
        putLE(o, 0, 2, head(FrameType.GRAFT) or (f.round.toLong() shl 8))
        putLE(o, 2, 8, f.idLo)
        putLE(o, 10, 4, f.idHi.toLong())
        return 14
    }

    private fun decGraft(b: ByteArray, len: Int): DecodeResult {
        if (len < 14) return TRUNCATED
        if (len > 14) return TRAILING_BYTES
        return Graft(b[1].toInt() and 0xFF, getLE(b, 2, 8), getLE(b, 10, 4).toInt())
    }

    private fun encDigest(f: Digest, o: ByteArray): Int {
        val more = if (f.moreFollows) 1L else 0L
        putLE(
            o, 0, 4,
            head(FrameType.DIGEST) or (more shl 8) or (f.window.toLong() shl 9) or (f.idLo.size.toLong() shl 15),
        )
        var p = 4
        for (i in f.idLo.indices) {
            putLE(o, p, 8, f.idLo[i])
            putLE(o, p + 8, 4, f.idHi[i].toLong())
            p += 12
        }
        return p
    }

    private fun decDigest(b: ByteArray, len: Int): DecodeResult {
        if (len < 4) return TRUNCATED
        val h = getLE(b, 0, 4)
        val window = bits(h, 9, 6)
        if (window < 1) return OUT_OF_RANGE
        val n = bits(h, 15, 10)
        if (n > MAX_DIGEST) return OUT_OF_RANGE
        if (h ushr 25 != 0L) return RESERVED_BITS_SET
        if (len - 4 < n * 12) return TRUNCATED
        if (len - 4 > n * 12) return TRAILING_BYTES
        val lo = LongArray(n)
        val hi = IntArray(n)
        for (i in 0 until n) {
            lo[i] = getLE(b, 4 + 12 * i, 8)
            hi[i] = getLE(b, 12 + 12 * i, 4).toInt()
        }
        return Digest(bits(h, 8, 1) == 1, window, lo, hi)
    }

    private fun encHandshake(f: Handshake, o: ByteArray): Int {
        val hasKey = if (f.resolvingKey != null) 1L else 0L
        putLE(
            o, 0, 6,
            head(FrameType.HANDSHAKE) or (f.suite.toLong() shl 8) or (f.capabilities.toLong() shl 12) or
                (hasKey shl 24) or (f.keyDay.toLong() shl 25),
        )
        if (f.resolvingKey == null) return 6
        f.resolvingKey.copyInto(o, 6)
        return 38
    }

    private fun decHandshake(b: ByteArray, len: Int): DecodeResult {
        if (len < 6) return TRUNCATED
        val h = getLE(b, 0, 6)
        if (h ushr 41 != 0L) return RESERVED_BITS_SET
        val hasKey = bits(h, 24, 1) == 1
        val size = if (hasKey) 38 else 6
        if (len < size) return TRUNCATED
        if (len > size) return TRAILING_BYTES
        val key = if (hasKey) b.copyOfRange(6, 38) else null
        return Handshake(bits(h, 8, 4), bits(h, 12, 12), bits(h, 25, 16), key)
    }

    private fun encAdvert(f: Advert, o: ByteArray): Int {
        putLE(
            o, 0, 4,
            1L or (f.epochLow.toLong() shl 2) or (f.capabilities.toLong() shl 6) or (f.density.toLong() shl 11) or
                (f.random.toLong() shl 13),
        )
        putLE(o, 4, 4, f.meshTag.toLong())
        putLE(o, 8, 8, f.resolvableTag)
        return ADVERT_SIZE
    }

    override fun decodeAdvert(buf: ByteArray, len: Int): DecodeResult {
        if (len < ADVERT_SIZE) return TRUNCATED
        val h = getLE(buf, 0, 4)
        if (bits(h, 0, 2) != 1) return DecodeError.UNSUPPORTED_VERSION
        val density = bits(h, 11, 2)
        if (density > MAX_DENSITY) return OUT_OF_RANGE
        if (len > ADVERT_SIZE) return TRAILING_BYTES
        return Advert(bits(h, 2, 4), bits(h, 6, 5), density, bits(h, 13, 19), getLE(buf, 4, 4).toInt(), getLE(buf, 8, 8))
    }
}
