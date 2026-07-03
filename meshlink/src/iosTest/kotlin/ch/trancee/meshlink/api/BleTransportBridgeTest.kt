package ch.trancee.meshlink.api

import ch.trancee.meshlink.api.apple.BleTransportBridge
import ch.trancee.meshlink.api.apple.BleTransportBridgeRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleTransportBridgeTest {
    @AfterTest
    fun tearDown(): Unit {
        BleTransportBridgeRegistry.clear()
    }

    @Test
    fun gattNotifyBearerIsDisabledByDefault(): Unit {
        // Arrange
        BleTransportBridgeRegistry.clear()

        // Act
        val enabled = BleTransportBridgeRegistry.isGattNotifyBearerEnabled()

        // Assert
        assertFalse(enabled)
    }

    @Test
    fun enableGattNotifyBearerTurnsTheFlagOn(): Unit {
        // Arrange
        BleTransportBridgeRegistry.clear()

        // Act
        BleTransportBridge.enableGattNotifyBearer()

        // Assert
        assertTrue(BleTransportBridgeRegistry.isGattNotifyBearerEnabled())
    }

    @Test
    fun disableGattNotifyBearerTurnsTheFlagOff(): Unit {
        // Arrange
        BleTransportBridge.enableGattNotifyBearer()

        // Act
        BleTransportBridge.disableGattNotifyBearer()

        // Assert
        assertFalse(BleTransportBridgeRegistry.isGattNotifyBearerEnabled())
    }
}
