package ch.trancee.meshlink.internal

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.Requirement
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UnsupportedRadioPortTest {
    @Test
    fun nodeOnAPlatformWithoutBluetoothIsBlockedAsUnsupported() =
        runTest {
            // Arrange
            val config = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = "No radio" }.build()
            val node = MeshLink.create(config, MeshPlatform(UnsupportedRadioPort()))

            // Act
            node.start()
            val state = node.state.value
            node.close()

            // Assert
            assertEquals(MeshState.Blocked(setOf(Requirement.Unsupported)), state)
        }
}
