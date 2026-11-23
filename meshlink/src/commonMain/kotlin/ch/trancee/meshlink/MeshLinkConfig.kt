@file:OptIn(ExperimentalObjCRefinement::class)

package ch.trancee.meshlink

import ch.trancee.meshlink.internal.Derivations
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

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
     * The secret of a gated mesh: exactly 32 bytes, or `null` (the default) for an open mesh. Only
     * nodes with the same key can link (Noise `psk3`) or recognize each other's advertisements. Not
     * allowed with [SecurityProfile.Unsecured]. [build] copies it.
     */
    public var meshKey: ByteArray? = null

    /**
     * Whether a message sent with [ReadReceiptRequest.Default] asks for a read receipt; `false` by
     * default. Internal until the feature toggles land.
     */
    @InternalMeshLinkApi
    @HiddenFromObjC
    public var readReceiptsRequestByDefault: Boolean = false

    /**
     * Whether this node sends a read receipt when the app marks a message read that asked for one;
     * `false` by default. Internal until the feature toggles land.
     */
    @InternalMeshLinkApi
    @HiddenFromObjC
    public var readReceiptsSend: Boolean = false

    /**
     * Validates the settings and returns the immutable config.
     *
     * @throws MeshLinkException.InvalidConfig if a setting is invalid.
     */
    @OptIn(InternalMeshLinkApi::class)
    @Throws(MeshLinkException::class)
    public fun build(): MeshLinkConfig {
        checkMeshId()
        return MeshLinkConfig(
            preset,
            securityProfile,
            meshId,
            checkedMeshKey(),
            Reading(readReceiptsRequestByDefault, readReceiptsSend),
        )
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
    /** The preset whose defaults this config uses. */
    public val preset: Preset,
    /** The security profile of the mesh. */
    public val securityProfile: SecurityProfile,
    /** The name of the mesh; see [MeshLinkConfigBuilder.meshId]. */
    public val meshId: String,
    private val key: ByteArray?,
    internal val reading: Reading,
) {
    /** A copy of the mesh key, or `null` for an open mesh; see [MeshLinkConfigBuilder.meshKey]. */
    public val meshKey: ByteArray? get() = key?.copyOf()

    /** How long a message this node sends stays deliverable, in seconds; from [preset]. */
    public val messageLifetimeSeconds: Int = preset.messageLifetimeSeconds

    /** See [MeshLinkConfigBuilder.readReceiptsRequestByDefault]. */
    @InternalMeshLinkApi
    @HiddenFromObjC
    public val readReceiptsRequestByDefault: Boolean get() = reading.requestByDefault

    /** See [MeshLinkConfigBuilder.readReceiptsSend]. */
    @InternalMeshLinkApi
    @HiddenFromObjC
    public val readReceiptsSend: Boolean get() = reading.send

    /** Every effective setting, one `name = value` line each. */
    public fun describe(): String =
        listOf(
            "preset = $preset",
            "securityProfile = $securityProfile",
            "meshId = \"$meshId\"",
            "meshKey = ${if (key == null) "none" else "<redacted>"}",
            "messageLifetimeSeconds = $messageLifetimeSeconds",
            "readReceiptsRequestByDefault = ${reading.requestByDefault}",
            "readReceiptsSend = ${reading.send}",
        ).joinToString("\n")
}

/** The read receipt settings of a [MeshLinkConfig]. */
internal class Reading(
    val requestByDefault: Boolean,
    val send: Boolean,
)
