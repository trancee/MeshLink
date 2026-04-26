package ch.trancee.meshlink.crypto

/**
 * Holds a public/private key pair as raw byte arrays.
 *
 * Used for both Ed25519 signing keys and X25519 Diffie-Hellman keys.
 *
 * [equals] and [hashCode] use content-equality so two [KeyPair] instances with identical byte
 * contents compare as equal, regardless of array identity.
 */
internal data class KeyPair(val publicKey: ByteArray, val privateKey: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyPair) return false
        return publicKey.contentEquals(other.publicKey) &&
            privateKey.contentEquals(other.privateKey)
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + privateKey.contentHashCode()
        return result
    }
}
