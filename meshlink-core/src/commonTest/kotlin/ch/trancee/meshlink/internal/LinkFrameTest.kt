package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LinkFrameTest {
    @Test
    fun anUnknownCloseReasonMeansNormal() {
        assertEquals(CloseReason.DUPLICATE_LINK, LinkFrame.Close(1).reason)
        assertEquals(CloseReason.NORMAL, LinkFrame.Close(UNKNOWN_REASON).reason)
        assertEquals(CloseReason.NORMAL, CloseReason.of(-1))
        CloseReason.entries.forEach { assertEquals(it, LinkFrame.Close(it).reason) }
    }

    @Test
    fun anUnknownBusyReasonMeansReassemblySlots() {
        assertEquals(BusyReason.LARGE_BUDGET, LinkFrame.Busy(1, BusyReason.LARGE_BUDGET).reason)
        assertEquals(BusyReason.REASSEMBLY_SLOTS, LinkFrame.Busy(1, 0).reason)
        assertEquals(BusyReason.REASSEMBLY_SLOTS, LinkFrame.Busy(1, UNKNOWN_REASON).reason)
        BusyReason.entries.forEach { assertEquals(it, BusyReason.of(it.code)) }
    }

    @Test
    fun fragmentsAndBusyRoundTrip() {
        val data = ByteArray(LinkFrame.Fragment.DATA_MAX)
        val fragment = LinkFrame.Fragment(STREAM, LinkFrame.Fragment.NUMBER_MAX, true, data)
        val busy = LinkFrame.Busy(STREAM, BusyReason.LARGE_BUDGET)

        val decoded = (LinkFrame.decode(fragment.encode()) as Decoded.Valid).value as LinkFrame.Fragment
        val busyDecoded = (LinkFrame.decode(busy.encode()) as Decoded.Valid).value as LinkFrame.Busy

        assertEquals(listOf(STREAM, LinkFrame.Fragment.NUMBER_MAX), listOf(decoded.streamId, decoded.number))
        assertEquals(true, decoded.last)
        assertEquals(LinkFrame.Fragment.DATA_MAX, decoded.data.size)
        assertEquals(listOf(STREAM, BusyReason.LARGE_BUDGET.code), listOf(busyDecoded.streamId, busyDecoded.code))
    }

    @Test
    fun typesThisDecoderDoesNotHandleAreUnknownAndAnEmptyFrameIsTruncated() {
        listOf("0500", "40", "80", "bf").forEach {
            assertEquals(DecodeError.UNKNOWN_TYPE, (LinkFrame.decode(it.hex()) as Decoded.Invalid).error, it)
        }
        assertEquals(DecodeError.TRUNCATED, (LinkFrame.decode(ByteArray(0)) as Decoded.Invalid).error)
    }

    @Test
    fun framesRejectOutOfRangeFields() {
        val hello = LinkFrame.PlainHello(1, ByteArray(DIGEST), ByteArray(DIGEST)).encode()
        hello[1] = 0

        assertEquals(DecodeError.OUT_OF_RANGE, (LinkFrame.decode(hello) as Decoded.Invalid).error)
        assertFailsWith<IllegalArgumentException> { LinkFrame.PlainHello(0, ByteArray(DIGEST), ByteArray(DIGEST)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.PlainHello(256, ByteArray(DIGEST), ByteArray(DIGEST)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.PlainHello(1, ByteArray(DIGEST - 1), ByteArray(DIGEST)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.PlainHello(1, ByteArray(DIGEST), ByteArray(DIGEST + 1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Close(-1) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Handshake(0, 1, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Handshake(256, 1, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Handshake(1, 0, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Handshake(1, 4, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Handshake(1, 1, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> {
            LinkFrame.Handshake(1, 1, ByteArray(LinkFrame.Handshake.NOISE_MESSAGE_MAX + 1))
        }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Close(256) }
        assertFailsWith<IllegalArgumentException> { ResolvingKey(ByteArray(ResolvingKey.KEY_LENGTH - 1), 0u) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Fragment(-1, 0, false, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Fragment(256, 0, false, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Fragment(0, -1, false, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> {
            LinkFrame.Fragment(0, LinkFrame.Fragment.NUMBER_MAX + 1, false, ByteArray(1))
        }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Fragment(0, 0, false, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> {
            LinkFrame.Fragment(0, 0, false, ByteArray(LinkFrame.Fragment.DATA_MAX + 1))
        }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Busy(-1, 1) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Busy(256, 1) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Busy(0, 256) }
        assertFailsWith<IllegalArgumentException> { LinkFrame.Busy(0, -1) }
    }

    private companion object {
        const val UNKNOWN_REASON = 0x7F
        const val DIGEST = 32
        const val STREAM = 0xFE
    }
}
