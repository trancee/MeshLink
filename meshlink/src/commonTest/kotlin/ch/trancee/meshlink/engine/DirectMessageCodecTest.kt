package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.transport.DirectMessageEnvelope
import ch.trancee.meshlink.engine.transport.DirectWireFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DirectMessageCodecTest {
    @Test
    fun `direct message envelope round-trips and copies constructor arrays`() {
        // Arrange
        val ciphertext = byteArrayOf(13, 14, 15, 16)
        val envelope =
            DirectMessageEnvelope(senderPeerId = PeerId("sender-peer"), ciphertext = ciphertext)
        ciphertext[0] = 96

        // Act
        val decoded = DirectMessageEnvelope.decode(envelope.encode())

        // Assert
        assertEquals("sender-peer", decoded.senderPeerId.value)
        assertContentEquals(byteArrayOf(13, 14, 15, 16), decoded.ciphertext)
    }

    @Test
    fun `direct wire frames round-trip through the shared codec`() {
        // Arrange
        val frames =
            listOf(
                DirectWireFrame.HandshakeMessage1(byteArrayOf(1, 2, 3)),
                DirectWireFrame.HandshakeMessage2(byteArrayOf(4, 5, 6)),
                DirectWireFrame.HandshakeMessage3(byteArrayOf(7, 8, 9)),
                DirectWireFrame.Data(byteArrayOf(10, 11, 12)),
            )

        // Act
        val decodedFrames = frames.map { frame -> DirectWireFrame.decode(frame.encode()) }

        // Assert
        frames.zip(decodedFrames).forEach { (expected, actual) ->
            assertEquals(expected::class, actual::class)
            assertContentEquals(expected.payload, actual.payload)
        }
    }

    @Test
    fun `direct wire frame decode rejects unknown type codes`() {
        // Arrange
        val encoded = byteArrayOf(99.toByte(), 0, 0, 0, 0)

        // Act
        val failure =
            assertFailsWith<MeshLinkException.TransportFailure> { DirectWireFrame.decode(encoded) }

        // Assert
        assertEquals("Unknown direct wire frame type 99", failure.message)
    }
}
