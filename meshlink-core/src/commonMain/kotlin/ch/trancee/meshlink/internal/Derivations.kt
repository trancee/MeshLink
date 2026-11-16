package ch.trancee.meshlink.internal

/**
 * The hash derivations of the protocol spec ("Derivation rules", "Epochs", NodeId, mesh identifier
 * and mesh tag), computed with [crypto].
 */
public class Derivations(
    private val crypto: CryptoProvider,
) {
    /**
     * `H(purpose, input; key)`: 32-byte BLAKE2b over `DS(purpose) ‖ input`, keyed if [key] is not
     * `null`. Shorter identifiers truncate this output; they never request a shorter BLAKE2b output.
     */
    public fun hash(
        domain: Domain,
        input: ByteArray,
        key: ByteArray? = null,
    ): ByteArray = crypto.blake2b(HASH_LENGTH, domain.encoded + input, key)

    /**
     * `KDF(ikm, salt, purpose, context, L)`: HKDF-SHA-256 with `info = DS(purpose) ‖ context`; an
     * empty [salt] means 32 zero bytes.
     */
    public fun kdf(
        ikm: ByteArray,
        salt: ByteArray,
        domain: Domain,
        context: ByteArray = ByteArray(0),
        length: Int = HASH_LENGTH,
    ): ByteArray = crypto.hkdfSha256Expand(crypto.hkdfSha256Extract(salt, ikm), domain.encoded + context, length)

    /** A gated mesh's tag key: `KDF(mesh_key, MeshIdDigest, "mesh-tag-key", "", 32)` (D-05). */
    public fun meshTagKey(
        meshKey: ByteArray,
        meshIdDigest: ByteArray,
    ): ByteArray = kdf(meshKey, meshIdDigest, Domain.MESH_TAG_KEY)

    /** A gated mesh's `XXpsk3` psk: `KDF(mesh_key, MeshIdDigest, "mesh-psk", "", 32)` (D-06). */
    public fun meshPsk(
        meshKey: ByteArray,
        meshIdDigest: ByteArray,
    ): ByteArray = kdf(meshKey, meshIdDigest, Domain.MESH_PSK)

    /** `NodeId = H("node-id", ed25519_public_key)`. */
    public fun nodeId(ed25519PublicKey: ByteArray): ByteArray = hash(Domain.NODE_ID, ed25519PublicKey)

    /**
     * `MeshIdDigest = H("mesh-id", u16(len(mesh_id)) ‖ mesh_id ‖ u8(profile))`.
     *
     * @param meshId the UTF-8 mesh identifier, 1 to 64 bytes.
     */
    public fun meshIdDigest(
        meshId: ByteArray,
        profile: WireProfile,
    ): ByteArray {
        require(meshId.size in MESH_ID_LENGTH) { "mesh identifier must be 1 to 64 bytes, not ${meshId.size}" }
        return hash(Domain.MESH_ID, u16(meshId.size) + meshId + profile.code.toByte())
    }

    /** `mesh_tag = trunc_4(H("mesh-tag", u32(epoch); key = tag_key))`. */
    public fun meshTag(
        tagKey: ByteArray,
        epoch: UInt,
    ): ByteArray = hash(Domain.MESH_TAG, u32(epoch), tagKey).copyOf(MESH_TAG_LENGTH)

    /** The `unsecured` routing tag of [nodeId]: `trunc_8(H("unsecured-tag", NodeId ‖ u32(epoch)))`. */
    public fun unsecuredTag(
        nodeId: ByteArray,
        epoch: UInt,
    ): ByteArray = hash(Domain.UNSECURED_TAG, nodeId + u32(epoch)).copyOf(ROUTING_TAG_LENGTH)

    /** `commitment = trunc_16(H("tombstone", k))` for a message's tombstone secret [k]. */
    public fun tombstoneCommitment(k: ByteArray): ByteArray = hash(Domain.TOMBSTONE, k).copyOf(COMMITMENT_LENGTH)

    public companion object {
        private const val HASH_LENGTH = 32
        private const val MESH_TAG_LENGTH = 4
        private const val ROUTING_TAG_LENGTH = 8
        private const val COMMITMENT_LENGTH = 16
        private const val EPOCH_SECONDS = 300

        /** The shortest mesh identifier, in UTF-8 bytes (spec "Mesh identifier"). */
        public const val MESH_ID_MIN_BYTES: Int = 1

        /** The longest mesh identifier, in UTF-8 bytes (spec "Mesh identifier"). */
        public const val MESH_ID_MAX_BYTES: Int = 64
        private val MESH_ID_LENGTH = MESH_ID_MIN_BYTES..MESH_ID_MAX_BYTES
        private val UNIX_SECONDS = 0..UInt.MAX_VALUE.toLong()

        /**
         * The link handshake prologue (D-15):
         * `DS("link-prologue") ‖ u8(n) ‖ versions ‖ u8(selected) ‖ u8(profile) ‖ MeshIdDigest`, with
         * [versions] as the peripheral's capability record lists them.
         */
        public fun linkPrologue(
            versions: List<Int>,
            selected: Int,
            profile: WireProfile,
            meshIdDigest: ByteArray,
        ): ByteArray =
            Domain.LINK_PROLOGUE.encoded +
                byteArrayOf(versions.size.toByte()) +
                ByteArray(versions.size) { versions[it].toByte() } +
                byteArrayOf(selected.toByte(), profile.code.toByte()) +
                meshIdDigest

        /**
         * The epoch number of [unixSeconds]: `floor(unix_seconds / 300)`.
         *
         * @param unixSeconds 0 to 2^32 − 1, the protocol's `u32` time range.
         */
        public fun epoch(unixSeconds: Long): UInt {
            require(unixSeconds in UNIX_SECONDS) { "unix seconds must fit a u32, not $unixSeconds" }
            return (unixSeconds / EPOCH_SECONDS).toUInt()
        }
    }
}

/** A registered domain string, `MeshLink/1 <purpose>` (spec "Domain-string registry"). */
public enum class Domain(
    public val purpose: String,
) {
    NODE_ID("node-id"),
    MESH_ID("mesh-id"),
    MESH_TAG("mesh-tag"),
    MESH_TAG_KEY("mesh-tag-key"),
    MESH_PSK("mesh-psk"),
    LINK_PROLOGUE("link-prologue"),
    UNSECURED_TAG("unsecured-tag"),
    TOMBSTONE("tombstone"),
    CERTIFICATE("certificate"),
    ;

    /** `DS(purpose) = u8(len(s)) ‖ s`, where `s = "MeshLink/1 " ‖ purpose`. */
    internal val encoded: ByteArray =
        "MeshLink/1 $purpose".encodeToByteArray().let { byteArrayOf(it.size.toByte()) + it }
}

/** A security profile's one-byte wire code (spec "Security profiles"). */
public enum class WireProfile(
    public val code: Int,
) {
    UNSECURED(0x00),
    CLASSICAL(0x01),
    POST_QUANTUM(0x02),
}
