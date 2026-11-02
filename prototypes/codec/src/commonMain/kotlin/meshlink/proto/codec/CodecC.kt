package meshlink.proto.codec

import ch.trancee.kompact.runtime.KompactByteRange
import ch.trancee.kompact.runtime.KompactCursor
import ch.trancee.kompact.runtime.KompactStatus.OK
import meshlink.proto.codec.DecodeError.OUT_OF_RANGE
import meshlink.proto.codec.DecodeError.RESERVED_BITS_SET
import meshlink.proto.codec.DecodeError.TRAILING_BYTES
import meshlink.proto.codec.DecodeError.TRUNCATED

/**
 * C: the bit-packed layout of B through the Kompact 0.10.0 runtime, using the caller-owned
 * cursor ("holder" path). Not thread-safe: one instance per thread.
 */
class CodecC : Codec {
    override val name = "C-kompact"

    private val cursor = KompactCursor(ByteArray(0))
    private val range = KompactByteRange(ByteArray(0))

    // Unchecked after ensureAvailable; a failure here is a bug in this codec, not bad input.
    private fun KompactCursor.take(width: Int): Long {
        check(readBits(width) == OK) { "read past ensureAvailable: $status" }
        return valueBits
    }

    private fun KompactCursor.put(width: Int, value: Long) {
        check(writeUnsigned(width, value.toULong()) == OK) { "write failed: $status" }
    }

    private fun KompactCursor.put(width: Int, value: Int) = put(width, value.toLong() and 0xFFFF_FFFFL)

    private fun KompactCursor.putBytes(source: ByteArray, start: Int, end: Int) {
        range.reset(source, start, end)
        check(writeRawByteRange(range) == OK) { "raw write failed: $status" }
    }

    private fun KompactCursor.remainingBits(): Int = endBit - position

    override fun encode(frame: Frame, out: ByteArray): Int {
        frame.validate()
        val c = cursor
        check(c.reset(out) == OK)
        if (frame is Advert) return encAdvert(c, frame)
        c.put(3, VERSION)
        when (frame) {
            is Unicast -> encUnicast(c, frame)
            is Broadcast -> encBroadcast(c, frame)
            is Fragment -> encFragment(c, frame)
            is Receipt -> encReceipt(c, frame)
            is IHave -> encIHave(c, frame)
            is Graft -> encGraft(c, frame)
            is Digest -> encDigest(c, frame)
            is Handshake -> encHandshake(c, frame)
            is Advert -> error("unreachable")
        }
        check(c.position % 8 == 0)
        return c.position / 8
    }

    override fun decode(buf: ByteArray, len: Int): DecodeResult {
        val c = cursor
        check(c.reset(buf, 0, 0, len * 8) == OK)
        if (c.readBits(3) != OK) return TRUNCATED
        if (c.valueBits != VERSION.toLong()) return DecodeError.UNSUPPORTED_VERSION
        return when (c.take(5).toInt()) {
            FrameType.UNICAST -> decUnicast(c, buf)
            FrameType.BROADCAST -> decBroadcast(c, buf)
            FrameType.FRAGMENT -> decFragment(c, buf)
            FrameType.RECEIPT -> decReceipt(c)
            FrameType.IHAVE -> decIHave(c)
            FrameType.GRAFT -> decGraft(c)
            FrameType.DIGEST -> decDigest(c)
            FrameType.HANDSHAKE -> decHandshake(c, buf)
            else -> DecodeError.UNKNOWN_TYPE
        }
    }

    private fun encUnicast(c: KompactCursor, f: Unicast) {
        c.put(5, FrameType.UNICAST)
        c.put(4, f.flags)
        c.put(8, f.hopLimit)
        c.put(12, f.lifetime)
        c.put(14, f.payloadLength)
        c.put(2, 0)
        c.put(64, f.source)
        c.put(64, f.destination)
        c.put(64, f.idLo)
        c.put(32, f.idHi)
        if (f.flags and UnicastFlags.EXTENSION != 0) {
            range.reset(f.body, f.extOffset, f.extOffset + f.extLength)
            check(c.writeByteRange(8, range) == OK)
        }
        c.putBytes(f.body, f.payloadOffset, f.payloadOffset + f.payloadLength)
    }

    private fun decUnicast(c: KompactCursor, b: ByteArray): DecodeResult {
        if (c.ensureAvailable(34 * 8 - 8) != OK) return TRUNCATED
        val flags = c.take(4).toInt()
        val hopLimit = c.take(8).toInt()
        val lifetime = c.take(12).toInt()
        if (lifetime < MIN_LIFETIME) return OUT_OF_RANGE
        val payloadLength = c.take(14).toInt()
        if (c.take(2) != 0L) return RESERVED_BITS_SET
        val source = c.take(64)
        val destination = c.take(64)
        val idLo = c.take(64)
        val idHi = c.take(32).toInt()
        var extOffset = 0
        var extLength = 0
        if (flags and UnicastFlags.EXTENSION != 0) {
            if (c.readByteRange(8, range) != OK) return TRUNCATED
            extOffset = range.start
            extLength = range.size
        }
        if (c.remainingBits() < payloadLength * 8) return TRUNCATED
        if (c.remainingBits() > payloadLength * 8) return TRAILING_BYTES
        return Unicast(
            flags, hopLimit, lifetime, source, destination, idLo, idHi, b, extOffset, extLength,
            c.position / 8, payloadLength,
        )
    }

