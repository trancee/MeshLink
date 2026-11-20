package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RouteFramesTest {
    @Test
    fun aRouteRequestDecodesItsFieldsAndReEncodes() {
        // Arrange
        val bytes = REQUEST.hex()

        // Act
        val request = (RouteRequest.decode(bytes) as Decoded.Valid).value

        // Assert
        assertEquals(HOP_LIMIT_REQUEST, request.header.hopLimit)
        assertContentEquals("57fac0f41ce4572c65194c2e".hex(), request.header.messageId)
        assertEquals(EXPIRY, request.header.expiry)
        assertContentEquals("79a787df599f4a22".hex(), request.targetTag)
        assertContentEquals("a48619f7a310a8b3".hex(), request.originTag)
        assertEquals(1u, request.originSequence)
        assertEquals(0u, request.targetSequence)
        assertEquals(0, request.hopCount)
        assertContentEquals(bytes, request.encode())
    }

    @Test
    fun aRouteReplyDecodesItsFieldsAndReEncodes() {
        val bytes = REPLY.hex()

        val reply = (RouteReply.decode(bytes) as Decoded.Valid).value

        assertEquals(HOP_LIMIT_REPLY, reply.header.hopLimit)
        assertContentEquals("3975e4d6e4bc2c05".hex(), reply.header.destinationTag)
        assertContentEquals("05514919d593747f".hex(), reply.header.sourceTag)
        assertContentEquals("79a787df599f4a22".hex(), reply.targetTag)
        assertEquals(SEQUENCE_REPLY, reply.targetSequence)
        assertEquals(HOPS_REPLY, reply.hopCount)
        assertContentEquals(bytes, reply.encode())
    }

    @Test
    fun aMalformedRouteRequestIsRejected() {
        val valid = REQUEST.hex()
        val cases =
            mapOf(
                ByteArray(0) to DecodeError.TRUNCATED,
                valid.copyOf().also { it[0] = BROADCAST } to DecodeError.UNKNOWN_TYPE,
                valid.copyOf(RouteRequest.LENGTH - 1) to DecodeError.TRUNCATED,
                valid.copyOf(RouteRequest.LENGTH + 1) to DecodeError.TRAILING_BYTES,
                valid.copyOf().also { it[1] = 0 } to DecodeError.OUT_OF_RANGE,
                valid.copyOf().also { it[ORIGIN_SEQUENCE_LAST] = 0 } to DecodeError.OUT_OF_RANGE,
            )

        val errors = cases.keys.map { (RouteRequest.decode(it) as Decoded.Invalid).error }

        assertEquals(cases.values.toList(), errors)
    }

    @Test
    fun aMalformedRouteReplyIsRejected() {
        val valid = REPLY.hex()
        val cases =
            mapOf(
                ByteArray(0) to DecodeError.TRUNCATED,
                valid.copyOf().also { it[0] = MessageFrame.TYPE.toByte() } to DecodeError.UNKNOWN_TYPE,
                valid.copyOf(RouteReply.LENGTH - 1) to DecodeError.TRUNCATED,
                valid.copyOf(RouteReply.LENGTH + 1) to DecodeError.TRAILING_BYTES,
                valid.copyOf().also { it[1] = 0 } to DecodeError.OUT_OF_RANGE,
                valid.copyOf().also { it[TARGET_SEQUENCE_LAST] = 0 } to DecodeError.OUT_OF_RANGE,
            )

        val errors = cases.keys.map { (RouteReply.decode(it) as Decoded.Invalid).error }

        assertEquals(cases.values.toList(), errors)
    }

    @Test
    fun theConstructorsRejectOutOfRangeFields() {
        val request = (RouteRequest.decode(REQUEST.hex()) as Decoded.Valid).value
        val reply = (RouteReply.decode(REPLY.hex()) as Decoded.Valid).value
        val tag = request.targetTag

        assertFailsWith<IllegalArgumentException> { FloodHeader(0, request.header.messageId, EXPIRY) }
        assertFailsWith<IllegalArgumentException> { FloodHeader(HOP_LIMIT_REPLY + 1, request.header.messageId, EXPIRY) }
        assertFailsWith<IllegalArgumentException> { FloodHeader(1, ByteArray(1), EXPIRY) }
        assertFailsWith<IllegalArgumentException> { RouteRequest(request.header, tag, ByteArray(1), 1u, 0u, 0) }
        assertFailsWith<IllegalArgumentException> { RouteRequest(request.header, ByteArray(1), tag, 1u, 0u, 0) }
        assertFailsWith<IllegalArgumentException> { RouteRequest(request.header, tag, tag, 0u, 0u, 0) }
        assertFailsWith<IllegalArgumentException> { RouteRequest(request.header, tag, tag, 1u, 0u, -1) }
        assertFailsWith<IllegalArgumentException> { RouteRequest(request.header, tag, tag, 1u, 0u, BYTE) }
        assertFailsWith<IllegalArgumentException> { RouteReply(reply.header, ByteArray(1), 1u, 0) }
        assertFailsWith<IllegalArgumentException> { RouteReply(reply.header, tag, 0u, 0) }
        assertFailsWith<IllegalArgumentException> { RouteReply(reply.header, tag, 1u, -1) }
        assertFailsWith<IllegalArgumentException> { RouteReply(reply.header, tag, 1u, BYTE) }
    }

    private companion object {
        // routed-frames.json tcIds 13 and 8.
        const val REQUEST = "814057fac0f41ce4572c65194c2e6b36eed879a787df599f4a22a48619f7a310a8b3000000010000000000"
        const val REPLY =
            "43ff8f024f5d07235072ec8b12a46b36eed83975e4d6e4bc2c0505514919d593747f79a787df599f4a220000001103"
        const val HOP_LIMIT_REQUEST = 64
        const val HOP_LIMIT_REPLY = 255
        const val EXPIRY = 1_798_762_200u
        const val SEQUENCE_REPLY = 17u
        const val HOPS_REPLY = 3
        const val BROADCAST: Byte = -0x80
        const val ORIGIN_SEQUENCE_LAST = 37
        const val TARGET_SEQUENCE_LAST = 45
        const val BYTE = 256
    }
}
