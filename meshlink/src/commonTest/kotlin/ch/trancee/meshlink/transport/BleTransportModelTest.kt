package ch.trancee.meshlink.transport

import ch.trancee.meshlink.api.PeerId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BleTransportModelTest {
    @Test
    fun `outbound frame copies its payload on construction`() {
        // Arrange
        val sourcePayload = byteArrayOf(1, 2, 3)
        val frame =
            OutboundFrame(
                peerId = PeerId("peer-abcdef"),
                payload = sourcePayload,
                preferredMode = TransportMode.L2CAP,
            )
        sourcePayload[0] = 9

        // Act
        val copiedPayload = frame.payload

        // Assert
        assertEquals("peer-abcdef", frame.peerId.value)
        assertEquals(TransportMode.L2CAP, frame.preferredMode)
        assertContentEquals(byteArrayOf(1, 2, 3), copiedPayload)
    }

    @Test
    fun `frame received copies its payload on construction`() {
        // Arrange
        val sourcePayload = byteArrayOf(4, 5, 6)
        val event =
            TransportEvent.FrameReceived(peerId = PeerId("peer-fedcba"), payload = sourcePayload)
        sourcePayload[0] = 7

        // Act
        val copiedPayload = event.payload

        // Assert
        assertEquals("peer-fedcba", event.peerId.value)
        assertContentEquals(byteArrayOf(4, 5, 6), copiedPayload)
    }
}
