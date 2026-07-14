package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.platform.android.gatt.GattNotifyClient
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
    fun resolveMaximumPayloadBytesPerDeliveryReturnsGattLimitForSamePlatformPeers(): Unit {
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
        assertEquals(GattNotifyClient.maximumPayloadBytesPerDelivery(), maximumPayloadBytes)
    }

    @Test
    fun resolveMaximumPayloadBytesPerDeliveryKeepsGattWhenNoL2capBudgetExists(): Unit {
        // Arrange / Act
        val maximumPayloadBytes =
            resolveMaximumPayloadBytesPerDelivery(
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                l2capMaxTransmitPacketSize = null,
            )

        // Assert
        assertEquals(GattNotifyClient.maximumPayloadBytesPerDelivery(), maximumPayloadBytes)
    }
}
