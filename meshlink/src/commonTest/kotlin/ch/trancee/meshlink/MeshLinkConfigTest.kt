package ch.trancee.meshlink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MeshLinkConfigTest {
    private fun configWith(meshId: String): MeshLinkConfig =
        meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { this.meshId = meshId }.build()

    @Test
    fun buildKeepsTheChosenSettings() {
        // Act
        val config = meshLinkConfig(Preset.FieldTeams, SecurityProfile.Unsecured) { meshId = "Rescue" }.build()

        // Assert
        assertEquals(Preset.FieldTeams, config.preset)
        assertEquals(SecurityProfile.Unsecured, config.securityProfile)
        assertEquals("Rescue", config.meshId)
    }

    @Test
    fun meshIdOfOneToSixtyFourUtf8BytesIsAccepted() {
        // Spec "Mesh identifier": 1 to 64 bytes; "ä" is 2 bytes, so 32 of them are exactly 64.
        listOf("a", "a".repeat(64), "ä".repeat(32)).forEach { meshId ->
            assertEquals(meshId, configWith(meshId).meshId)
        }
    }

    @Test
    fun meshIdOutsideOneToSixtyFourUtf8BytesIsRejected() {
        // 33 × "ä" is 33 characters but 66 bytes: the limit counts bytes, not characters.
        listOf("", "a".repeat(65), "ä".repeat(33)).forEach { meshId ->
            val failure = assertFailsWith<MeshLinkException.InvalidConfig> { configWith(meshId) }
            assertEquals("meshId", failure.setting)
        }
    }

    @Test
    fun meshIdThatIsNotValidUtf8IsRejected() {
        // Arrange: a lone high surrogate has no UTF-8 encoding.
        val loneSurrogate = "mesh\uD800"

        // Act
        val failure = assertFailsWith<MeshLinkException.InvalidConfig> { configWith(loneSurrogate) }

        // Assert
        assertEquals("meshId", failure.setting)
    }

    @Test
    fun messageLifetimeComesFromThePreset() {
        // ADR 0011 amendment "Default message lifetime" (ADR 0014 decision 2).
        val expected =
            mapOf(
                Preset.OfflineChat to 1_800,
                Preset.EmergencyBroadcast to 3_600,
                Preset.ProximityDiscovery to 120,
                Preset.LocalMultiplayer to 120,
                Preset.FieldTeams to 3_600,
            )

        expected.forEach { (preset, seconds) ->
            val config = meshLinkConfig(preset, SecurityProfile.Unsecured) { meshId = "m" }.build()
            assertEquals(seconds, config.messageLifetimeSeconds, "$preset")
        }
    }

    @Test
    fun describeListsEveryEffectiveSetting() {
        // Arrange
        val config = configWith("Festival")

        // Act
        val description = config.describe()

        // Assert
        assertEquals(
            """
            preset = OfflineChat
            securityProfile = Unsecured
            meshId = "Festival"
            messageLifetimeSeconds = 1800
            """.trimIndent(),
            description,
        )
    }
}
