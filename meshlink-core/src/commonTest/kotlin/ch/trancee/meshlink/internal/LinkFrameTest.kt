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
    fun typesThisDecoderDoesNotHandleAreUnknownAndAnEmptyFrameIsTruncated() {
        listOf("0100", "40", "80", "bf").forEach {
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
    }

    private companion object {
        const val UNKNOWN_REASON = 0x7F
        const val DIGEST = 32
    }
}
