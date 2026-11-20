package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BroadcastFrameTest {
    @Test
    fun anUnsecuredBroadcastDecodesItsFieldsAndReEncodes() {
        // Arrange
        val bytes = UNSECURED.hex()

        // Act
        val frame = (BroadcastFrame.decode(bytes) as Decoded.Valid).value

        // Assert
        assertEquals(HOP_LIMIT, frame.header.hopLimit)
        assertContentEquals(MESSAGE_ID.hex(), frame.header.messageId)
        assertEquals(EXPIRY, frame.header.expiry)
        val container = assertIs<Container.Unsecured>(frame.container)
        assertContentEquals(SENDER.hex(), container.senderNodeId)
        assertContentEquals("Lost dog, black".encodeToByteArray(), container.plaintext)
        assertContentEquals(bytes, frame.encode())
    }

    @Test
    fun aSignedBroadcastDecodesItsFieldsAndReEncodes() {
        val bytes = SIGNED.hex()

        val frame = (BroadcastFrame.decode(bytes) as Decoded.Valid).value

        val container = assertIs<Container.SignedBroadcast>(frame.container)
        assertEquals(Container.SIGNED_BROADCAST, container.kind)
        assertContentEquals(ORIGIN_KEY.hex(), container.originKey)
        assertContentEquals("Water at gate 3".encodeToByteArray(), container.payload)
        assertContentEquals(SIGNATURE.hex(), container.signature)
        assertContentEquals(bytes, frame.encode())
    }

    @Test
    fun aBroadcastOfTheLargestPayloadDecodesInEitherContainer() {
        val unsecured = unsecured(SPEC_PAYLOAD_MAX)
        val signed = signed(SPEC_PAYLOAD_MAX)

        val decoded = listOf(unsecured, signed).map { BroadcastFrame.decode(it) }

        assertEquals(listOf(true, true), decoded.map { it is Decoded.Valid })
    }

    @Test
    fun aMalformedBroadcastIsRejected() {
        val valid = SIGNED.hex()
        val cases =
            mapOf(
                ByteArray(0) to DecodeError.TRUNCATED,
                valid.copyOf().also { it[0] = RouteRequest.TYPE.toByte() } to DecodeError.UNKNOWN_TYPE,
                valid.copyOf(FloodHeader.LENGTH - 1) to DecodeError.TRUNCATED,
                valid.copyOf(FloodHeader.LENGTH - 1).also { it[1] = 0 } to DecodeError.TRUNCATED,
                valid.copyOf(FloodHeader.LENGTH) to DecodeError.TRUNCATED,
                valid.copyOf().also { it[1] = 0 } to DecodeError.OUT_OF_RANGE,
                valid.copyOf().also { it[KIND] = Container.TRANSPORT.toByte() } to DecodeError.UNKNOWN_KIND,
                valid.copyOf(valid.size - 1) to DecodeError.TRUNCATED,
                valid.copyOf(valid.size + 1) to DecodeError.TRAILING_BYTES,
                valid.copyOf(SIGNED_PREFIX - 1) to DecodeError.TRUNCATED,
                unsecured(BroadcastFrame.PAYLOAD_MAX + 1) to DecodeError.OUT_OF_RANGE,
                signed(BroadcastFrame.PAYLOAD_MAX + 1) to DecodeError.OUT_OF_RANGE,
            )

        val errors = cases.keys.map { (BroadcastFrame.decode(it) as Decoded.Invalid).error }

        assertEquals(cases.values.toList(), errors)
    }

    @Test
    fun aSignedBroadcastContainerIsNotAllowedInAMessage() {
        val container = SIGNED.hex().copyOfRange(FloodHeader.LENGTH, SIGNED.length / 2)
        val message = MessageFrame.decode(MESSAGE_HEADER.hex() + container)

        assertEquals(DecodeError.UNKNOWN_KIND, (message as Decoded.Invalid).error)
    }

    @Test
    fun anyFloodTypeDecodesByItsHeader() {
        val bytes = UNKNOWN_FLOOD.hex() + byteArrayOf(1, 2)

        val frame = (FloodFrame.decode(bytes) as Decoded.Valid).value

        assertEquals(LAST_FLOOD_TYPE, frame.type)
        assertEquals(HOP_LIMIT, frame.header.hopLimit)
        assertContentEquals(byteArrayOf(1, 2), frame.body)
        assertContentEquals(bytes, frame.encode())
    }

    @Test
    fun anEmptyCutUnicastOrSpentFloodFrameIsRejected() {
        val valid = UNKNOWN_FLOOD.hex()
        val cases =
            mapOf(
                ByteArray(0) to DecodeError.TRUNCATED,
                valid.copyOf(FloodHeader.LENGTH - 1) to DecodeError.TRUNCATED,
                valid.copyOf().also { it[0] = MessageFrame.TYPE.toByte() } to DecodeError.UNKNOWN_TYPE,
                valid.copyOf().also { it[0] = RESERVED_TYPE } to DecodeError.UNKNOWN_TYPE,
                valid.copyOf().also { it[1] = 0 } to DecodeError.OUT_OF_RANGE,
            )

        val errors = cases.keys.map { (FloodFrame.decode(it) as Decoded.Invalid).error }

        assertEquals(cases.values.toList(), errors)
    }

    @Test
    fun theConstructorsRejectOutOfRangeFields() {
        val header = (FloodFrame.decode(UNKNOWN_FLOOD.hex()) as Decoded.Valid).value.header
        val key = ORIGIN_KEY.hex()
        val signature = SIGNATURE.hex()
        val large = ByteArray(BroadcastFrame.PAYLOAD_MAX + 1)
        val transport = Container.Transport(0u, ByteArray(TRANSPORT_MINIMUM))

        assertFailsWith<IllegalArgumentException> { FloodFrame(MessageFrame.TYPE, header, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { FloodFrame(RESERVED_TYPE.toInt() and BYTE, header, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { Container.SignedBroadcast(ByteArray(1), ByteArray(0), signature) }
        assertFailsWith<IllegalArgumentException> { Container.SignedBroadcast(key, ByteArray(0), ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { Container.SignedBroadcast(key, large, signature) }
        assertFailsWith<IllegalArgumentException> { BroadcastFrame(header, transport) }
        assertFailsWith<IllegalArgumentException> { BroadcastFrame(header, Container.Unsecured(SENDER.hex(), large)) }
    }

    private fun unsecured(size: Int): ByteArray =
        HEADER.hex() + Container.UNSECURED.toByte() + SENDER.hex() + u32(size.toUInt()) + ByteArray(size)

    private fun signed(size: Int): ByteArray =
        HEADER.hex() + Container.SIGNED_BROADCAST.toByte() + ORIGIN_KEY.hex() + u16(size) +
            ByteArray(size) + SIGNATURE.hex()

    private companion object {
        // spec "BROADCAST": at most 1,024 payload bytes.
        const val SPEC_PAYLOAD_MAX = 1_024
        const val KIND = FloodHeader.LENGTH

        // routed-frames.json tcIds 12, 11, 44 and the header of 1.
        const val UNSECURED =
            "804057fac0f41ce4572c65194c2e6b36eed8044bcda937edd6f858cb9c7f565c916530a2b0047709dfd4512fc1c540d8d1" +
                "28530000000f4c6f737420646f672c20626c61636b"
        const val SIGNED =
            "804057fac0f41ce4572c65194c2e6b36eed8056bd4a421df32031ccfec06579097c275c3c386d639d79dc8649d5c5bf5a4" +
                "4347000f576174657220617420676174652033f63375314837554cdeaf15120173c5c30988a59aa6add252cff640374c" +
                "b0dc7d552677da1241c15e9db49f9c8d5f0673ae6aa11b93db11669450a6de6e951e11"
        const val UNKNOWN_FLOOD = "bf4057fac0f41ce4572c65194c2e6b36eed8"
        const val HEADER = "804057fac0f41ce4572c65194c2e6b36eed8"
        const val MESSAGE_HEADER =
            "40ff8f024f5d07235072ec8b12a46b36eed83975e4d6e4bc2c0505514919d593747f00112233445566778899aabbccddeeff"
        const val MESSAGE_ID = "57fac0f41ce4572c65194c2e"
        const val SENDER = "4bcda937edd6f858cb9c7f565c916530a2b0047709dfd4512fc1c540d8d12853"
        const val ORIGIN_KEY = "6bd4a421df32031ccfec06579097c275c3c386d639d79dc8649d5c5bf5a44347"
        const val SIGNATURE =
            "f63375314837554cdeaf15120173c5c30988a59aa6add252cff640374cb0dc7d552677da1241c15e9db49f9c8d5f0673ae" +
                "6aa11b93db11669450a6de6e951e11"
        const val HOP_LIMIT = 64
        const val EXPIRY = 1_798_762_200u
        const val LAST_FLOOD_TYPE = 0xBF
        const val RESERVED_TYPE: Byte = -0x40
        const val SIGNED_PREFIX = FloodHeader.LENGTH + 35
        const val TRANSPORT_MINIMUM = 17
        const val BYTE = 0xFF
    }
}
