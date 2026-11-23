package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DeliveryReceiptTest {
    @Test
    fun aReceiptRoundTripsItsHeaderTombstonesAndContainer() {
        // Arrange
        val receipt = receipt(count = 2)

        // Act
        val decoded = (DeliveryReceipt.decode(receipt.encode()) as Decoded.Valid).value

        // Assert
        assertEquals(DeliveryReceipt.TYPE, receipt.encode()[0].toInt())
        val hex = { tombstones: List<Tombstone> -> tombstones.map { it.messageId.hex() + it.k.hex() } }
        assertEquals(hex(receipt.tombstones), hex(decoded.tombstones))
        assertContentEquals(receipt.header.messageId, decoded.header.messageId)
        assertIs<Container.Unsecured>(decoded.container)
        assertContentEquals(receipt.encode(), decoded.encode())
    }

    @Test
    fun aCutEmptyOtherSpentOrMiscountedReceiptIsRejected() {
        val valid = receipt(count = 1).encode()
        val counted = UnicastHeader.LENGTH
        val cases =
            listOf(
                ByteArray(0) to DecodeError.TRUNCATED,
                valid.copyOf().also { it[0] = MessageFrame.TYPE.toByte() } to DecodeError.UNKNOWN_TYPE,
                valid.copyOf(counted) to DecodeError.TRUNCATED,
                valid.copyOf().also { it[1] = 0 } to DecodeError.OUT_OF_RANGE,
                valid.copyOf().also { it[counted] = 0 } to DecodeError.OUT_OF_RANGE,
                valid.copyOf().also { it[counted] = (DeliveryReceipt.TOMBSTONES_MAX + 1).toByte() } to
                    DecodeError.OUT_OF_RANGE,
                valid.copyOf(counted + 1 + Tombstone.LENGTH).also { it[counted] = 2 } to DecodeError.TRUNCATED,
                valid.copyOf(counted + 1 + Tombstone.LENGTH) to DecodeError.TRUNCATED,
            )

        val errors = cases.map { (DeliveryReceipt.decode(it.first) as Decoded.Invalid).error }

        assertEquals(cases.map { it.second }, errors)
    }

    @Test
    fun onlyTheResponderTransportAndUnsecuredKindsAreAllowed() {
        val head = receipt(count = 1).encode().copyOf(UnicastHeader.LENGTH + 1 + Tombstone.LENGTH)
        val responder = Container.IkResponder(byteArrayOf(1, 2, 3))
        val transport = Container.Transport(7u, ByteArray(TRANSPORT_MINIMUM))
        val initiator = Container.IkInitiator(byteArrayOf(1))
        val broadcast = Container.SignedBroadcast(ByteArray(KEY), ByteArray(0), ByteArray(SIGNATURE))

        val decode = { container: Container -> DeliveryReceipt.decode(head + container.encode()) }
        val kinds = listOf(responder, transport).map { (decode(it) as Decoded.Valid).value.container.kind }
        val refused = listOf(initiator, broadcast).map { (decode(it) as Decoded.Invalid).error }

        assertEquals(listOf(Container.IK_RESPONDER, Container.TRANSPORT), kinds)
        assertEquals(listOf(DecodeError.UNKNOWN_KIND, DecodeError.UNKNOWN_KIND), refused)
        assertContentEquals(byteArrayOf(Container.IK_RESPONDER.toByte(), 0, 0, 0, 3, 1, 2, 3), responder.encode())
    }

    @Test
    fun constructorsRejectFieldsTheWireCannotCarry() {
        val valid = receipt(count = 1)

        assertFailsWith<IllegalArgumentException> { DeliveryReceipt(valid.header, emptyList(), valid.container) }
        assertFailsWith<IllegalArgumentException> {
            DeliveryReceipt(valid.header, List(DeliveryReceipt.TOMBSTONES_MAX + 1) { tombstone(it) }, valid.container)
        }
        assertFailsWith<IllegalArgumentException> { Tombstone(ByteArray(MESSAGE_ID - 1), ByteArray(K)) }
        assertFailsWith<IllegalArgumentException> { Tombstone(ByteArray(MESSAGE_ID), ByteArray(K + 1)) }
        assertFailsWith<IllegalArgumentException> { Container.IkResponder(ByteArray(0)) }
    }

    private fun receipt(count: Int): DeliveryReceipt {
        val header =
            UnicastHeader(HOP_LIMIT, ByteArray(MESSAGE_ID) { 9 }, EXPIRY, ByteArray(TAG) { 1 }, ByteArray(TAG) { 2 })
        val container = Container.Unsecured(ByteArray(NODE_ID) { 3 }, byteArrayOf(4, 5))
        return DeliveryReceipt(header, List(count) { tombstone(it) }, container)
    }

    private fun tombstone(index: Int): Tombstone =
        Tombstone(ByteArray(MESSAGE_ID) { index.toByte() }, ByteArray(K) { (index + 1).toByte() })

    private companion object {
        const val HOP_LIMIT = 7
        const val MESSAGE_ID = 12
        const val K = 16
        const val TAG = 8
        const val NODE_ID = 32
        const val KEY = 32
        const val SIGNATURE = 64
        const val TRANSPORT_MINIMUM = 17
        const val EXPIRY = 1_798_762_200u
    }
}
