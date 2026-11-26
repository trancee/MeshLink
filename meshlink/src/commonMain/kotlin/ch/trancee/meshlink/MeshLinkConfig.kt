package ch.trancee.meshlink

import ch.trancee.meshlink.internal.BroadcastFrame
import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.MessageContent

/**
 * Starts a [MeshLinkConfig] for [preset] and [securityProfile] (ADR 0007 decision 4). [configure]
 * sets the remaining settings; [MeshLinkConfigBuilder.build] validates them.
 *
 * ```kotlin
 * val config = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = "Festival" }.build()
 * ```
 */
public fun meshLinkConfig(
    preset: Preset,
    securityProfile: SecurityProfile,
    configure: MeshLinkConfigBuilder.() -> Unit,
): MeshLinkConfigBuilder = MeshLinkConfigBuilder(preset, securityProfile).apply(configure)

/**
 * The mutable settings of a [MeshLinkConfig]; see [meshLinkConfig]. It starts with every
 * [PresetSettings] value as the preset sets it; each one set here overrides the preset.
 */
public class MeshLinkConfigBuilder internal constructor(
    internal val preset: Preset,
    internal val securityProfile: SecurityProfile,
) : PresetSettings(preset.values) {
    /**
     * The name of the mesh: 1 to 64 bytes of UTF-8, compared byte for byte. Nodes join the same
     * mesh only if they use the same mesh ID and security profile.
     */
    public var meshId: String = ""

    /**
     * The secret of a gated mesh: exactly 32 bytes, or `null` (the default) for an open mesh. Only
     * nodes with the same key can link (Noise `psk3`) or recognize each other's advertisements. Not
     * allowed with [SecurityProfile.Unsecured]. [build] copies it.
     */
    public var meshKey: ByteArray? = null

    private val upgrade = Knob("l2capUpgrade", true)

    /**
     * Whether this node moves its links from GATT to an L2CAP connection-oriented channel where both
     * ends can (spec "Bearer switch"); `true` by default. As peripheral it listens and offers its PSM;
     * as central it opens a channel to a peer that offers one. Off, every link it is part of stays on
     * GATT, which is slower but works with every peer.
     */
    public var l2capUpgrade: Boolean by upgrade

    /**
     * Validates the settings and returns the immutable config.
     *
     * @throws MeshLinkException.InvalidConfig if a setting is invalid.
     */
    @Throws(MeshLinkException::class)
    public fun build(): MeshLinkConfig {
        checkMeshId()
        checkPresetValues()
        return MeshLinkConfig(this, checkedMeshKey())
    }

    // The lines of MeshLinkConfig.describe(), each with its source.
    internal fun describe(): String {
        val fixed =
            listOf(
                "preset = $preset (override)",
                "securityProfile = $securityProfile (override)",
                "meshId = \"$meshId\" (override)",
                if (meshKey == null) "meshKey = none (default)" else "meshKey = <redacted> (override)",
                "l2capUpgrade = ${upgrade.value} (${if (upgrade.overridden) "override" else "default"})",
            )
        val presetLines = knobs.all.map { "${it.key} = ${it.value} (${if (it.overridden) "override" else "preset"})" }
        return (fixed + presetLines).joinToString("\n")
    }

    private fun checkMeshId() {
        val bytes =
            try {
                meshId.encodeToByteArray(throwOnInvalidSequence = true)
            } catch (e: CharacterCodingException) {
                throw MeshLinkException.InvalidConfig("meshId", "meshId is not valid UTF-8", e)
            }
        if (bytes.size !in Derivations.MESH_ID_MIN_BYTES..Derivations.MESH_ID_MAX_BYTES) {
            throw MeshLinkException.InvalidConfig(
                "meshId",
                "meshId must be ${Derivations.MESH_ID_MIN_BYTES} to ${Derivations.MESH_ID_MAX_BYTES} UTF-8 bytes, " +
                    "not ${bytes.size}",
            )
        }
    }

    /** A copy of [meshKey], which the caller may still change. */
    private fun checkedMeshKey(): ByteArray? {
        val key = meshKey?.copyOf()
        if (key != null && securityProfile == SecurityProfile.Unsecured) {
            throw MeshLinkException.InvalidConfig("meshKey", "meshKey needs a secured profile, not Unsecured")
        }
        if (key != null && key.size != MESH_KEY_BYTES) {
            throw MeshLinkException.InvalidConfig("meshKey", "meshKey must be $MESH_KEY_BYTES bytes, not ${key.size}")
        }
        return key
    }

    private companion object {
        const val MESH_KEY_BYTES = 32
    }
}

