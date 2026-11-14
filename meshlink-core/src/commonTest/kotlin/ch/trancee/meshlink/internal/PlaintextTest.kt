package ch.trancee.meshlink.internal

import ch.trancee.meshlink.vectors.LayoutVector
import ch.trancee.meshlink.vectors.innerPlaintextVectors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PlaintextTest {
    @Test
    fun decodesEveryVector() {
        assertEquals((1..19).toList(), innerPlaintextVectors.map { it.tcId })
        innerPlaintextVectors.forEach { vector ->
            val result = decode(vector)
            val decoded = vector.decoded
            if (decoded == null) {
                assertIs<Decoded.Invalid>(result, "tcId ${vector.tcId}")
                assertEquals(vector.error, result.error.code, "tcId ${vector.tcId}")
            } else {
                assertFields(vector.tcId, decoded, assertIs<Decoded.Valid<Content>>(result).value)
            }
        }
    }

    @Test
    fun encodesEveryValidVector() {
        innerPlaintextVectors.filter { it.result == "valid" }.forEach { vector ->
            val content = (decode(vector) as Decoded.Valid).value

            assertEquals(vector.encoded, content.encode(secured(vector)).hex(), "tcId ${vector.tcId}")
        }
    }

    @Test
    fun decodersSurviveEveryPrefixOverlongInputsAndSeededMutations() {
        innerPlaintextVectors.filter { it.result == "valid" }.forEach { vector ->
            mutationInputs(vector.encoded.hex()).forEach { input ->
                when (val result = decode(vector, input)) {
                    is Decoded.Valid -> assertContentEquals(input, result.value.encode(secured(vector)))
                    is Decoded.Invalid -> Unit
                }
            }
        }
    }

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

    private fun secured(vector: LayoutVector): Boolean = vector.tcId <= LAST_SECURED

    private fun decode(
        vector: LayoutVector,
        bytes: ByteArray = vector.encoded.hex(),
    ): Decoded<Content> =
        if (vector.layout == "inner-message") {
            MessageContent.decode(bytes, secured(vector))
        } else {
            ReceiptContent.decode(bytes, secured(vector))
        }

    private fun assertFields(
        tcId: Int,
        decoded: Map<String, String>,
        content: Content,
    ) {
        val id = "tcId $tcId"
        assertEquals(decoded.getValue("message_id"), content.messageId.hex(), id)
        assertEquals(decoded.getValue("expiry").toUInt(), content.expiry, id)
        assertEquals(decoded.getValue("extensions.count").toInt(), content.extensions.size, id)
        content.extensions.forEachIndexed { index, tlv ->
            assertEquals(decoded.getValue("extensions.$index.type").removePrefix("0x").toInt(HEX), tlv.type, id)
            assertEquals(decoded.getValue("extensions.$index.value"), tlv.value.hex(), id)
        }
        if (content is MessageContent) {
            assertEquals(decoded.getValue("k"), content.k.hex(), id)
            assertEquals(decoded.getValue("payload"), content.payload.hex(), id)
        }
    }

    private companion object {
        const val LAST_SECURED = 11
        const val LENGTH = 4
        const val ID = 12
        const val K = 16
        const val HEX = 16
        const val CONVERSATION_SEED = 0x82
        const val MESSAGE_IDS = 0x83
        const val HALF_U16 = 32_768
    }
}
