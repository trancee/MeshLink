package ch.trancee.meshlink

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class IosPlatformTest {
    @Test
    fun nodeOnTheSimulatorIsBlockedAsUnsupported() =
        runTest {
            // Arrange
            val config = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = "Simulator" }.build()
            val node = MeshLink.create(config, MeshPlatform.ios())

            // Act
            node.start()
            val state = node.state.value
            node.close()

            // Assert
            assertEquals(MeshState.Blocked(setOf(Requirement.Unsupported)), state)
        }

    @Test
    fun everyCallReturnsThePlatformOfTheOneRadio() {
        assertSame(MeshPlatform.ios().port, MeshPlatform.ios().port)
    }
}