/** The immutable settings of a [MeshLink] node, built with [meshLinkConfig]. */
public class MeshLinkConfig internal constructor(
    builder: MeshLinkConfigBuilder,
    private val key: ByteArray?,
) {
    private val values: PresetValues = builder.knobs.values()
    private val description: String = builder.describe()

    /** The preset whose values this config starts from. */
    public val preset: Preset = builder.preset

    /** The security profile of the mesh. */
    public val securityProfile: SecurityProfile = builder.securityProfile

    /** The name of the mesh; see [MeshLinkConfigBuilder.meshId]. */
    public val meshId: String = builder.meshId

    /** See [MeshLinkConfigBuilder.l2capUpgrade]. */
    public val l2capUpgrade: Boolean = builder.l2capUpgrade

    /** A copy of the mesh key, or `null` for an open mesh; see [MeshLinkConfigBuilder.meshKey]. */
    public val meshKey: ByteArray? get() = key?.copyOf()

    /** See [PresetSettings.density]. */
    public val density: Density = values.density

    /** See [PresetSettings.power]. */
    public val power: Power = values.power

    /** See [PresetSettings.routedUnicast]. */
    public val routedUnicast: Boolean = values.routedUnicast

    /** See [PresetSettings.broadcastOrigination]. */
    public val broadcastOrigination: Boolean = values.broadcastOrigination

    /** See [PresetSettings.broadcastPriority]. */
    public val broadcastPriority: Boolean = values.broadcastPriority

    /** See [PresetSettings.holding]. */
    public val holding: Boolean = values.holding

    /** See [PresetSettings.messageLifetimeSeconds]. */
    public val messageLifetimeSeconds: Int = values.messageLifetimeSeconds

    /** The effective read receipt settings; see [ReadReceiptsBuilder]. */
    public val readReceipts: ReadReceipts = ReadReceipts(values.readReceiptsRequestByDefault, values.readReceiptsSend)

    /** The effective payload caps; see [LimitsBuilder]. */
    public val limits: Limits = Limits(values.maxUnicastPayload, values.maxBroadcastPayload)

    /**
     * Every effective setting, one `name = value (source)` line each. The source is `preset` for a
     * value the preset set, `override` for one the app set, and `default` for one with a fixed default.
     * A mesh key prints as `<redacted>`.
     */
    public fun describe(): String = description
}

/** The effective read receipt settings of a [MeshLinkConfig]; see [ReadReceiptsBuilder]. */
public class ReadReceipts internal constructor(
    /** See [ReadReceiptsBuilder.requestByDefault]. */
    public val requestByDefault: Boolean,
    /** See [ReadReceiptsBuilder.send]. */
    public val send: Boolean,
)

/**
 * The payload caps of a [MeshLinkConfig], set through [MeshLinkConfigBuilder.limits] (ADR 0015
 * decision 8). They may only lower the protocol caps: a send above them returns
 * [SendResult.Rejected] with [RejectionReason.TooLarge].
 *
 * ```kotlin
 * meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) {
 *     meshId = "Festival"
 *     limits.maxUnicastPayload = 4_096
 * }
 * ```
 *
 * MeshLink never compresses payloads. If an app compresses secret data together with data an
 * attacker can influence, the ciphertext length leaks the secret (CRIME/BREACH-style); compress
 * only data that is all public or all secret.
 */
public class LimitsBuilder internal constructor(
    knobs: Knobs,
) {
    /**
     * The largest payload of a message to one peer: 4,096 to 131,072 bytes. A payload above 4,096
     * bytes is a large message, which travels only on a direct link or a discovered route and is never
     * held for an absent destination.
     */
    public var maxUnicastPayload: Int by knobs.maxUnicastPayload

    /** The largest payload of a mesh broadcast: 1 to 1,024 bytes. */
    public var maxBroadcastPayload: Int by knobs.maxBroadcastPayload

    internal fun check() {
        if (maxUnicastPayload !in MessageContent.PAYLOAD_SMALL_MAX..MessageContent.PAYLOAD_MAX) {
            throw MeshLinkException.InvalidConfig(
                "limits.maxUnicastPayload",
                "limits.maxUnicastPayload must be ${MessageContent.PAYLOAD_SMALL_MAX} to " +
                    "${MessageContent.PAYLOAD_MAX} bytes, not $maxUnicastPayload",
            )
        }
        if (maxBroadcastPayload !in 1..BroadcastFrame.PAYLOAD_MAX) {
            throw MeshLinkException.InvalidConfig(
                "limits.maxBroadcastPayload",
                "limits.maxBroadcastPayload must be 1 to ${BroadcastFrame.PAYLOAD_MAX} bytes, not $maxBroadcastPayload",
            )
        }
    }
}

/** The effective payload caps of a [MeshLinkConfig]; see [LimitsBuilder]. */
public class Limits internal constructor(
    /** The largest payload of a message to one peer, in bytes; see [LimitsBuilder.maxUnicastPayload]. */
    public val maxUnicastPayload: Int,
    /** The largest payload of a mesh broadcast, in bytes; see [LimitsBuilder.maxBroadcastPayload]. */
    public val maxBroadcastPayload: Int,
)
