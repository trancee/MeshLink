package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.apple.BleTransportBridge
import ch.trancee.meshlink.api.apple.BleTransportBridgeRegistry
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.TransportMode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BleTransportMaximumPayloadTest {
    @AfterTest
    fun tearDown(): Unit {
        BleTransportBridgeRegistry.clear()
    }

    @Test
    fun maximumPayloadBytesPerDeliveryReturnsNullWhenTheBridgeIsMissing(): Unit {
        // Arrange
        val transport = testTransport()
        val peerId = PeerId("peer-android")
        transport.peerRegistry.upsertDiscovery(
            hintPeerId = peerId,
            discovery =
                DiscoveredPeerDiscovery(
                    identifier = "peripheral-android",
                    keyHash = ByteArray(12) { index -> (index + 1).toByte() },
                    l2capPsm = 192,
                    transportMode = TransportMode.L2CAP,
                    platformFamily = BleDiscoveryPlatformFamily.ANDROID,
                ),
        )

        // Act
        val maximumPayloadBytes = transport.maximumPayloadBytesPerDelivery(peerId)

        // Assert
        assertNull(maximumPayloadBytes)
    }

    @Test
    fun maximumPayloadBytesPerDeliveryReturnsGattNotifyLimitForMixedPlatformPeers(): Unit {
        // Arrange
        val transport = testTransport()
        val peerId = PeerId("peer-android")
        BleTransportBridge.install { _, _, _, _ -> true }
        transport.peerRegistry.upsertDiscovery(
            hintPeerId = peerId,
            discovery =
                DiscoveredPeerDiscovery(
                    identifier = "peripheral-android",
                    keyHash = ByteArray(12) { index -> (index + 1).toByte() },
                    l2capPsm = 192,
                    transportMode = TransportMode.L2CAP,
                    platformFamily = BleDiscoveryPlatformFamily.ANDROID,
                ),
        )

        // Act
        val maximumPayloadBytes = transport.maximumPayloadBytesPerDelivery(peerId)

        // Assert
        assertEquals(GattNotifyLink.maximumPayloadBytesPerDelivery(), maximumPayloadBytes)
    }

    @Test
    fun maximumPayloadBytesPerDeliveryReturnsGattLimitForSamePlatformPeersEvenWhenBridgeExists(): Unit {
        // Arrange
        val transport = testTransport()
        val peerId = PeerId("peer-ios")
        BleTransportBridge.install { _, _, _, _ -> true }
        transport.peerRegistry.upsertDiscovery(
            hintPeerId = peerId,
            discovery =
                DiscoveredPeerDiscovery(
                    identifier = "peripheral-ios",
                    keyHash = ByteArray(12) { index -> (index + 21).toByte() },
                    l2capPsm = 192,
                    transportMode = TransportMode.L2CAP,
                    platformFamily = BleDiscoveryPlatformFamily.IOS,
                ),
        )

        // Act
        val maximumPayloadBytes = transport.maximumPayloadBytesPerDelivery(peerId)

        // Assert
        assertEquals(GattNotifyLink.maximumPayloadBytesPerDelivery(), maximumPayloadBytes)
    }
}

private fun testTransport(): BleTransportAdapter {
    return BleTransportAdapter(
        appId = "demo.meshlink.ios.transport",
        advertisementKeyHash = ByteArray(12) { index -> (index + 101).toByte() },
    )
}
