package ch.trancee.meshlink.crypto.noise

/**
 * Bidirectional transport session produced by a completed Noise XX handshake.
 *
 * Holds two independent [CipherState]s — one for each direction — plus the remote peer's static
 * public key and the handshake hash for channel binding.
 *
 * Obtain an instance by completing the handshake via [NoiseXXInitiator.finalize] or
 * [NoiseXXResponder.finalize].
 */
internal class NoiseSession(
    private val sendState: CipherState,
    private val receiveState: CipherState,
    private val remoteStaticKey: ByteArray,
    private val handshakeHash: ByteArray,
) {

    /**
     * Encrypts [plaintext] using the send [CipherState] with empty associated data.
     *
     * The nonce counter is incremented after each call. Do not reuse this session across devices.
     *
     * @return The ciphertext concatenated with a 16-byte Poly1305 authentication tag.
     */
    fun encrypt(plaintext: ByteArray): ByteArray =
        sendState.encryptWithAd(EMPTY_BYTE_ARRAY, plaintext)

    /**
     * Decrypts [ciphertext] using the receive [CipherState] with empty associated data.
     *
     * @return The decrypted plaintext.
     * @throws IllegalStateException if the authentication tag verification fails.
     */
    fun decrypt(ciphertext: ByteArray): ByteArray =
        receiveState.decryptWithAd(EMPTY_BYTE_ARRAY, ciphertext)

    /** Returns the remote peer's static X25519 public key (32 bytes). */
    fun getRemoteStaticKey(): ByteArray = remoteStaticKey.copyOf()

    /**
     * Returns the handshake hash (32 bytes) for use in channel binding (Noise §11.2).
     *
     * The hash is unique per session and can be signed or used in a higher-level authentication
     * protocol to tie application-layer identities to the transport session.
     */
    fun getHandshakeHash(): ByteArray = handshakeHash.copyOf()
}