    private fun encBroadcast(c: KompactCursor, f: Broadcast) {
        c.put(5, FrameType.BROADCAST)
        c.put(4, f.flags)
        c.put(8, f.hopLimit)
        c.put(12, f.lifetime)
        c.put(14, f.payloadLength)
        c.put(2, 0)
        c.put(64, f.origin)
        c.put(64, f.idLo)
        c.put(32, f.idHi)
        if (f.signature != null) c.putBytes(f.signature, 0, 64)
        if (f.flags and BroadcastFlags.EXTENSION != 0) {
            range.reset(f.body, f.extOffset, f.extOffset + f.extLength)
            check(c.writeByteRange(8, range) == OK)
        }
        c.putBytes(f.body, f.payloadOffset, f.payloadOffset + f.payloadLength)
    }

    private fun decBroadcast(c: KompactCursor, b: ByteArray): DecodeResult {
        if (c.ensureAvailable(26 * 8 - 8) != OK) return TRUNCATED
        val flags = c.take(4).toInt()
        if (flags and BroadcastFlags.ALL.inv() != 0) return RESERVED_BITS_SET
        val hopLimit = c.take(8).toInt()
        val lifetime = c.take(12).toInt()
        if (lifetime < MIN_LIFETIME) return OUT_OF_RANGE
        val payloadLength = c.take(14).toInt()
        if (c.take(2) != 0L) return RESERVED_BITS_SET
        val origin = c.take(64)
        val idLo = c.take(64)
        val idHi = c.take(32).toInt()
        var signature: ByteArray? = null
        if (flags and BroadcastFlags.SIGNED != 0) {
            val start = c.position / 8
            if (c.skipBits(64 * 8) != OK) return TRUNCATED
            signature = b.copyOfRange(start, start + 64)
        }
        var extOffset = 0
        var extLength = 0
        if (flags and BroadcastFlags.EXTENSION != 0) {
            if (c.readByteRange(8, range) != OK) return TRUNCATED
            extOffset = range.start
            extLength = range.size
        }
        if (c.remainingBits() < payloadLength * 8) return TRUNCATED
        if (c.remainingBits() > payloadLength * 8) return TRAILING_BYTES
        return Broadcast(
            flags, hopLimit, lifetime, origin, idLo, idHi, signature, b, extOffset, extLength,
            c.position / 8, payloadLength,
        )
    }

    private fun encFragment(c: KompactCursor, f: Fragment) {
        c.put(5, FrameType.FRAGMENT)
        c.put(10, f.index)
        c.put(1, if (f.last) 1 else 0)
        c.put(14, f.offset)
        c.put(7, 0)
        c.put(64, f.idLo)
        c.put(32, f.idHi)
        c.putBytes(f.body, f.dataOffset, f.dataOffset + f.dataLength)
    }

    private fun decFragment(c: KompactCursor, b: ByteArray): DecodeResult {
        if (c.ensureAvailable(17 * 8 - 8) != OK) return TRUNCATED
        val index = c.take(10).toInt()
        val last = c.take(1) == 1L
        val offset = c.take(14).toInt()
        if (c.take(7) != 0L) return RESERVED_BITS_SET
        val idLo = c.take(64)
        val idHi = c.take(32).toInt()
        val dataLength = c.remainingBits() / 8
        if (dataLength < 1) return TRUNCATED
        if (offset + dataLength > MAX_MESSAGE) return OUT_OF_RANGE
        return Fragment(index, last, offset, idLo, idHi, b, c.position / 8, dataLength)
    }

    private fun encReceipt(c: KompactCursor, f: Receipt) {
        c.put(5, FrameType.RECEIPT)
        c.put(3, f.status)
        c.put(8, f.hops)
        c.put(5, 0)
        c.put(64, f.idLo)
        c.put(32, f.idHi)
    }

    private fun decReceipt(c: KompactCursor): DecodeResult {
        if (c.ensureAvailable(15 * 8 - 8) != OK) return TRUNCATED
        val status = c.take(3).toInt()
        if (status > MAX_RECEIPT_STATUS) return OUT_OF_RANGE
        val hops = c.take(8).toInt()
        if (c.take(5) != 0L) return RESERVED_BITS_SET
        val idLo = c.take(64)
        val idHi = c.take(32).toInt()
        if (c.remainingBits() > 0) return TRAILING_BYTES
        return Receipt(status, hops, idLo, idHi)
    }

    private fun encIHave(c: KompactCursor, f: IHave) {
        c.put(5, FrameType.IHAVE)
        c.put(6, f.idLo.size)
        c.put(8, f.round)
        c.put(2, 0)
        for (i in f.idLo.indices) {
            c.put(64, f.idLo[i])
            c.put(32, f.idHi[i])
        }
    }

