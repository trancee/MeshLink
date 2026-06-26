package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.DirectWireFrame
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.GattDataBearerMode
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportMode
import kotlin.test.Test
import kotlin.test.assertEquals

class BleTransportSendBearerModeTest {
    @Test
    fun resolveSendDataBearerModeUsesGattFallbackForMixedPlatformDataFrames(): Unit {
        // Arrange
        val transport = testTransport()
        val peer =
            discoveredPeer(
                hintPeerId = "peer-android",
                platformFamily = BleDiscoveryPlatformFamily.ANDROID,
            )
        val dataFrame = DirectWireFrame.Data(ByteArray(32)).encode()
        val outboundFrame = OutboundFrame(peerId = peer.hintPeerId, payload = dataFrame)

        // Act
        val bearerMode =
            transport.resolveSendDataBearerMode(
                frame = outboundFrame,
                peer = peer,
                directFrame = DirectWireFrame.Data(ByteArray(32)),
            )

        // Assert
        assertEquals(GattDataBearerMode.GATT_OPTIONAL_WITH_L2CAP_FALLBACK, bearerMode)
    }

    @Test
    fun resolveSendDataBearerModeKeepsHandshakeFramesOnL2capEvenWhenGattIsPreferred(): Unit {
        // Arrange
        val transport = testTransport()
        val peer =
            discoveredPeer(
                hintPeerId = "peer-android",
                platformFamily = BleDiscoveryPlatformFamily.ANDROID,
            )
        val handshakeFrame = DirectWireFrame.HandshakeMessage1(byteArrayOf(0x01, 0x02)).encode()
        val outboundFrame =
            OutboundFrame(
                peerId = peer.hintPeerId,
                payload = handshakeFrame,
                preferredMode = TransportMode.GATT,
            )

        // Act
        val bearerMode =
            transport.resolveSendDataBearerMode(
                frame = outboundFrame,
                peer = peer,
                directFrame = DirectWireFrame.HandshakeMessage1(byteArrayOf(0x01, 0x02)),
            )

        // Assert
        assertEquals(GattDataBearerMode.L2CAP_ONLY, bearerMode)
    }

    @Test
    fun resolveSendDataBearerModeKeepsSamePlatformDataFramesOnGattFallback(): Unit {
        // Arrange
        val transport = testTransport()
        val peer =
            discoveredPeer(hintPeerId = "peer-ios", platformFamily = BleDiscoveryPlatformFamily.IOS)
        val dataFrame = DirectWireFrame.Data(ByteArray(32)).encode()
        val outboundFrame = OutboundFrame(peerId = peer.hintPeerId, payload = dataFrame)

        // Act
        val bearerMode =
            transport.resolveSendDataBearerMode(
                frame = outboundFrame,
                peer = peer,
                directFrame = DirectWireFrame.Data(ByteArray(32)),
            )

        // Assert
        assertEquals(GattDataBearerMode.GATT_OPTIONAL_WITH_L2CAP_FALLBACK, bearerMode)
    }
}

private fun testTransport(): BleTransportAdapter {
    return BleTransportAdapter(
        appId = "demo.meshlink.ios.transport",
        advertisementKeyHash = ByteArray(12) { index -> (index + 101).toByte() },
    )
}

private fun discoveredPeer(
    hintPeerId: String,
    platformFamily: BleDiscoveryPlatformFamily,
): DiscoveredPeer {
    return DiscoveredPeer(
        hintPeerId = PeerId(hintPeerId),
        state =
            DiscoveredPeerState(
                keyHash = ByteArray(12) { index -> (index + 1).toByte() },
                peripheralIdentifier = "peripheral-$hintPeerId",
                l2capPsm = 192,
                transportMode = TransportMode.L2CAP,
                platformFamily = platformFamily,
            ),
    )
}
