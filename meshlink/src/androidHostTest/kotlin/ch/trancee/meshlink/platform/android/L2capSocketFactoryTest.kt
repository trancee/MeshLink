package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.platform.android.l2cap.L2capSocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class L2capSocketFactoryTest {
    @Test
    fun `selectInsecureFactory uses legacy factory below explicit socket api`() {
        // Arrange
        var explicitInvoked = false
        var legacyInvoked = false

        // Act
        val selected =
            L2capSocketFactory.selectInsecureFactory(
                sdkInt = 35,
                explicitFactory = {
                    explicitInvoked = true
                    "explicit"
                },
                legacyFactory = {
                    legacyInvoked = true
                    "legacy"
                },
            )

        // Assert
        assertEquals("legacy", selected)
        assertTrue(!explicitInvoked)
        assertTrue(legacyInvoked)
    }

    @Test
    fun `selectInsecureFactory prefers explicit settings at explicit socket api`() {
        // Arrange
        var explicitInvoked = false
        var legacyInvoked = false

        // Act
        val selected =
            L2capSocketFactory.selectInsecureFactory(
                sdkInt = 36,
                explicitFactory = {
                    explicitInvoked = true
                    "explicit"
                },
                legacyFactory = {
                    legacyInvoked = true
                    "legacy"
                },
            )

        // Assert
        assertEquals("explicit", selected)
        assertTrue(explicitInvoked)
        assertTrue(!legacyInvoked)
    }
}
