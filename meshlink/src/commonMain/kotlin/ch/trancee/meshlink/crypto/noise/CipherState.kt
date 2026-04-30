package ch.trancee.meshlink.crypto.noise

import ch.trancee.meshlink.crypto.CryptoProvider

/**
 * Noise Protocol Framework §5.1 CipherState.
 *
 * Holds a symmetric key `k` (32 bytes or empty) and a monotonic nonce counter `n`. Delegates all
 * AEAD operations to [CryptoProvider]. When [k] is unset, plaintext passes through unchanged per
 * the Noise spec §5.1: "If k is non-empty returns ENCRYPT(k, n++, ad, plaintext). Otherwise returns
 * plaintext."
 */
internal class CipherState(private val crypto: CryptoProvider) {

    private var k: ByteArray = EMPTY_BYTE_ARRAY // empty = no key
    private var n: Long = 0L

    /**
     * Reusable 12-byte nonce buffer. Bytes 0–3 are always zero (IETF ChaCha20-Poly1305 format).
     * Only bytes 4–11 are written by [writeNonce]. Safe because CipherState is single-threaded (one
     * per Noise session direction).
     */
    private val nonceBuffer = ByteArray(12)

    /** Returns true if this CipherState holds an initialized key. */
    fun hasKey(): Boolean = k.isNotEmpty()

    /**
     * Sets the cipher key and resets the nonce counter to zero.
     *
     * @param key Must be exactly 32 bytes.
     */
    fun initializeKey(key: ByteArray) {
        if (key.size != 32) {
            throw IllegalArgumentException("CipherState key must be 32 bytes, got ${key.size}")
        }
        k = key.copyOf()
        n = 0L
    }

    /**
     * Encrypts [plaintext] with [ad] as associated data and increments the nonce counter.
     *
     * If no key is set, returns [plaintext] unchanged (Noise spec §5.1 pass-through).
     */
    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        if (!hasKey()) return plaintext
        writeNonce(n)
        n++
        return crypto.aeadEncrypt(k, nonceBuffer, plaintext, ad)
    }

    /**
     * Decrypts [ciphertext] with [ad] as associated data and increments the nonce counter.
     *
     * If no key is set, returns [ciphertext] unchanged.
     *
     * @throws IllegalStateException if AEAD authentication fails.
     */
    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (!hasKey()) return ciphertext
        writeNonce(n)
        n++
        return crypto.aeadDecrypt(k, nonceBuffer, ciphertext, ad)
    }

    /** Noise §11.3 Rekey stub. Not used in v1; included for spec completeness. */
    fun rekey() {
        // Not implemented in v1.
    }

    /**
     * Writes a 64-bit nonce counter into [nonceBuffer] in-place (bytes 4–11, little-endian). Bytes
     * 0–3 stay zero — IETF ChaCha20-Poly1305 format per Noise spec §12.
     */
    private fun writeNonce(counter: Long) {
        var v = counter
        for (i in 4..11) {
            nonceBuffer[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    companion object {
        /**
         * Encodes a 64-bit nonce counter as the 12-byte IETF ChaCha20-Poly1305 nonce.
         *
         * Noise spec §12 (ChaChaPoly): 4 zero bytes ‖ little-endian 64-bit counter.
         *
         * Retained for use by callers outside [CipherState] (e.g. test utilities).
         */
        internal fun encodeNonce(counter: Long): ByteArray {
            val nonce = ByteArray(12) // bytes 0–3 remain 0 by default
            var v = counter
            for (i in 4..11) {
                nonce[i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
            return nonce
        }
    }
}
