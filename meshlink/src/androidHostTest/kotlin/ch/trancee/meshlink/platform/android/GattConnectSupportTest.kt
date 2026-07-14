package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.platform.android.gatt.connectGattSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GattConnectSupportTest {
    @Test
    fun connectGattSessionUsesTheLegacyFactoryBelowApi23(): Unit {
        // Arrange
        var leTransportInvoked = false
        var legacyInvoked = false

        // Act
        val selected =
            connectGattSession(
                sdkInt = 22,
                leTransportFactory = {
                    leTransportInvoked = true
                    "le"
                },
                legacyFactory = {
                    legacyInvoked = true
                    "legacy"
                },
            )

        // Assert
        assertEquals("legacy", selected)
        assertTrue(!leTransportInvoked)
        assertTrue(legacyInvoked)
    }

    @Test
    fun connectGattSessionUsesTheLeTransportFactoryOnApi23AndAbove(): Unit {
        // Arrange
        var leTransportInvoked = false
        var legacyInvoked = false

        // Act
        val selected =
            connectGattSession(
                sdkInt = 23,
                leTransportFactory = {
                    leTransportInvoked = true
                    "le"
                },
                legacyFactory = {
                    legacyInvoked = true
                    "legacy"
                },
            )

        // Assert
        assertEquals("le", selected)
        assertTrue(leTransportInvoked)
        assertTrue(!legacyInvoked)
    }
}
