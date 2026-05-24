package ch.trancee.meshlink.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MeshLinkModelsTest {
    @Test
    fun `peer id rejects blank values`() {
        // Arrange / Act
        val failure = assertFailsWith<MeshLinkException.InvalidConfiguration> { PeerId("   ") }

        // Assert
        assertEquals("peerId must not be blank", failure.message)
    }

    @Test
    fun `peer id string representation redacts all but the last six characters`() {
        // Arrange
        val peerId = PeerId("1234567890abcdef")

        // Act
        val redacted = peerId.toString()

        // Assert
        assertEquals("PeerId(...abcdef)", redacted)
    }

    @Test
    fun `inbound message copies the source payload on construction`() {
        // Arrange
        val sourcePayload = byteArrayOf(1, 2, 3, 4)
        val message =
            InboundMessage(
                originPeerId = PeerId("origin-peer"),
                payload = sourcePayload,
                receivedAtEpochMillis = 1234L,
                priority = DeliveryPriority.HIGH,
            )
        sourcePayload[0] = 9

        // Act
        val copiedPayload = message.payload
        val summary = message.toString()

        // Assert
        assertContentEquals(byteArrayOf(1, 2, 3, 4), copiedPayload)
        assertEquals(
            "InboundMessage(originPeerId=PeerId(...n-peer), receivedAtEpochMillis=1234, priority=HIGH, payloadSize=4)",
            summary,
        )
        assertFalse(summary.contains("1, 2, 3, 4"))
    }

    @Test
    fun `result wrappers expose readable toString output`() {
        // Arrange
        val notSent = SendResult.NotSent(SendFailureReason.UNREACHABLE)
        val invalidStart = StartResult.InvalidState(MeshLinkState.Paused)
        val invalidPause = PauseResult.InvalidState(MeshLinkState.Stopped)
        val invalidResume = ResumeResult.InvalidState(MeshLinkState.Uninitialized)

        // Act / Assert
        assertEquals("NotSent(reason=UNREACHABLE)", notSent.toString())
        assertEquals("InvalidState(currentState=Paused)", invalidStart.toString())
        assertEquals("InvalidState(currentState=Stopped)", invalidPause.toString())
        assertEquals("InvalidState(currentState=Uninitialized)", invalidResume.toString())
    }
}
