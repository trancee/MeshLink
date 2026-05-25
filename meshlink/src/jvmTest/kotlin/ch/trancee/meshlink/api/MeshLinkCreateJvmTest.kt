package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.meshLinkConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class MeshLinkCreateJvmTest {
    @Test
    fun `createMeshLinkRuntime without context supports the full lifecycle on jvm`() = runBlocking {
        // Arrange
        val config = meshLinkConfig { appId = "demo.meshlink.runtime.${Random.nextInt()}" }
        val api = createMeshLinkRuntime(config = config)

        // Act
        val results = listOf(api.start(), api.pause(), api.resume(), api.stop())

        // Assert
        assertEquals(
            expected = listOf("Started", "Paused", "Resumed", "Stopped"),
            actual = results.map { result -> result::class.simpleName },
        )
        assertEquals(expected = MeshLinkState.Stopped, actual = api.state.value)
    }
}
