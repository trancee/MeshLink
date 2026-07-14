package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.platform.android.gatt.selectGattNotifyCallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GattNotifyCallbackSupportTest {
    @Test
    fun selectGattNotifyCallbackUsesTheLegacyFactoryBelowApi33(): Unit {
        // Arrange
        var modernInvoked = false
        var legacyInvoked = false

        // Act
        val selected =
            selectGattNotifyCallback(
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
    fun selectGattNotifyCallbackUsesTheModernFactoryOnApi33AndAbove(): Unit {
        // Arrange
        var modernInvoked = false
        var legacyInvoked = false

        // Act
        val selected =
            selectGattNotifyCallback(
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
