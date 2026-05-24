package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidBleTransportMaximumPayloadTest {
    @Test
    fun resolveAndroidMaximumPayloadBytesPerDeliveryReturnsGattLimitForMixedPlatformPeers(): Unit {
        // Arrange
        val l2capMaxTransmitPacketSize = 185

        // Act
        val maximumPayloadBytes =
            resolveAndroidMaximumPayloadBytesPerDelivery(
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.IOS,
                l2capMaxTransmitPacketSize = l2capMaxTransmitPacketSize,
            )

        // Assert
        assertEquals(AndroidGattNotifyClient.maximumPayloadBytesPerDelivery(), maximumPayloadBytes)
    }

    @Test
    fun resolveAndroidMaximumPayloadBytesPerDeliveryReturnsL2capBudgetForSamePlatformPeers(): Unit {
        // Arrange
        val l2capMaxTransmitPacketSize = 185

        // Act
        val maximumPayloadBytes =
            resolveAndroidMaximumPayloadBytesPerDelivery(
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                l2capMaxTransmitPacketSize = l2capMaxTransmitPacketSize,
            )

        // Assert
        assertEquals(l2capMaxTransmitPacketSize, maximumPayloadBytes)
    }

    @Test
    fun resolveAndroidMaximumPayloadBytesPerDeliveryKeepsNullWhenNoL2capBudgetExists(): Unit {
        // Arrange / Act
        val maximumPayloadBytes =
            resolveAndroidMaximumPayloadBytesPerDelivery(
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                l2capMaxTransmitPacketSize = null,
            )

        // Assert
        assertEquals(null, maximumPayloadBytes)
    }
}
