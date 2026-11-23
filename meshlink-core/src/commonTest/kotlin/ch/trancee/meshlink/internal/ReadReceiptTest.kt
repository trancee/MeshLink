package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReadReceiptTest {
    @Test
    fun aReadReceiptRoundTripsItsHeaderAndContainer() {
        // Arrange
        val receipt = receipt(Container.Unsecured(ByteArray(NODE_ID) { 3 }, byteArrayOf(4, 5)))

        // Act
        val decoded = (ReadReceipt.decode(receipt.encode()) as Decoded.Valid).value

        // Assert
        assertEquals(ReadReceipt.TYPE, receipt.encode()[0].toInt())
        assertContentEquals(receipt.header.messageId, decoded.header.messageId)
        assertIs<Container.Unsecured>(decoded.container)
        assertContentEquals(receipt.encode(), decoded.encode())
    }

    @Test
    fun aCutEmptyOtherOrSpentReadReceiptIsRejected() {
        val valid = receipt(Container.Unsecured(ByteArray(NODE_ID) { 3 }, byteArrayOf(4, 5))).encode()
        val cases =
            listOf(
                ByteArray(0) to DecodeError.TRUNCATED,
                valid.copyOf().also { it[0] = DeliveryReceipt.TYPE.toByte() } to DecodeError.UNKNOWN_TYPE,
                valid.copyOf(UnicastHeader.LENGTH) to DecodeError.TRUNCATED,
                valid.copyOf().also { it[1] = 0 } to DecodeError.OUT_OF_RANGE,
                valid + byteArrayOf(0) to DecodeError.TRAILING_BYTES,
            )

        val errors = cases.map { (ReadReceipt.decode(it.first) as Decoded.Invalid).error }

        assertEquals(cases.map { it.second }, errors)
    }

    @Test
    fun onlyTheInitiatorTransportAndUnsecuredKindsAreAllowed() {
        val initiator = Container.IkInitiator(byteArrayOf(1))
        val transport = Container.Transport(7u, ByteArray(TRANSPORT_MINIMUM))
        val responder = Container.IkResponder(byteArrayOf(1, 2, 3))
        val broadcast = Container.SignedBroadcast(ByteArray(KEY), ByteArray(0), ByteArray(SIGNATURE))

        val decode = { container: Container -> ReadReceipt.decode(receipt(container).encode()) }
        val kinds = listOf(initiator, transport).map { (decode(it) as Decoded.Valid).value.container.kind }
        val refused = listOf(responder, broadcast).map { (decode(it) as Decoded.Invalid).error }

        assertEquals(listOf(Container.IK_INITIATOR, Container.TRANSPORT), kinds)
        assertEquals(listOf(DecodeError.UNKNOWN_KIND, DecodeError.UNKNOWN_KIND), refused)
    }

    private fun receipt(container: Container): ReadReceipt {
        val header =
            UnicastHeader(HOP_LIMIT, ByteArray(MESSAGE_ID) { 9 }, EXPIRY, ByteArray(TAG) { 1 }, ByteArray(TAG) { 2 })
        return ReadReceipt(header, container)
    }

    private companion object {
        const val HOP_LIMIT = 7
        const val MESSAGE_ID = 12
        const val TAG = 8
        const val NODE_ID = 32
        const val KEY = 32
        const val SIGNATURE = 64
        const val TRANSPORT_MINIMUM = 17
        const val EXPIRY = 1_798_762_200u
    }
}
