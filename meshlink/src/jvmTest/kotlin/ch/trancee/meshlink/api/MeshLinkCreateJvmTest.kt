package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.meshLinkConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

@Suppress("DEPRECATION")
class MeshLinkCreateJvmTest {
    @Test
    fun `create without context preserves the contextless factory behavior`() = runBlocking {
        // Arrange
        val config = meshLinkConfig { appId = "demo.meshlink.${Random.nextInt()}" }
        val createdApi = MeshLink.create(config = config)
        val deprecatedApi = MeshLink.createIos(config = config)

        // Act
        val createdResults =
            listOf(createdApi.start(), createdApi.pause(), createdApi.resume(), createdApi.stop())
        val deprecatedResults =
            listOf(
                deprecatedApi.start(),
                deprecatedApi.pause(),
                deprecatedApi.resume(),
                deprecatedApi.stop(),
            )

        // Assert
        assertEquals(
            expected = deprecatedResults.map { result -> result::class.simpleName },
            actual = createdResults.map { result -> result::class.simpleName },
        )
        assertEquals(expected = MeshLinkState.Stopped, actual = createdApi.state.value)
        assertEquals(expected = MeshLinkState.Stopped, actual = deprecatedApi.state.value)
    }
}
