package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import kotlin.test.Test
import kotlin.test.assertEquals

class BleTransportMaximumPayloadTest {
    @Test
    fun resolveMaximumPayloadBytesPerDeliveryReturnsGattLimitForMixedPlatformPeers(): Unit {
        // Arrange
        val l2capMaxTransmitPacketSize = 185

        // Act
        val maximumPayloadBytes =
            resolveMaximumPayloadBytesPerDelivery(
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.IOS,
                l2capMaxTransmitPacketSize = l2capMaxTransmitPacketSize,
            )

        // Assert
        assertEquals(GattNotifyClient.maximumPayloadBytesPerDelivery(), maximumPayloadBytes)
    }

    @Test
    fun resolveMaximumPayloadBytesPerDeliveryReturnsL2capBudgetForSamePlatformPeers(): Unit {
        // Arrange
        val l2capMaxTransmitPacketSize = 185

        // Act
        val maximumPayloadBytes =
            resolveMaximumPayloadBytesPerDelivery(
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                l2capMaxTransmitPacketSize = l2capMaxTransmitPacketSize,
            )

        // Assert
        assertEquals(l2capMaxTransmitPacketSize, maximumPayloadBytes)
    }

    @Test
    fun resolveMaximumPayloadBytesPerDeliveryKeepsNullWhenNoL2capBudgetExists(): Unit {
        // Arrange / Act
        val maximumPayloadBytes =
            resolveMaximumPayloadBytesPerDelivery(
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                l2capMaxTransmitPacketSize = null,
            )

        // Assert
        assertEquals(null, maximumPayloadBytes)
    }
}
