package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.meshLinkConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class MeshLinkCreateJvmTest {
    @Test
    fun `create without context supports the full lifecycle on jvm`() = runBlocking {
        // Arrange
        val config = meshLinkConfig { appId = "demo.meshlink.${Random.nextInt()}" }
        val api = MeshLink.create(config = config)

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
