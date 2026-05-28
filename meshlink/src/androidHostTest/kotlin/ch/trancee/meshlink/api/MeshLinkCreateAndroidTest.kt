package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.meshLinkConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MeshLinkCreateAndroidTest {
    @Test
    fun `meshLink without bootstrap on Android fails with helpful guidance`() {
        // Arrange
        val config = meshLinkConfig { appId = "demo.meshlink.android.runtime" }
        val expectedMessage =
            "Android bootstrap is required. Call meshLink(config = ..., bootstrap = meshLinkBootstrap(context))."

        // Act
        val error =
            assertFailsWith<MeshLinkException.InvalidConfiguration> { meshLink(config = config) }

        // Assert
        assertEquals(expected = expectedMessage, actual = error.message)
    }

    @Test
    fun `meshLink with a non Android bootstrap fails as invalid configuration`() {
        // Arrange
        val config = meshLinkConfig { appId = "demo.meshlink.android.runtime" }
        val invalidBootstrap = InvalidBootstrap()
        val expectedMessage =
            "Android bootstrap is required. Call meshLink(config = ..., bootstrap = meshLinkBootstrap(context))."

        // Act
        val error =
            assertFailsWith<MeshLinkException.InvalidConfiguration> {
                meshLink(config = config, bootstrap = invalidBootstrap)
            }

        // Assert
        assertEquals(expected = expectedMessage, actual = error.message)
    }
}

private class InvalidBootstrap : MeshLinkBootstrap()
