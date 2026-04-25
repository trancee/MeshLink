package ch.trancee.meshlink.crypto

/**
 * Carries the cryptographic proof that a node is rotating its long-term keypairs.
 *
 * The announcement is signed by the **old** Ed25519 private key so that existing peers can verify
 * authenticity before trusting the new keys.
 *
 * Wire serialisation (FlatBuffers) is deferred to M002 — this class is the in-memory struct with
 * sign/verify logic only.
 *
 * Signing payload: `newEdPublicKey ‖ newDhPublicKey ‖ rotationNonce_LE8`
 */
internal class RotationAnnouncement(
    val newEdPublicKey: ByteArray,
    val newDhPublicKey: ByteArray,
    val rotationNonce: ULong,
    val signature: ByteArray,
) {
    companion object {
        /**
         * Creates and signs a [RotationAnnouncement]. The payload signed is `newEdPublicKey ‖
         * newDhPublicKey ‖ nonce_LE8`, using [oldEdKeyPair]'s private key.
         */
        fun create(
            crypto: CryptoProvider,
            oldEdKeyPair: KeyPair,
            newEdPublicKey: ByteArray,
            newDhPublicKey: ByteArray,
            nonce: ULong,
        ): RotationAnnouncement {
            val payload = newEdPublicKey + newDhPublicKey + nonceToBytes(nonce)
            val signature = crypto.sign(oldEdKeyPair.privateKey, payload)
            return RotationAnnouncement(newEdPublicKey, newDhPublicKey, nonce, signature)
        }

        /**
         * Verifies the [announcement]'s signature against [oldEdPublicKey]. Returns `true` if the
         * signature is valid, `false` if the announcement has been tampered with or the wrong
         * public key is supplied.
         */
        fun verify(
            crypto: CryptoProvider,
            oldEdPublicKey: ByteArray,
            announcement: RotationAnnouncement,
        ): Boolean {
            val payload =
                announcement.newEdPublicKey +
                    announcement.newDhPublicKey +
                    nonceToBytes(announcement.rotationNonce)
            return crypto.verify(oldEdPublicKey, payload, announcement.signature)
        }

        /** Encodes [nonce] as 8 bytes, little-endian (LSB first). */
        private fun nonceToBytes(nonce: ULong): ByteArray {
            val bytes = ByteArray(8)
            var v = nonce
            for (i in 0 until 8) {
                bytes[i] = (v and 0xFFUL).toByte()
                v = v shr 8
            }
            return bytes
        }
    }
}
