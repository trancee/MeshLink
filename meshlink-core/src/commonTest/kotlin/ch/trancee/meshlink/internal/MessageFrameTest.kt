package ch.trancee.meshlink.internal

import ch.trancee.meshlink.vectors.LayoutVector
import ch.trancee.meshlink.vectors.routedFramesVectors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MessageFrameTest {
    private val vectors = routedFramesVectors.filter { it.encoded.startsWith("40") }

    @Test
    fun decodesEveryMessageVector() {
        assertEquals(TC_IDS, vectors.map { it.tcId })
        vectors.forEach { vector ->
            val result = MessageFrame.decode(vector.encoded.hex())
            val decoded = vector.decoded
            if (decoded == null) {
                assertIs<Decoded.Invalid>(result, "tcId ${vector.tcId}")
                assertEquals(vector.error, result.error.code, "tcId ${vector.tcId}")
            } else {
                assertFields(vector, decoded, assertIs<Decoded.Valid<MessageFrame>>(result).value)
            }
        }
    }

    @Test
    fun encodesEveryValidVector() {
        vectors.filter { it.result == "valid" }.forEach { vector ->
            val frame = (MessageFrame.decode(vector.encoded.hex()) as Decoded.Valid).value

            assertEquals(vector.encoded, frame.encode().hex(), "tcId ${vector.tcId}")
        }
    }

    @Test
    fun anEmptyOrOtherFrameIsRejected() {
        assertEquals(DecodeError.TRUNCATED, (MessageFrame.decode(ByteArray(0)) as Decoded.Invalid).error)
        val receipt = valid(3).encode().also { it[0] = RECEIPT_TYPE }

        assertEquals(DecodeError.UNKNOWN_TYPE, (MessageFrame.decode(receipt) as Decoded.Invalid).error)
    }

    @Test
    fun constructorsRejectFieldsTheWireCannotCarry() {
        val frame = valid(3)
        assertContentEquals(frame.encode(), frame.copy().encode())
        assertFailsWith<IllegalArgumentException> { frame.copy(hopLimit = 0) }
        assertFailsWith<IllegalArgumentException> { frame.copy(hopLimit = 256) }
        assertFailsWith<IllegalArgumentException> { frame.copy(messageId = ByteArray(11)) }
        assertFailsWith<IllegalArgumentException> { frame.copy(destinationTag = ByteArray(7)) }
        assertFailsWith<IllegalArgumentException> { frame.copy(sourceTag = ByteArray(9)) }
        assertFailsWith<IllegalArgumentException> { frame.copy(commitment = ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { Container.Unsecured(ByteArray(31), ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { Container.IkInitiator(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { Container.Transport(0u, ByteArray(TAG - 1)) }
    }

    @Test
    fun decoderSurvivesEveryPrefixOverlongInputsAndSeededMutations() {
        vectors.filter { it.result == "valid" }.forEach { vector ->
            mutationInputs(vector.encoded.hex()).forEach { input ->
                when (val result = MessageFrame.decode(input)) {
                    is Decoded.Valid -> assertContentEquals(input, result.value.encode())
                    is Decoded.Invalid -> Unit
                }
            }
        }
    }

    private fun valid(tcId: Int): MessageFrame =
        (MessageFrame.decode(vectors.first { it.tcId == tcId }.encoded.hex()) as Decoded.Valid).value

    private fun MessageFrame.copy(
        hopLimit: Int = header.hopLimit,
        messageId: ByteArray = header.messageId,
        destinationTag: ByteArray = header.destinationTag,
        sourceTag: ByteArray = header.sourceTag,
        commitment: ByteArray = this.commitment,
    ): MessageFrame {
        val header = UnicastHeader(hopLimit, messageId, header.expiry, destinationTag, sourceTag)
        return MessageFrame(header, commitment, container)
    }

    private fun assertFields(
        vector: LayoutVector,
        decoded: Map<String, String>,
        frame: MessageFrame,
    ) {
        val id = "tcId ${vector.tcId}"
        assertEquals(decoded.getValue("hop_limit").toInt(), frame.header.hopLimit, id)
        assertEquals(decoded.getValue("message_id"), frame.header.messageId.hex(), id)
        assertEquals(decoded.getValue("expiry").toUInt(), frame.header.expiry, id)
        assertEquals(decoded.getValue("destination_tag"), frame.header.destinationTag.hex(), id)
        assertEquals(decoded.getValue("source_tag"), frame.header.sourceTag.hex(), id)
        assertEquals(decoded.getValue("commitment"), frame.commitment.hex(), id)
        when (val container = frame.container) {
            is Container.IkInitiator -> {
                assertEquals(decoded.getValue("container.noise_message"), container.noiseMessage.hex(), id)
            }

            is Container.Transport -> {
                assertEquals(decoded.getValue("container.nonce").toULong(), container.nonce, id)
                assertEquals(decoded.getValue("container.ciphertext"), container.ciphertext.hex(), id)
            }

            is Container.Unsecured -> {
                assertEquals(decoded.getValue("container.sender_node_id"), container.senderNodeId.hex(), id)
                assertEquals(decoded.getValue("container.plaintext"), container.plaintext.hex(), id)
            }
        }
        assertEquals(decoded.getValue("container.kind").removePrefix("0x").toInt(HEX), frame.container.kind, id)
    }

    private companion object {
        val TC_IDS = listOf(1, 2, 3, 15, 16, 17, 18, 19, 20, 46, 47, 48, 49, 54, 55)
        const val RECEIPT_TYPE: Byte = 0x41
        const val TAG = 17
        const val HEX = 16
    }
}
