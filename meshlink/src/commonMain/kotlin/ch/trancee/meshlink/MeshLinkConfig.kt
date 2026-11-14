package ch.trancee.meshlink

import ch.trancee.meshlink.internal.Derivations

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

/** The mutable settings of a [MeshLinkConfig]; see [meshLinkConfig]. */
public class MeshLinkConfigBuilder internal constructor(
    private val preset: Preset,
    private val securityProfile: SecurityProfile,
) {
    /**
     * The name of the mesh: 1 to 64 bytes of UTF-8, compared byte for byte. Nodes join the same
     * mesh only if they use the same mesh ID and security profile.
     */
    public var meshId: String = ""

    /**
     * Validates the settings and returns the immutable config.
     *
     * @throws MeshLinkException.InvalidConfig if a setting is invalid.
     */
    @Throws(MeshLinkException::class)
    public fun build(): MeshLinkConfig {
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
        return MeshLinkConfig(preset, securityProfile, meshId)
    }
}

/** The immutable settings of a [MeshLink] node, built with [meshLinkConfig]. */
public class MeshLinkConfig internal constructor(
    /** The preset whose defaults this config uses. */
    public val preset: Preset,
    /** The security profile of the mesh. */
    public val securityProfile: SecurityProfile,
    /** The name of the mesh; see [MeshLinkConfigBuilder.meshId]. */
    public val meshId: String,
) {
    /** How long a message this node sends stays deliverable, in seconds; from [preset]. */
    public val messageLifetimeSeconds: Int = preset.messageLifetimeSeconds

    /** Every effective setting, one `name = value` line each. */
    public fun describe(): String =
        listOf(
            "preset = $preset",
            "securityProfile = $securityProfile",
            "meshId = \"$meshId\"",
            "messageLifetimeSeconds = $messageLifetimeSeconds",
        ).joinToString("\n")
}