    private fun decIHave(c: KompactCursor): DecodeResult {
        if (c.ensureAvailable(3 * 8 - 8) != OK) return TRUNCATED
        val n = c.take(6).toInt()
        if (n !in 1..MAX_IHAVE) return OUT_OF_RANGE
        val round = c.take(8).toInt()
        if (c.take(2) != 0L) return RESERVED_BITS_SET
        if (c.remainingBits() < n * 96) return TRUNCATED
        if (c.remainingBits() > n * 96) return TRAILING_BYTES
        val lo = LongArray(n)
        val hi = IntArray(n)
        for (i in 0 until n) {
            lo[i] = c.take(64)
            hi[i] = c.take(32).toInt()
        }
        return IHave(round, lo, hi)
    }

    private fun encGraft(c: KompactCursor, f: Graft) {
        c.put(5, FrameType.GRAFT)
        c.put(8, f.round)
        c.put(64, f.idLo)
        c.put(32, f.idHi)
    }

    private fun decGraft(c: KompactCursor): DecodeResult {
        if (c.ensureAvailable(14 * 8 - 8) != OK) return TRUNCATED
        if (c.remainingBits() > 14 * 8 - 8) return TRAILING_BYTES
        return Graft(c.take(8).toInt(), c.take(64), c.take(32).toInt())
    }

    private fun encDigest(c: KompactCursor, f: Digest) {
        c.put(5, FrameType.DIGEST)
        c.put(1, if (f.moreFollows) 1 else 0)
        c.put(6, f.window)
        c.put(10, f.idLo.size)
        c.put(7, 0)
        for (i in f.idLo.indices) {
            c.put(64, f.idLo[i])
            c.put(32, f.idHi[i])
        }
    }

    private fun decDigest(c: KompactCursor): DecodeResult {
        if (c.ensureAvailable(4 * 8 - 8) != OK) return TRUNCATED
        val more = c.take(1) == 1L
        val window = c.take(6).toInt()
        if (window < 1) return OUT_OF_RANGE
        val n = c.take(10).toInt()
        if (n > MAX_DIGEST) return OUT_OF_RANGE
        if (c.take(7) != 0L) return RESERVED_BITS_SET
        if (c.remainingBits() < n * 96) return TRUNCATED
        if (c.remainingBits() > n * 96) return TRAILING_BYTES
        val lo = LongArray(n)
        val hi = IntArray(n)
        for (i in 0 until n) {
            lo[i] = c.take(64)
            hi[i] = c.take(32).toInt()
        }
        return Digest(more, window, lo, hi)
    }

    private fun encHandshake(c: KompactCursor, f: Handshake) {
        c.put(5, FrameType.HANDSHAKE)
        c.put(4, f.suite)
        c.put(12, f.capabilities)
        c.put(1, if (f.resolvingKey != null) 1 else 0)
        c.put(16, f.keyDay)
        c.put(7, 0)
        if (f.resolvingKey != null) c.putBytes(f.resolvingKey, 0, 32)
    }

    private fun decHandshake(c: KompactCursor, b: ByteArray): DecodeResult {
        if (c.ensureAvailable(6 * 8 - 8) != OK) return TRUNCATED
        val suite = c.take(4).toInt()
        val capabilities = c.take(12).toInt()
        val hasKey = c.take(1) == 1L
        val keyDay = c.take(16).toInt()
        if (c.take(7) != 0L) return RESERVED_BITS_SET
        val keyBits = if (hasKey) 32 * 8 else 0
        if (c.remainingBits() < keyBits) return TRUNCATED
        if (c.remainingBits() > keyBits) return TRAILING_BYTES
        val key = if (hasKey) b.copyOfRange(c.position / 8, c.position / 8 + 32) else null
        return Handshake(suite, capabilities, keyDay, key)
    }

    private fun encAdvert(c: KompactCursor, f: Advert): Int {
        c.put(2, 1)
        c.put(4, f.epochLow)
        c.put(5, f.capabilities)
        c.put(2, f.density)
        c.put(19, f.random)
        c.put(32, f.meshTag)
        c.put(64, f.resolvableTag)
        return c.position / 8
    }

    override fun decodeAdvert(buf: ByteArray, len: Int): DecodeResult {
        val c = cursor
        check(c.reset(buf, 0, 0, len * 8) == OK)
        if (c.ensureAvailable(ADVERT_SIZE * 8) != OK) return TRUNCATED
        if (c.take(2) != 1L) return DecodeError.UNSUPPORTED_VERSION
        val epochLow = c.take(4).toInt()
        val capabilities = c.take(5).toInt()
        val density = c.take(2).toInt()
        if (density > MAX_DENSITY) return OUT_OF_RANGE
        val random = c.take(19).toInt()
        val meshTag = c.take(32).toInt()
        val resolvableTag = c.take(64)
        if (c.remainingBits() > 0) return TRAILING_BYTES
        return Advert(epochLow, capabilities, density, random, meshTag, resolvableTag)
    }
}
