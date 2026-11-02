package meshlink.proto.codec

import meshlink.proto.codec.DecodeError.OUT_OF_RANGE
import meshlink.proto.codec.DecodeError.RESERVED_BITS_SET
import meshlink.proto.codec.DecodeError.TRAILING_BYTES
import meshlink.proto.codec.DecodeError.TRUNCATED

/** A: byte-aligned layout, hand-written. Every field owns whole bytes; integers are little-endian. */
object CodecA : Codec {
    override val name = "A-aligned"

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    override fun encode(frame: Frame, out: ByteArray): Int {
        frame.validate()
        if (frame is Advert) return encAdvert(frame, out)
        out[0] = VERSION.toByte()
        return when (frame) {
            is Unicast -> encUnicast(frame, out)
            is Broadcast -> encBroadcast(frame, out)
            is Fragment -> encFragment(frame, out)
            is Receipt -> encReceipt(frame, out)
            is IHave -> encIHave(frame, out)
            is Graft -> encGraft(frame, out)
            is Digest -> encDigest(frame, out)
            is Handshake -> encHandshake(frame, out)
            is Advert -> error("unreachable")
        }
    }

    override fun decode(buf: ByteArray, len: Int): DecodeResult {
        if (len < 2) return TRUNCATED
        if (u8(buf, 0) != VERSION) return DecodeError.UNSUPPORTED_VERSION
        return when (u8(buf, 1)) {
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
        o[1] = FrameType.UNICAST.toByte()
        o[2] = f.flags.toByte()
        o[3] = f.hopLimit.toByte()
        putLE(o, 4, 2, f.lifetime.toLong())
        putLE(o, 6, 2, f.payloadLength.toLong())
        putLE(o, 8, 8, f.source)
        putLE(o, 16, 8, f.destination)
        putLE(o, 24, 8, f.idLo)
        putLE(o, 32, 4, f.idHi.toLong())
        var p = 36
        if (f.flags and UnicastFlags.EXTENSION != 0) {
            o[p++] = f.extLength.toByte()
            f.body.copyInto(o, p, f.extOffset, f.extOffset + f.extLength)
            p += f.extLength
        }
        f.body.copyInto(o, p, f.payloadOffset, f.payloadOffset + f.payloadLength)
        return p + f.payloadLength
    }

    private fun decUnicast(b: ByteArray, len: Int): DecodeResult {
        if (len < 36) return TRUNCATED
        val flags = u8(b, 2)
        if (flags and UnicastFlags.ALL.inv() != 0) return RESERVED_BITS_SET
        val lifetime = getLE(b, 4, 2).toInt()
        if (lifetime !in MIN_LIFETIME..MAX_LIFETIME) return OUT_OF_RANGE
        val payloadLength = getLE(b, 6, 2).toInt()
        if (payloadLength > MAX_PAYLOAD) return OUT_OF_RANGE
        var p = 36
        var extLength = 0
        var extOffset = 0
        if (flags and UnicastFlags.EXTENSION != 0) {
            if (len < p + 1) return TRUNCATED
            extLength = u8(b, p++)
            extOffset = p
            if (len - p < extLength) return TRUNCATED
            p += extLength
        }
        if (len - p < payloadLength) return TRUNCATED
        if (len - p > payloadLength) return TRAILING_BYTES
        return Unicast(
            flags, u8(b, 3), lifetime, getLE(b, 8, 8), getLE(b, 16, 8), getLE(b, 24, 8), getLE(b, 32, 4).toInt(),
            b, extOffset, extLength, p, payloadLength,
        )
    }

    private fun encBroadcast(f: Broadcast, o: ByteArray): Int {
        o[1] = FrameType.BROADCAST.toByte()
        o[2] = f.flags.toByte()
        o[3] = f.hopLimit.toByte()
        putLE(o, 4, 2, f.lifetime.toLong())
        putLE(o, 6, 2, f.payloadLength.toLong())
        putLE(o, 8, 8, f.origin)
        putLE(o, 16, 8, f.idLo)
        putLE(o, 24, 4, f.idHi.toLong())
        var p = 28
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
        if (len < 28) return TRUNCATED
        val flags = u8(b, 2)
        if (flags and BroadcastFlags.ALL.inv() != 0) return RESERVED_BITS_SET
        val lifetime = getLE(b, 4, 2).toInt()
        if (lifetime !in MIN_LIFETIME..MAX_LIFETIME) return OUT_OF_RANGE
        val payloadLength = getLE(b, 6, 2).toInt()
        if (payloadLength > MAX_PAYLOAD) return OUT_OF_RANGE
        var p = 28
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
            extLength = u8(b, p++)
            extOffset = p
            if (len - p < extLength) return TRUNCATED
            p += extLength
        }
        if (len - p < payloadLength) return TRUNCATED
        if (len - p > payloadLength) return TRAILING_BYTES
        return Broadcast(
            flags, u8(b, 3), lifetime, getLE(b, 8, 8), getLE(b, 16, 8), getLE(b, 24, 4).toInt(), signature,
            b, extOffset, extLength, p, payloadLength,
        )
    }

