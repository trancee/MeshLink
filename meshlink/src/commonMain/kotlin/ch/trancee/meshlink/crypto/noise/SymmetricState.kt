package ch.trancee.meshlink.crypto.noise

import ch.trancee.meshlink.crypto.CryptoProvider

/**
 * Noise Protocol Framework §5.2 SymmetricState.
 *
 * Accumulates all handshake data into the handshake hash [h] (used as AEAD associated data), and
 * mixes DH outputs into the chaining key [ck] to derive transport keys at the end of the handshake.
 */
internal class SymmetricState(private val crypto: CryptoProvider) {

    private var ck: ByteArray = ByteArray(32)
    private var h: ByteArray = ByteArray(32)
    private val cipherState: CipherState = CipherState(crypto)

    /**
     * Initializes the symmetric state with a protocol name per Noise spec §5.2.
     *
     * If [protocolName] is ≤ 32 bytes (HASHLEN), zero-pads to 32 bytes. If longer, SHA-256 hashes
     * it. Sets both [h] and [ck] to the resulting value.
     */
    fun initializeSymmetric(protocolName: String) {
        val nameBytes = protocolName.encodeToByteArray()
        val initialValue =
            if (nameBytes.size <= 32) {
                val padded = ByteArray(32)
                nameBytes.copyInto(padded)
                padded
            } else {
                crypto.sha256(nameBytes)
            }
        h = initialValue.copyOf()
        ck = initialValue.copyOf()
    }

    /**
     * Mixes a DH output [ikm] into the chaining key (Noise §5.2 MixKey).
     *
     * HKDF(ck, ikm, "", 64): first 32 bytes become the new [ck]; next 32 bytes initialize a new
     * cipher key (resetting the nonce counter to 0).
     */
    fun mixKey(ikm: ByteArray) {
        val output = crypto.hkdfSha256(ck, ikm, ByteArray(0), 64)
        ck = output.copyOfRange(0, 32)
        cipherState.initializeKey(output.copyOfRange(32, 64))
    }

    /**
     * Mixes [data] into the handshake hash (Noise §5.2 MixHash).
     *
     * h = SHA-256(h ‖ data)
     */
    fun mixHash(data: ByteArray) {
        h = crypto.sha256(h + data)
    }

    /**
     * Mixes [ikm] into both the chaining key and the handshake hash (Noise §5.2 MixKeyAndHash).
     *
     * Used for PSK mode. HKDF(ck, ikm, "", 96): first 32 bytes → new ck, next 32 bytes → MixHash
     * input, last 32 bytes → new cipher key.
     */
    fun mixKeyAndHash(ikm: ByteArray) {
        val output = crypto.hkdfSha256(ck, ikm, ByteArray(0), 96)
        ck = output.copyOfRange(0, 32)
        mixHash(output.copyOfRange(32, 64))
        cipherState.initializeKey(output.copyOfRange(64, 96))
    }

    /**
     * Encrypts [plaintext] using the current [h] as associated data, then mixes the ciphertext into
     * [h] (Noise §5.2 EncryptAndHash).
     */
    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipherState.encryptWithAd(h, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    /**
     * Decrypts [ciphertext] using the current [h] as associated data, then mixes [ciphertext] into
     * [h] (Noise §5.2 DecryptAndHash). Always hashes the ciphertext, not the plaintext.
     *
     * @throws IllegalStateException if AEAD authentication fails.
     */
    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipherState.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    /**
     * Splits the symmetric state into two transport [CipherState]s (Noise §5.2 Split).
     *
     * HKDF(ck, "", "", 64): first 32 bytes → c1 key (initiator→responder), next 32 bytes → c2 key
     * (responder→initiator).
     */
    fun split(): Pair<CipherState, CipherState> {
        val output = crypto.hkdfSha256(ck, ByteArray(0), ByteArray(0), 64)
        val c1 = CipherState(crypto)
        c1.initializeKey(output.copyOfRange(0, 32))
        val c2 = CipherState(crypto)
        c2.initializeKey(output.copyOfRange(32, 64))
        return Pair(c1, c2)
    }

    /** Returns the current handshake hash for channel binding (Noise §11.2 GetHandshakeHash). */
    fun getHandshakeHash(): ByteArray = h.copyOf()

    /** Returns true if the inner CipherState has an initialized key. */
    fun hasKey(): Boolean = cipherState.hasKey()
}
