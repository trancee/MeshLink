package ch.trancee.meshlink.platform.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GattConnectSupportTest {
    @Test
    fun connectAndroidGattSessionUsesTheLegacyFactoryBelowApi23(): Unit {
        // Arrange
        var leTransportInvoked = false
        var legacyInvoked = false

        // Act
        val selected =
            connectAndroidGattSession(
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
    fun connectAndroidGattSessionUsesTheLeTransportFactoryOnApi23AndAbove(): Unit {
        // Arrange
        var leTransportInvoked = false
        var legacyInvoked = false

        // Act
        val selected =
            connectAndroidGattSession(
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
