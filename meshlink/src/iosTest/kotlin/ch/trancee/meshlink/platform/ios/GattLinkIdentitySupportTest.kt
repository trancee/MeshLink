package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GattLinkIdentitySupportTest {
    @Test
    fun unknownCentralBindsToClaimedPeerAndKeepsFollowingFrames(): Unit {
        // Arrange
        val identifier = "47e5d340-47ab-4a12-a2cb-3c769ce0c1d0"
        val claimedPeerId = PeerId("4275d7508c2201b96666e20d")
        val payloadFrame = byteArrayOf(0x0A, 0x0B, 0x0C)
        val frameBuffer = L2capFrameBuffer()
        val chunks =
            listOf(
                frameBuffer.encode(WireCodec.encode(WireFrame.LinkIdentity(claimedPeerId))),
                frameBuffer.encode(payloadFrame),
            )
        val peerBindings = PeerBindings()

        // Act
        val result =
            processUnknownGattWriteChunks(
                identifier = identifier,
                chunks = chunks,
                buffer = L2capFrameBuffer(),
                peerBindings = peerBindings,
                log = {},
            )

        // Assert
        assertTrue(result.accepted)
        assertEquals(claimedPeerId.value, result.claimedHintPeerIdValue)
        assertEquals(claimedPeerId.value, peerBindings.hintForIdentifier(identifier))
        assertEquals(1, result.decodedFrames.size)
        assertContentEquals(payloadFrame, result.decodedFrames.single())
    }
}
