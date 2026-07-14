package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.platform.android.gatt.IncomingGattFrameDisposition
import ch.trancee.meshlink.platform.android.gatt.resolveIncomingGattFrameDisposition
import ch.trancee.meshlink.platform.android.l2cap.L2capFrameBuffer
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GattLinkIdentitySupportTest {
    @Test
    fun unrecognizedAddressBindsToClaimedPeerBeforeDataFrameDelivery(): Unit {
        // Arrange
        val address = "47:45:3D:C7:69:CE"
        val claimedPeerId = PeerId("4275d7508c2201b96666e20d")
        val frameBuffer = L2capFrameBuffer()
        val decodedFrames = buildList {
            addAll(
                frameBuffer.append(
                    frameBuffer.encode(WireCodec.encode(WireFrame.LinkIdentity(claimedPeerId)))
                )
            )
            addAll(frameBuffer.append(frameBuffer.encode(byteArrayOf(0x01, 0x02, 0x03))))
        }
        val peerBindings = PeerBindings()
        val provisionalPeers = mutableListOf<PeerId>()
        val claimedPeers = mutableListOf<PeerId>()
        val deliveredPeers = mutableListOf<PeerId>()

        // Act
        decodedFrames.forEach { frame ->
            when (
                val disposition =
                    resolveIncomingGattFrameDisposition(
                        address = address,
                        frame = frame,
                        peerBindings = peerBindings,
                        onUnknownPeerFrame = { peerId, _ -> provisionalPeers += peerId },
                        onClaimedPeerIdentity = { peerId, _ -> claimedPeers += peerId },
                        log = {},
                    )
            ) {
                is IncomingGattFrameDisposition.ConsumedLinkIdentity -> Unit
                is IncomingGattFrameDisposition.Deliver -> deliveredPeers += disposition.peerId
            }
        }

        // Assert
        assertEquals(claimedPeerId.value, peerBindings.hintForAddress(address))
        assertTrue(provisionalPeers.isEmpty(), "LinkIdentity should prevent temporary peer minting")
        assertEquals(
            listOf(claimedPeerId.value),
            claimedPeers.map { it.value },
            "LinkIdentity claim should register a routable peer for reply sends",
        )
        assertEquals(listOf(claimedPeerId.value), deliveredPeers.map { it.value })
    }

    @Test
    fun knownAddressStillDeliversFramesWithoutRebinding(): Unit {
        // Arrange
        val address = "AA:BB:CC:DD:EE:FF"
        val knownPeerId = PeerId("known-peer-001")
        val peerBindings = PeerBindings().also { it.bindHintToAddress(address, knownPeerId.value) }

        // Act
        val disposition =
            resolveIncomingGattFrameDisposition(
                address = address,
                frame = byteArrayOf(0x01, 0x02),
                peerBindings = peerBindings,
                onUnknownPeerFrame = { _, _ -> error("known peers must not become provisional") },
                onClaimedPeerIdentity = { _, _ -> error("known peers must not be reclaimed") },
                log = {},
            )

        // Assert
        val delivered = disposition as IncomingGattFrameDisposition.Deliver
        assertEquals(knownPeerId.value, delivered.peerId.value)
    }
}
