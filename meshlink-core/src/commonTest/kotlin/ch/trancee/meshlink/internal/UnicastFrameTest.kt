package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnicastFrameTest {
    @Test
    fun anyUnicastTypeDecodesByItsHeader() {
        // Arrange
        val bytes = UNKNOWN_UNICAST.hex()

        // Act
        val frame = (UnicastFrame.decode(bytes) as Decoded.Valid).value

        // Assert
        assertEquals(TYPE_7E, frame.type)
        assertEquals(HOP_LIMIT, frame.header.hopLimit)
        assertContentEquals(BODY.hex(), frame.body)
        assertContentEquals(bytes, frame.encode())
    }

    @Test
    fun theAttemptFollowsTheHopLimitAndSurvivesARoundTrip() {
        // Arrange
        val header =
            UnicastHeader(HOP_LIMIT, ByteArray(MESSAGE_ID), 1u, ByteArray(TAG), ByteArray(TAG), attempt = ATTEMPT)

        // Act
        val bytes = UnicastFrame(TYPE_7E, header, BODY.hex()).encode()
        val decoded = (UnicastFrame.decode(bytes) as Decoded.Valid).value.header

        // Assert
        assertEquals(ATTEMPT, bytes[2].toInt())
        assertEquals(ATTEMPT, decoded.attempt)
    }

    @Test
    fun aFrameTakesAnAttemptAndKeepsEveryOtherByte() {
        // Arrange
        val bytes = UNKNOWN_UNICAST.hex()

        // Act
        val attempted = UnicastHeader.attempted(bytes, LAST_ATTEMPT + 1)

        // Assert
        assertEquals(0, (UnicastFrame.decode(attempted) as Decoded.Valid).value.header.attempt)
        val decoded = UnicastFrame.decode(UnicastHeader.attempted(bytes, ATTEMPT)) as Decoded.Valid
        assertEquals(ATTEMPT, decoded.value.header.attempt)
        assertContentEquals(bytes, attempted)
    }

    @Test
    fun theConstructorRejectsAnAttemptOutsideAByte() {
        assertFailsWith<IllegalArgumentException> {
            UnicastHeader(
                HOP_LIMIT,
                ByteArray(MESSAGE_ID),
                1u,
                ByteArray(TAG),
                ByteArray(TAG),
                attempt = LAST_ATTEMPT + 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            UnicastHeader(HOP_LIMIT, ByteArray(MESSAGE_ID), 1u, ByteArray(TAG), ByteArray(TAG), attempt = -1)
        }
    }

    @Test
    fun anEmptyCutFloodOrSpentFrameIsRejected() {
        val valid = UNKNOWN_UNICAST.hex()
        val cases =
            mapOf(
                ByteArray(0) to DecodeError.TRUNCATED,
                valid.copyOf(UnicastHeader.LENGTH - 1) to DecodeError.TRUNCATED,
                valid.copyOf().also { it[0] = FLOOD_TYPE } to DecodeError.UNKNOWN_TYPE,
                valid.copyOf().also { it[0] = BELOW_UNICAST } to DecodeError.UNKNOWN_TYPE,
                valid.copyOf().also { it[1] = 0 } to DecodeError.OUT_OF_RANGE,
            )

        val errors = cases.keys.map { (UnicastFrame.decode(it) as Decoded.Invalid).error }

        assertEquals(cases.values.toList(), errors)
    }

    @Test
    fun aHeaderOnlyFrameDecodesWithAnEmptyBody() {
        val bytes = UNKNOWN_UNICAST.hex().copyOf(UnicastHeader.LENGTH).also { it[0] = FIRST_TYPE }

        val frame = (UnicastFrame.decode(bytes) as Decoded.Valid).value

        assertEquals(MessageFrame.TYPE, frame.type)
        assertEquals(0, frame.body.size)
    }

    @Test
    fun theConstructorRejectsATypeOutsideUnicast() {
        val header = (UnicastFrame.decode(UNKNOWN_UNICAST.hex()) as Decoded.Valid).value.header

        assertFailsWith<IllegalArgumentException> { UnicastFrame(FLOOD_TYPE.toUByte().toInt(), header, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { UnicastFrame(BELOW_UNICAST.toInt(), header, ByteArray(0)) }
    }

    private companion object {
        // routed-frames.json tcId 43.
        const val UNKNOWN_UNICAST =
            "7eff008f024f5d07235072ec8b12a46b36eed83975e4d6e4bc2c0505514919d593747f0102"
        const val BODY = "0102"
        const val HOP_LIMIT = 255
        const val ATTEMPT = 7
        const val LAST_ATTEMPT = 255
        const val MESSAGE_ID = 12
        const val TAG = 8
        const val FIRST_TYPE: Byte = 0x40
        const val TYPE_7E = 0x7E
        const val FLOOD_TYPE: Byte = -0x80
        const val BELOW_UNICAST: Byte = 0x3F
    }
}
