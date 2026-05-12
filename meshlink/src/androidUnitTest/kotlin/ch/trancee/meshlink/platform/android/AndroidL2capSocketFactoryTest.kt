package ch.trancee.meshlink.platform.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidL2capSocketFactoryTest {
    @Test
    fun `selectInsecureFactory uses legacy factory below api 36`() {
        // Arrange
        var explicitInvoked = false
        var legacyInvoked = false

        // Act
        val selected =
            AndroidL2capSocketFactory.selectInsecureFactory(
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
        assertTrue(legacyInvoked, "Expected the legacy factory to be used below API 36")
        assertTrue(!explicitInvoked, "Expected the explicit factory to stay unused below API 36")
    }

    @Test
    fun `selectInsecureFactory prefers explicit settings on api 36 and above`() {
        // Arrange
        var explicitInvoked = false
        var legacyInvoked = false

        // Act
        val selected =
            AndroidL2capSocketFactory.selectInsecureFactory(
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
        assertTrue(explicitInvoked, "Expected the explicit factory to run on API 36+")
        assertTrue(
            !legacyInvoked,
            "Expected the legacy factory to stay unused when explicit settings succeed",
        )
    }

    @Test
    fun `selectInsecureFactory falls back to legacy settings when explicit creation fails`() {
        // Arrange
        var legacyInvoked = false
        var fallbackError: Throwable? = null

        // Act
        val selected =
            AndroidL2capSocketFactory.selectInsecureFactory(
                sdkInt = 36,
                explicitFactory = { error("boom") },
                legacyFactory = {
                    legacyInvoked = true
                    "legacy"
                },
                onExplicitFailure = { error -> fallbackError = error },
            )

        // Assert
        assertEquals("legacy", selected)
        assertTrue(
            legacyInvoked,
            "Expected the legacy factory to run after explicit creation fails",
        )
        assertEquals("boom", fallbackError?.message)
    }
}
