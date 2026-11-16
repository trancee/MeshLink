package ch.trancee.meshlink

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
            meshKey = none
            messageLifetimeSeconds = 1800
            """.trimIndent(),
            description,
        )
    }

    @Test
    fun aClassicalMeshTakesAThirtyTwoByteMeshKeyAndDescribeRedactsIt() {
        // Arrange
        val key = ByteArray(MESH_KEY) { it.toByte() }

        // Act
        val config =
            meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) {
                meshId = "Rescue"
                meshKey = key
            }.build()
        key.fill(0)

        // Assert
        assertContentEquals(ByteArray(MESH_KEY) { it.toByte() }, config.meshKey)
        assertEquals("meshKey = <redacted>", config.describe().lines()[3])
    }

    @Test
    fun aMeshWithoutAMeshKeyIsOpen() {
        // Act
        val config = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) { meshId = "Rescue" }.build()

        // Assert
        assertNull(config.meshKey)
        assertEquals("meshKey = none", config.describe().lines()[3])
    }

    @Test
    fun aMeshKeyThatIsNotThirtyTwoBytesIsRejected() {
        listOf(0, MESH_KEY - 1, MESH_KEY + 1).forEach { size ->
            val failure =
                assertFailsWith<MeshLinkException.InvalidConfig> {
                    meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) {
                        meshId = "Rescue"
                        meshKey = ByteArray(size)
                    }.build()
                }
            assertEquals("meshKey", failure.setting)
        }
    }

    @Test
    fun anUnsecuredMeshRejectsAMeshKey() {
        val failure =
            assertFailsWith<MeshLinkException.InvalidConfig> {
                meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) {
                    meshId = "Rescue"
                    meshKey = ByteArray(MESH_KEY)
                }.build()
            }

        assertEquals("meshKey", failure.setting)
    }

    private companion object {
        const val MESH_KEY = 32
    }
}
