package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.storage.SecureStorage

/**
 * Represents a node's long-term cryptographic identity: an independent Ed25519 signing keypair, an
 * independent X25519 key-agreement keypair, and a 12-byte Key Hash derived from both public keys.
 *
 * Key Hash = SHA-256(Ed25519Pub ‖ X25519Pub)[0:12] — 96 bits, used as a stable identifier for the
 * node. Key Hashes MUST be compared with [constantTimeEquals] to prevent timing side-channels.
 *
 * Construction is via [loadOrGenerate]: if all four key bytes are present in [SecureStorage] they
 * are loaded; otherwise fresh keypairs are generated and persisted.
 */
internal class Identity private constructor(
    val edKeyPair: KeyPair,
    val dhKeyPair: KeyPair,
    val keyHash: ByteArray,
) {
    // ── Equality ─────────────────────────────────────────────────────────────

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Identity) return false
        return constantTimeEquals(keyHash, other.keyHash)
    }

    override fun hashCode(): Int = keyHash.contentHashCode()

    // ── Key rotation ─────────────────────────────────────────────────────────

    /**
     * Generates fresh keypairs, increments the rotation nonce, signs a
     * [RotationAnnouncement] with the OLD Ed25519 private key, and persists the new keys + nonce
     * to [storage]. Returns the signed announcement so it can be broadcast to peers.
     *
     * The signing payload is `newEdPub ‖ newDhPub ‖ nonce_LE8`.
     */
    fun rotateKeys(crypto: CryptoProvider, storage: SecureStorage): RotationAnnouncement {
        val newEdKeyPair = crypto.generateEd25519KeyPair()
        val newDhKeyPair = crypto.generateX25519KeyPair()

        val storedNonce = storage.get(KEY_NONCE)
        val newNonce: ULong = if (storedNonce != null) {
            decodeULong(storedNonce) + 1UL
        } else {
            1UL
        }

        val announcement = RotationAnnouncement.create(
            crypto = crypto,
            oldEdKeyPair = edKeyPair,
            newEdPublicKey = newEdKeyPair.publicKey,
            newDhPublicKey = newDhKeyPair.publicKey,
            nonce = newNonce,
        )

        storage.put(KEY_ED_PRIV, newEdKeyPair.privateKey)
        storage.put(KEY_ED_PUB, newEdKeyPair.publicKey)
        storage.put(KEY_DH_PRIV, newDhKeyPair.privateKey)
        storage.put(KEY_DH_PUB, newDhKeyPair.publicKey)
        storage.put(KEY_NONCE, encodeULong(newNonce))

        return announcement
    }

    companion object {
        private const val KEY_ED_PRIV = "meshlink.identity.ed25519.private"
        private const val KEY_ED_PUB = "meshlink.identity.ed25519.public"
        private const val KEY_DH_PRIV = "meshlink.identity.x25519.private"
        private const val KEY_DH_PUB = "meshlink.identity.x25519.public"
        private const val KEY_NONCE = "meshlink.identity.rotation_nonce"

        /**
         * Loads the identity from [storage] if all four key bytes are present (ed25519 private +
         * public, x25519 private + public). If any key is absent, generates fresh keypairs and
         * persists them. Computes the Key Hash on every construction path.
         */
        fun loadOrGenerate(crypto: CryptoProvider, storage: SecureStorage): Identity {
            val edPriv = storage.get(KEY_ED_PRIV)
            val edPub = storage.get(KEY_ED_PUB)
            val dhPriv = storage.get(KEY_DH_PRIV)
            val dhPub = storage.get(KEY_DH_PUB)
            if (edPriv != null && edPub != null && dhPriv != null && dhPub != null) {
                val keyHash = crypto.sha256(edPub + dhPub).copyOf(12)
                return Identity(KeyPair(edPub, edPriv), KeyPair(dhPub, dhPriv), keyHash)
            }
            return generateAndStore(crypto, storage)
        }

        private fun generateAndStore(crypto: CryptoProvider, storage: SecureStorage): Identity {
            val edKeyPair = crypto.generateEd25519KeyPair()
            val dhKeyPair = crypto.generateX25519KeyPair()
            storage.put(KEY_ED_PRIV, edKeyPair.privateKey)
            storage.put(KEY_ED_PUB, edKeyPair.publicKey)
            storage.put(KEY_DH_PRIV, dhKeyPair.privateKey)
            storage.put(KEY_DH_PUB, dhKeyPair.publicKey)
            val keyHash = crypto.sha256(edKeyPair.publicKey + dhKeyPair.publicKey).copyOf(12)
            return Identity(edKeyPair, dhKeyPair, keyHash)
        }

        // ── Nonce encoding (little-endian 8-byte ULong) ──────────────────────

        /**
         * Encodes [value] as an 8-byte little-endian byte array (LSB first).
         * Internal so tests can verify round-trip correctness.
         */
        internal fun encodeULong(value: ULong): ByteArray {
            val bytes = ByteArray(8)
            var v = value
            for (i in 0 until 8) {
                bytes[i] = (v and 0xFFUL).toByte()
                v = v shr 8
            }
            return bytes
        }

        /**
         * Decodes an 8-byte little-endian byte array into a [ULong] (LSB first).
         * Internal so tests can verify round-trip correctness.
         */
        internal fun decodeULong(bytes: ByteArray): ULong {
            var result = 0UL
            for (i in 7 downTo 0) {
                result = (result shl 8) or (bytes[i].toULong() and 0xFFUL)
            }
            return result
        }
    }
}