    private fun encFragment(f: Fragment, o: ByteArray): Int {
        o[1] = FrameType.FRAGMENT.toByte()
        putLE(o, 2, 2, f.index.toLong())
        o[4] = if (f.last) 1 else 0
        putLE(o, 5, 2, f.offset.toLong())
        putLE(o, 7, 8, f.idLo)
        putLE(o, 15, 4, f.idHi.toLong())
        f.body.copyInto(o, 19, f.dataOffset, f.dataOffset + f.dataLength)
        return 19 + f.dataLength
    }

    private fun decFragment(b: ByteArray, len: Int): DecodeResult {
        if (len < 19) return TRUNCATED
        val index = getLE(b, 2, 2).toInt()
        if (index > MAX_FRAGMENT_INDEX) return OUT_OF_RANGE
        val last = u8(b, 4)
        if (last > 1) return RESERVED_BITS_SET
        val offset = getLE(b, 5, 2).toInt()
        if (offset > MAX_OFFSET) return OUT_OF_RANGE
        val dataLength = len - 19
        if (dataLength < 1) return TRUNCATED
        if (offset + dataLength > MAX_MESSAGE) return OUT_OF_RANGE
        return Fragment(index, last == 1, offset, getLE(b, 7, 8), getLE(b, 15, 4).toInt(), b, 19, dataLength)
    }

    private fun encReceipt(f: Receipt, o: ByteArray): Int {
        o[1] = FrameType.RECEIPT.toByte()
        o[2] = f.status.toByte()
        o[3] = f.hops.toByte()
        putLE(o, 4, 8, f.idLo)
        putLE(o, 12, 4, f.idHi.toLong())
        return 16
    }

    private fun decReceipt(b: ByteArray, len: Int): DecodeResult {
        if (len < 16) return TRUNCATED
        val status = u8(b, 2)
        if (status > MAX_RECEIPT_STATUS) return OUT_OF_RANGE
        if (len > 16) return TRAILING_BYTES
        return Receipt(status, u8(b, 3), getLE(b, 4, 8), getLE(b, 12, 4).toInt())
    }

    private fun encIHave(f: IHave, o: ByteArray): Int {
        o[1] = FrameType.IHAVE.toByte()
        o[2] = f.idLo.size.toByte()
        o[3] = f.round.toByte()
        var p = 4
        for (i in f.idLo.indices) {
            putLE(o, p, 8, f.idLo[i])
            putLE(o, p + 8, 4, f.idHi[i].toLong())
            p += 12
        }
        return p
    }

    private fun decIHave(b: ByteArray, len: Int): DecodeResult {
        if (len < 4) return TRUNCATED
        val n = u8(b, 2)
        if (n !in 1..MAX_IHAVE) return OUT_OF_RANGE
        if (len - 4 < n * 12) return TRUNCATED
        if (len - 4 > n * 12) return TRAILING_BYTES
        val lo = LongArray(n)
        val hi = IntArray(n)
        for (i in 0 until n) {
            lo[i] = getLE(b, 4 + 12 * i, 8)
            hi[i] = getLE(b, 12 + 12 * i, 4).toInt()
        }
        return IHave(u8(b, 3), lo, hi)
    }

    private fun encGraft(f: Graft, o: ByteArray): Int {
        o[1] = FrameType.GRAFT.toByte()
        o[2] = f.round.toByte()
        putLE(o, 3, 8, f.idLo)
        putLE(o, 11, 4, f.idHi.toLong())
        return 15
    }

