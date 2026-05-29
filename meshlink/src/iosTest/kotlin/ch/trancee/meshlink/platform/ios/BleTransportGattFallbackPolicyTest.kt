package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.DirectWireFrame
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.GattDataBearerMode
import ch.trancee.meshlink.transport.TransportMode
import kotlin.test.Test
import kotlin.test.assertEquals

class BleTransportGattFallbackPolicyTest {
    @Test
    fun resolveIosGattDataBearerModeUsesTheGattFallbackForMixedPlatformDataFrames(): Unit {
        // Arrange
        val directFrame = DirectWireFrame.Data(ByteArray(32))

        // Act
        val bearerMode =
            resolveIosGattDataBearerMode(
                directFrame = directFrame,
                localPlatformFamily = BleDiscoveryPlatformFamily.IOS,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                preferredMode = null,
            )

        // Assert
        assertEquals(GattDataBearerMode.GATT_OPTIONAL_WITH_L2CAP_FALLBACK, bearerMode)
    }

    @Test
    fun resolveIosGattDataBearerModeKeepsHandshakeFramesOnL2cap(): Unit {
        // Arrange
        val directFrame = DirectWireFrame.HandshakeMessage1(byteArrayOf(0x01, 0x02))

        // Act
        val bearerMode =
            resolveIosGattDataBearerMode(
                directFrame = directFrame,
                localPlatformFamily = BleDiscoveryPlatformFamily.IOS,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                preferredMode = TransportMode.GATT,
            )

        // Assert
        assertEquals(GattDataBearerMode.L2CAP_ONLY, bearerMode)
    }

    @Test
    fun selectGattNotifyHintPeerIdValuePrefersTheBoundHintWhenPresent(): Unit {
        // Arrange
        val mixedPlatformPeer =
            discoveredPeer(
                hintPeerId = "peer-android",
                platformFamily = BleDiscoveryPlatformFamily.ANDROID,
            )

        // Act
        val selectedHintPeerIdValue =
            selectGattNotifyHintPeerIdValue(
                GattNotifyHintSelectionRequest(
                    boundHintPeerIdValue = "peer-bound",
                    localPlatformFamily = BleDiscoveryPlatformFamily.IOS,
                    discoveredPeers = listOf(mixedPlatformPeer),
                )
            )

        // Assert
        assertEquals("peer-bound", selectedHintPeerIdValue)
    }

    @Test
    fun selectGattNotifyHintPeerIdValueFindsTheFirstMixedPlatformPeerWhenNoBindingExists(): Unit {
        // Arrange
        val samePlatformPeer =
            discoveredPeer(hintPeerId = "peer-ios", platformFamily = BleDiscoveryPlatformFamily.IOS)
        val mixedPlatformPeer =
            discoveredPeer(
                hintPeerId = "peer-android",
                platformFamily = BleDiscoveryPlatformFamily.ANDROID,
            )

        // Act
        val selectedHintPeerIdValue =
            selectGattNotifyHintPeerIdValue(
                GattNotifyHintSelectionRequest(
                    boundHintPeerIdValue = null,
                    localPlatformFamily = BleDiscoveryPlatformFamily.IOS,
                    discoveredPeers = listOf(samePlatformPeer, mixedPlatformPeer),
                )
            )

        // Assert
        assertEquals("peer-android", selectedHintPeerIdValue)
    }
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
