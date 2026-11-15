package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaintextTest {
    @Test
    fun contentShorterThanItsFixedFieldsIsTruncated() {
        val short = u32(1u) + byteArrayOf(0)

        assertEquals(DecodeError.TRUNCATED, (MessageContent.decode(short, secured = false) as Decoded.Invalid).error)
        assertEquals(DecodeError.TRUNCATED, (ReceiptContent.decode(short, secured = false) as Decoded.Invalid).error)
    }

    @Test
    fun aPayloadAboveTheMaximumIsOutOfRange() {
        val empty = message().encode(secured = false)
        val content = empty.copyOfRange(LENGTH, empty.size) + ByteArray(MessageContent.PAYLOAD_MAX + 1)
        val bytes = u32(content.size.toUInt()) + content

        assertEquals(DecodeError.OUT_OF_RANGE, (MessageContent.decode(bytes, secured = false) as Decoded.Invalid).error)
    }

    @Test
    fun constructorsRejectFieldsTheWireCannotCarry() {
        val seed = listOf(Tlv(CONVERSATION_SEED, ByteArray(ID)))
        assertFailsWith<IllegalArgumentException> { message(id = ByteArray(ID - 1)) }
        assertFailsWith<IllegalArgumentException> { message(k = ByteArray(K + 1)) }
        assertFailsWith<IllegalArgumentException> { message(extensions = seed) }
        assertFailsWith<IllegalArgumentException> { message(payload = ByteArray(MessageContent.PAYLOAD_MAX + 1)) }
        assertFailsWith<IllegalArgumentException> { ReceiptContent(ByteArray(ID + 1), 0u, emptyList()) }
        assertFailsWith<IllegalArgumentException> { ReceiptContent(ByteArray(ID), 0u, seed) }
        val huge = listOf(Tlv(1, ByteArray(HALF_U16)), Tlv(MESSAGE_IDS, ByteArray(HALF_U16)))
        assertFailsWith<IllegalArgumentException> { ReceiptContent(ByteArray(ID), 0u, huge) }
    }

    private fun message(
        id: ByteArray = ByteArray(ID),
        k: ByteArray = ByteArray(K),
        extensions: List<Tlv> = emptyList(),
        payload: ByteArray = ByteArray(0),
    ): MessageContent = MessageContent(id, 0u, k, extensions, payload)

    private companion object {
        const val LENGTH = 4
        const val ID = 12
        const val K = 16
        const val CONVERSATION_SEED = 0x82
        const val MESSAGE_IDS = 0x83
        const val HALF_U16 = 32_768
    }
}