    private fun decGraft(b: ByteArray, len: Int): DecodeResult {
        if (len < 15) return TRUNCATED
        if (len > 15) return TRAILING_BYTES
        return Graft(u8(b, 2), getLE(b, 3, 8), getLE(b, 11, 4).toInt())
    }

    private fun encDigest(f: Digest, o: ByteArray): Int {
        o[1] = FrameType.DIGEST.toByte()
        o[2] = if (f.moreFollows) 1 else 0
        o[3] = f.window.toByte()
        putLE(o, 4, 2, f.idLo.size.toLong())
        var p = 6
        for (i in f.idLo.indices) {
            putLE(o, p, 8, f.idLo[i])
            putLE(o, p + 8, 4, f.idHi[i].toLong())
            p += 12
        }
        return p
    }

    private fun decDigest(b: ByteArray, len: Int): DecodeResult {
        if (len < 6) return TRUNCATED
        val more = u8(b, 2)
        if (more > 1) return RESERVED_BITS_SET
        val window = u8(b, 3)
        if (window !in 1..MAX_WINDOW) return OUT_OF_RANGE
        val n = getLE(b, 4, 2).toInt()
        if (n > MAX_DIGEST) return OUT_OF_RANGE
        if (len - 6 < n * 12) return TRUNCATED
        if (len - 6 > n * 12) return TRAILING_BYTES
        val lo = LongArray(n)
        val hi = IntArray(n)
        for (i in 0 until n) {
            lo[i] = getLE(b, 6 + 12 * i, 8)
            hi[i] = getLE(b, 14 + 12 * i, 4).toInt()
        }
        return Digest(more == 1, window, lo, hi)
    }

    private fun encHandshake(f: Handshake, o: ByteArray): Int {
        o[1] = FrameType.HANDSHAKE.toByte()
        o[2] = f.suite.toByte()
        putLE(o, 3, 2, f.capabilities.toLong())
        o[5] = if (f.resolvingKey != null) 1 else 0
        putLE(o, 6, 2, f.keyDay.toLong())
        if (f.resolvingKey == null) return 8
        f.resolvingKey.copyInto(o, 8)
        return 40
    }

    private fun decHandshake(b: ByteArray, len: Int): DecodeResult {
        if (len < 8) return TRUNCATED
        val suite = u8(b, 2)
        if (suite > 15) return OUT_OF_RANGE
        val caps = getLE(b, 3, 2).toInt()
        if (caps > 0xFFF) return RESERVED_BITS_SET
        val hasKey = u8(b, 5)
        if (hasKey > 1) return RESERVED_BITS_SET
        val size = if (hasKey == 1) 40 else 8
        if (len < size) return TRUNCATED
        if (len > size) return TRAILING_BYTES
        val key = if (hasKey == 1) b.copyOfRange(8, 40) else null
        return Handshake(suite, caps, getLE(b, 6, 2).toInt(), key)
    }

    // A has no room for the random fill: it is dropped on encode and decodes as 0.
    private fun encAdvert(f: Advert, o: ByteArray): Int {
        o[0] = 1
        o[1] = f.epochLow.toByte()
        o[2] = f.capabilities.toByte()
        o[3] = f.density.toByte()
        putLE(o, 4, 4, f.meshTag.toLong())
        putLE(o, 8, 8, f.resolvableTag)
        return ADVERT_SIZE
    }

    override fun decodeAdvert(buf: ByteArray, len: Int): DecodeResult {
        if (len < ADVERT_SIZE) return TRUNCATED
        if (u8(buf, 0) != 1) return DecodeError.UNSUPPORTED_VERSION
        val epoch = u8(buf, 1)
        if (epoch > 15) return RESERVED_BITS_SET
        val caps = u8(buf, 2)
        if (caps > 0x1F) return RESERVED_BITS_SET
        val density = u8(buf, 3)
        if (density > MAX_DENSITY) return OUT_OF_RANGE
        if (len > ADVERT_SIZE) return TRAILING_BYTES
        return Advert(epoch, caps, density, 0, getLE(buf, 4, 4).toInt(), getLE(buf, 8, 8))
    }
}
