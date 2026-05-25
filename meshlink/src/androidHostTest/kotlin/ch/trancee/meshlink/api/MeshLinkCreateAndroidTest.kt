package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.meshLinkConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MeshLinkCreateAndroidTest {
    @Test
    fun `meshLink without context on Android fails with helpful guidance`() {
        // Arrange
        val config = meshLinkConfig { appId = "demo.meshlink.android.runtime" }
        val expectedMessage =
            "Android context is required. Call meshLink(config = ..., context = ...)."

        // Act
        val error =
            assertFailsWith<MeshLinkException.InvalidConfiguration> { meshLink(config = config) }

        // Assert
        assertEquals(expected = expectedMessage, actual = error.message)
    }

    @Test
    fun `meshLink with a non context bootstrap object fails as invalid configuration`() {
        // Arrange
        val config = meshLinkConfig { appId = "demo.meshlink.android.runtime" }
        val invalidContext = Any()
        val expectedMessage =
            "Android context is required. Call meshLink(config = ..., context = ...)."

        // Act
        val error =
            assertFailsWith<MeshLinkException.InvalidConfiguration> {
                meshLink(config = config, context = invalidContext)
            }

        // Assert
        assertEquals(expected = expectedMessage, actual = error.message)
    }
}
