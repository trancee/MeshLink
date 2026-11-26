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
    fun offlineChatSetsItsPresetValues() {
        assertPresetValues(
            Preset.OfflineChat,
            "density = Automatic\npower = Balanced\nroutedUnicast = true\nbroadcastOrigination = true\n" +
                "broadcastPriority = false\nholding = true\nmessageLifetimeSeconds = 1800\n" +
                "readReceipts.requestByDefault = true\nreadReceipts.send = true\n" +
                "limits.maxUnicastPayload = 131072\nlimits.maxBroadcastPayload = 1024",
        )
    }

    @Test
    fun emergencyBroadcastSetsItsPresetValues() {
        assertPresetValues(
            Preset.EmergencyBroadcast,
            "density = Automatic\npower = Balanced\nroutedUnicast = true\nbroadcastOrigination = true\n" +
                "broadcastPriority = true\nholding = true\nmessageLifetimeSeconds = 3600\n" +
                "readReceipts.requestByDefault = false\nreadReceipts.send = false\n" +
                "limits.maxUnicastPayload = 4096\nlimits.maxBroadcastPayload = 1024",
        )
    }

    @Test
    fun proximityDiscoverySetsItsPresetValues() {
        assertPresetValues(
            Preset.ProximityDiscovery,
            "density = Automatic\npower = Responsive\nroutedUnicast = false\nbroadcastOrigination = true\n" +
                "broadcastPriority = false\nholding = false\nmessageLifetimeSeconds = 120\n" +
                "readReceipts.requestByDefault = false\nreadReceipts.send = false\n" +
                "limits.maxUnicastPayload = 4096\nlimits.maxBroadcastPayload = 1024",
        )
    }

    @Test
    fun localMultiplayerSetsItsPresetValues() {
        assertPresetValues(
            Preset.LocalMultiplayer,
            "density = Pinned(Low)\npower = Responsive\nroutedUnicast = true\nbroadcastOrigination = true\n" +
                "broadcastPriority = false\nholding = false\nmessageLifetimeSeconds = 120\n" +
                "readReceipts.requestByDefault = false\nreadReceipts.send = false\n" +
                "limits.maxUnicastPayload = 131072\nlimits.maxBroadcastPayload = 1024",
        )
    }

    @Test
    fun fieldTeamsSetsItsPresetValues() {
        assertPresetValues(
            Preset.FieldTeams,
            "density = Automatic\npower = Balanced\nroutedUnicast = true\nbroadcastOrigination = true\n" +
                "broadcastPriority = false\nholding = true\nmessageLifetimeSeconds = 3600\n" +
                "readReceipts.requestByDefault = true\nreadReceipts.send = true\n" +
                "limits.maxUnicastPayload = 131072\nlimits.maxBroadcastPayload = 1024",
        )
    }

    @Test
    fun customInheritsItsBaseAndAppliesItsOverrides() {
        // Arrange
        val custom =
            Preset.Custom("Quiet chat", Preset.OfflineChat) {
                holding = false
                density = Density.Pinned(DensityClass.Medium)
                readReceipts.send = false
                limits.maxUnicastPayload = 8_192
            }

        // Act
        val config = meshLinkConfig(custom, SecurityProfile.Unsecured) { meshId = "m" }.build()

        // Assert
        assertEquals(
            listOf(false, Density.Pinned(DensityClass.Medium), false, 8_192),
            listOf(config.holding, config.density, config.readReceipts.send, config.limits.maxUnicastPayload),
        )
        assertEquals(
            listOf(1_800, true, Power.Balanced, true),
            listOf(
                config.messageLifetimeSeconds,
                config.readReceipts.requestByDefault,
                config.power,
                config.routedUnicast,
            ),
        )
        assertEquals("preset = Custom(\"Quiet chat\", base = OfflineChat) (override)", config.describe().lines()[0])
    }

    @Test
    fun theBuilderStartsFromThePresetAndKeepsEveryToggleSet() {
        // Arrange
        val builder = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = "m" }
        val before = listOf(builder.holding, builder.l2capUpgrade, builder.messageLifetimeSeconds)

        // Act
        val config =
            builder
                .apply {
                    density = Density.Pinned(DensityClass.High)
                    power = Power.Responsive
                    routedUnicast = false
                    broadcastOrigination = false
                    broadcastPriority = true
                    holding = false
                    l2capUpgrade = false
                    messageLifetimeSeconds = 600
                    readReceipts.requestByDefault = false
                    readReceipts.send = false
                }.build()

        // Assert
        assertEquals(listOf(true, true, 1_800), before)
        assertEquals(
            listOf(
                Density.Pinned(DensityClass.High),
                Power.Responsive,
                false,
                false,
                true,
                false,
                false,
                600,
                false,
                false,
            ),
            listOf(
                config.density,
                config.power,
                config.routedUnicast,
                config.broadcastOrigination,
                config.broadcastPriority,
                config.holding,
                config.l2capUpgrade,
                config.messageLifetimeSeconds,
                config.readReceipts.requestByDefault,
                config.readReceipts.send,
            ),
        )
    }

    @Test
    fun debugDiagnosticsAreOffUnlessTheAppTurnsThemOn() {
        // Arrange
        val builder = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = "m" }
        val before = builder.build()

        // Act
        val after = builder.apply { diagnostics.debug = true }.build()

        // Assert
        assertEquals(false to true, before.diagnostics.debug to after.diagnostics.debug)
        assertEquals("diagnostics.debug = true (override)", after.describe().lines()[5])
    }

    @Test
    fun aMessageLifetimeOfTwoMinutesToOneHourIsAccepted() {
        // ADR 0014 decision 2: 120 s to 1 h.
        val accepted = listOf(120, 3_600).map { configWithLifetime(it).messageLifetimeSeconds }

        assertEquals(listOf(120, 3_600), accepted)
    }

    @Test
    fun aMessageLifetimeOutsideTwoMinutesToOneHourIsRejected() {
        val settings =
            listOf(119, 3_601).map {
                assertFailsWith<MeshLinkException.InvalidConfig> { configWithLifetime(it) }.setting
            }
        val custom =
            assertFailsWith<MeshLinkException.InvalidConfig> {
                meshLinkConfig(Preset.Custom("Short", Preset.FieldTeams) { messageLifetimeSeconds = 60 }, UNSECURED) {
                    meshId = "m"
                }.build()
            }

        assertEquals(List(2) { "messageLifetimeSeconds" }, settings)
        assertEquals("messageLifetimeSeconds", custom.setting)
    }

    @Test
    fun describeListsEveryEffectiveSettingWithItsSource() {
        // Arrange
        val config =
            meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) {
                meshId = "Festival"
                holding = false
                limits.maxBroadcastPayload = 512
            }.build()

        // Act
        val description = config.describe()

        // Assert
        assertEquals(
            """
            preset = OfflineChat (override)
            securityProfile = Unsecured (override)
            meshId = "Festival" (override)
            meshKey = none (default)
            l2capUpgrade = true (default)
            diagnostics.debug = false (default)
            density = Automatic (preset)
            power = Balanced (preset)
            routedUnicast = true (preset)
            broadcastOrigination = true (preset)
            broadcastPriority = false (preset)
            holding = false (override)
            messageLifetimeSeconds = 1800 (preset)
            readReceipts.requestByDefault = true (preset)
            readReceipts.send = true (preset)
            limits.maxUnicastPayload = 131072 (preset)
            limits.maxBroadcastPayload = 512 (override)
            """.trimIndent(),
            description,
        )
    }

    @Test
    fun limitsDefaultToTheProtocolCapsAndKeepEveryValueWithinThem() {
        // ADR 0015 decision 8: unicast 4 KiB to 128 KiB, broadcast at most 1 KiB.
        val defaults = configWith("Festival").limits
        val edges =
            listOf(4_096 to 1, 131_072 to 1_024).map { (unicast, broadcast) ->
                meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) {
                    meshId = "Festival"
                    limits.maxUnicastPayload = unicast
                    limits.maxBroadcastPayload = broadcast
                }.build().limits
            }

        assertEquals(listOf(131_072, 1_024), listOf(defaults.maxUnicastPayload, defaults.maxBroadcastPayload))
        assertEquals(listOf(4_096 to 1, 131_072 to 1_024), edges.map { it.maxUnicastPayload to it.maxBroadcastPayload })
    }

    @Test
    fun limitsOutsideTheProtocolCapsAreRejected() {
        val unicast =
            listOf(4_095, 131_073).map { value ->
                assertFailsWith<MeshLinkException.InvalidConfig> {
                    meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) {
                        meshId = "Festival"
                        limits.maxUnicastPayload = value
                    }.build()
                }.setting
            }
        val broadcast =
            listOf(0, 1_025).map { value ->
                assertFailsWith<MeshLinkException.InvalidConfig> {
                    meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) {
                        meshId = "Festival"
                        limits.maxBroadcastPayload = value
                    }.build()
                }.setting
            }

        assertEquals(List(2) { "limits.maxUnicastPayload" }, unicast)
        assertEquals(List(2) { "limits.maxBroadcastPayload" }, broadcast)
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
        assertEquals("meshKey = <redacted> (override)", config.describe().lines()[3])
    }

    @Test
    fun aMeshWithoutAMeshKeyIsOpen() {
        // Act
        val config = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) { meshId = "Rescue" }.build()

        // Assert
        assertNull(config.meshKey)
        assertEquals("meshKey = none (default)", config.describe().lines()[3])
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

    private fun configWithLifetime(seconds: Int): MeshLinkConfig =
        meshLinkConfig(Preset.OfflineChat, UNSECURED) {
            meshId = "m"
            messageLifetimeSeconds = seconds
        }.build()

    // Asserts the preset's lines of describe(): every value it sets, each with source preset.
    private fun assertPresetValues(
        preset: Preset,
        expected: String,
    ) {
        val config = meshLinkConfig(preset, UNSECURED) { meshId = "m" }.build()

        val described = config.describe().lines().filter { it.endsWith(" (preset)") }

        assertEquals(expected, described.joinToString("\n") { it.removeSuffix(" (preset)") })
    }

    private companion object {
        const val MESH_KEY = 32
        val UNSECURED = SecurityProfile.Unsecured
    }
}
