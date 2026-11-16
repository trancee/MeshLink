package ch.trancee.meshlink.internal

/**
 * One node's view of its mesh on the air (spec "Advertisement", "Mesh tag", "Resolvable tag"):
 * builds its advertisements and recognizes other members' ones. Not thread-safe; a node calls it
 * from its own dispatcher.
 *
 * `tag_key` is the MeshIdDigest of an open mesh, or `KDF(mesh_key, MeshIdDigest, "mesh-tag-key")`
 * of a gated one ([meshKey]). The resolvable tag is the resolving key's for the epoch when a gated
 * node passes one, and random otherwise.
 */
public class Discovery(
    private val crypto: CryptoProvider,
    meshId: ByteArray,
    profile: WireProfile,
    meshKey: ByteArray? = null,
) {
    private val derivations = Derivations(crypto)
    private val tagKey =
        derivations.meshIdDigest(meshId, profile).let { digest ->
            if (meshKey == null) digest else derivations.meshTagKey(meshKey, digest)
        }
    private var own = ByteArray(0)
    private var windowEpoch: UInt? = null
    private var window: List<ByteArray> = emptyList()

    /**
     * A fresh advertisement payload for [epoch]: the mesh tag of [epoch], then the resolvable tag of
     * [resolvingKey] for [epoch], or random bytes without one, and random fill drawn from the crypto
     * provider, so an observer can't link two epochs' payloads. Flags are zero until links can
     * upgrade. It becomes the payload [accept] treats as this node's.
     */
    public fun advertisement(
        epoch: UInt,
        resolvingKey: ByteArray? = null,
    ): ByteArray {
        val fill = crypto.randomBytes(Advertisement.RESOLVABLE_TAG_LENGTH + Advertisement.RANDOM_LENGTH)
        own =
            Advertisement(
                flags = 0,
                meshTag = derivations.meshTag(tagKey, epoch),
                resolvableTag =
                    if (resolvingKey == null) {
                        fill.copyOf(Advertisement.RESOLVABLE_TAG_LENGTH)
                    } else {
                        derivations.resolvableTag(resolvingKey, epoch)
                    },
                random = fill.copyOfRange(Advertisement.RESOLVABLE_TAG_LENGTH, fill.size),
            ).encode()
        return own.copyOf()
    }

    /**
     * The advertisement in [payload] if another member of this mesh sent it: it decodes, it is not
     * this node's own payload, and its mesh tag is this mesh's for epoch e − 1, e or e + 1, where e
     * is [epoch]. Otherwise `null`.
     */
    public fun accept(
        payload: ByteArray,
        epoch: UInt,
    ): Advertisement? {
        val advertisement = (Advertisement.decode(payload) as? Decoded.Valid)?.value
        val tags = window(epoch)
        return advertisement?.takeIf { candidate ->
            !payload.contentEquals(own) && tags.any { it.contentEquals(candidate.meshTag) }
        }
    }

    private fun window(epoch: UInt): List<ByteArray> {
        if (epoch != windowEpoch) {
            window = listOf(epoch - 1u, epoch, epoch + 1u).map { derivations.meshTag(tagKey, it) }
            windowEpoch = epoch
        }
        return window
    }
}
