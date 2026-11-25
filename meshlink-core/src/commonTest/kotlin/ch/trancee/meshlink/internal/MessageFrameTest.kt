package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessageFrameTest {
    @Test
    fun anEmptyOrOtherFrameIsRejected() {
        assertEquals(DecodeError.TRUNCATED, (MessageFrame.decode(ByteArray(0)) as Decoded.Invalid).error)
        val receipt = valid().encode().also { it[0] = RECEIPT_TYPE }

        assertEquals(DecodeError.UNKNOWN_TYPE, (MessageFrame.decode(receipt) as Decoded.Invalid).error)
    }

    @Test
    fun constructorsRejectFieldsTheWireCannotCarry() {
        val frame = valid()
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

    private fun valid(): MessageFrame = (MessageFrame.decode(UNSECURED_MESSAGE.hex()) as Decoded.Valid).value

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

    private companion object {
        const val UNSECURED_MESSAGE =
            "40ff008f024f5d07235072ec8b12a46b36eed83975e4d6e4bc2c0505514919d593747f30a53f758a8d877dbf382b43324f250c" +
                "044bcda937edd6f858cb9c7f565c916530a2b0047709dfd4512fc1c540d8d128530000000568656c6c6f"
        const val RECEIPT_TYPE: Byte = 0x41
        const val TAG = 17
    }
}
