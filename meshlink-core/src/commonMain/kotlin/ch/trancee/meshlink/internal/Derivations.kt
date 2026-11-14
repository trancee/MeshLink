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

    /** `NodeId = H("node-id", ed25519_public_key)`. */
    public fun nodeId(ed25519PublicKey: ByteArray): ByteArray = hash(Domain.NODE_ID, ed25519PublicKey)

    /**
     * `MeshIdDigest = H("mesh-id", u16(len(mesh_id)) ‖ mesh_id ‖ u8(profile))`.
     *
     * @param meshId the UTF-8 mesh identifier, 1 to 64 bytes.
     */
    public fun meshIdDigest(
        meshId: ByteArray,
        profile: SecurityProfile,
    ): ByteArray {
        require(meshId.size in MESH_ID_LENGTH) { "mesh identifier must be 1 to 64 bytes, not ${meshId.size}" }
        return hash(Domain.MESH_ID, u16(meshId.size) + meshId + profile.code.toByte())
    }

    /** `mesh_tag = trunc_4(H("mesh-tag", u32(epoch); key = tag_key))`. */
    public fun meshTag(
        tagKey: ByteArray,
        epoch: UInt,
    ): ByteArray = hash(Domain.MESH_TAG, u32(epoch), tagKey).copyOf(MESH_TAG_LENGTH)

    public companion object {
        private const val HASH_LENGTH = 32
        private const val MESH_TAG_LENGTH = 4
        private const val EPOCH_SECONDS = 300
        private val MESH_ID_LENGTH = 1..64
        private val UNIX_SECONDS = 0..UInt.MAX_VALUE.toLong()

        /**
         * The epoch number of [unixSeconds]: `floor(unix_seconds / 300)`.
         *
         * @param unixSeconds 0 to 2^32 − 1, the protocol's `u32` time range.
         */
        public fun epoch(unixSeconds: Long): UInt {
            require(unixSeconds in UNIX_SECONDS) { "unix seconds must fit a u32, not $unixSeconds" }
            return (unixSeconds / EPOCH_SECONDS).toUInt()
        }

        private const val BYTE_BITS = 8

        private fun u16(value: Int): ByteArray = byteArrayOf((value shr BYTE_BITS).toByte(), value.toByte())

        private fun u32(value: UInt): ByteArray =
            ByteArray(UInt.SIZE_BYTES) { (value shr (BYTE_BITS * (UInt.SIZE_BYTES - 1 - it))).toByte() }
    }
}

/** A registered domain string, `MeshLink/1 <purpose>` (spec "Domain-string registry"). */
public enum class Domain(
    public val purpose: String,
) {
    NODE_ID("node-id"),
    MESH_ID("mesh-id"),
    MESH_TAG("mesh-tag"),
    ;

    /** `DS(purpose) = u8(len(s)) ‖ s`, where `s = "MeshLink/1 " ‖ purpose`. */
    internal val encoded: ByteArray =
        "MeshLink/1 $purpose".encodeToByteArray().let { byteArrayOf(it.size.toByte()) + it }
}

/** A security profile and its one-byte wire code (spec "Security profiles"). */
public enum class SecurityProfile(
    public val code: Int,
) {
    UNSECURED(0x00),
    CLASSICAL(0x01),
    POST_QUANTUM(0x02),
}
