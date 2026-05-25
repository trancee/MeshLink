package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.meshLinkConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MeshLinkCreateAndroidTest {
    @Test
    fun `createMeshLinkRuntime without context on Android fails with helpful guidance`() {
        // Arrange
        val config = meshLinkConfig { appId = "demo.meshlink.android.runtime" }
        val expectedMessage =
            "Android context is required. Call createMeshLinkRuntime(config = ..., context = ...)."

        // Act
        val error =
            assertFailsWith<MeshLinkException.InvalidConfiguration> {
                createMeshLinkRuntime(config = config)
            }

        // Assert
        assertEquals(expected = expectedMessage, actual = error.message)
    }

    @Test
    fun `createMeshLinkRuntime with a non context bootstrap object fails as invalid configuration`() {
        // Arrange
        val config = meshLinkConfig { appId = "demo.meshlink.android.runtime" }
        val invalidContext = Any()
        val expectedMessage =
            "Android context is required. Call createMeshLinkRuntime(config = ..., context = ...)."

        // Act
        val error =
            assertFailsWith<MeshLinkException.InvalidConfiguration> {
                createMeshLinkRuntime(config = config, context = invalidContext)
            }

        // Assert
        assertEquals(expected = expectedMessage, actual = error.message)
    }
}
