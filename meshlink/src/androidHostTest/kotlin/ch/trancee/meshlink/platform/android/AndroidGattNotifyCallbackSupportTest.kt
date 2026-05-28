package ch.trancee.meshlink.platform.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GattNotifyCallbackSupportTest {
    @Test
    fun selectAndroidGattNotifyCallbackUsesTheLegacyFactoryBelowApi33(): Unit {
        // Arrange
        var modernInvoked = false
        var legacyInvoked = false

        // Act
        val selected =
            selectAndroidGattNotifyCallback(
                sdkInt = 32,
                modernFactory = {
                    modernInvoked = true
                    "modern"
                },
                legacyFactory = {
                    legacyInvoked = true
                    "legacy"
                },
            )

        // Assert
        assertEquals("legacy", selected)
        assertTrue(!modernInvoked)
        assertTrue(legacyInvoked)
    }

    @Test
    fun selectAndroidGattNotifyCallbackUsesTheModernFactoryOnApi33AndAbove(): Unit {
        // Arrange
        var modernInvoked = false
        var legacyInvoked = false

        // Act
        val selected =
            selectAndroidGattNotifyCallback(
                sdkInt = 33,
                modernFactory = {
                    modernInvoked = true
                    "modern"
                },
                legacyFactory = {
                    legacyInvoked = true
                    "legacy"
                },
            )

        // Assert
        assertEquals("modern", selected)
        assertTrue(modernInvoked)
        assertTrue(!legacyInvoked)
    }
}
