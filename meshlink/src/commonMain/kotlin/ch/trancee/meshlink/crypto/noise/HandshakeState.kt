package ch.trancee.meshlink.crypto.noise

import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.KeyPair

/**
 * Noise Protocol Framework §5.3 HandshakeState for the XX pattern.
 *
 * Processes the 3-message Noise_XX_25519_ChaChaPoly_SHA256 handshake:
 * ```
 *   → e
 *   ← e, ee, s, es
 *   → s, se
 * ```
 *
 * **Usage:** call [writeMessage] and [readMessage] in alternating order per the protocol sequence,
 * then call [split] to obtain the two transport [CipherState]s.
 *
 * Both [writeMessage] and [readMessage] advance an internal [step] counter (0 → 1 → 2 → 3). When
 * [step] reaches 3 (after the last message), [split] is populated automatically.
 */
internal class HandshakeState(
    private val crypto: CryptoProvider,
    private val isInitiator: Boolean,
    private val staticKeyPair: KeyPair,
) {

    private companion object {
        const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
        const val DH_LEN = 32
        const val TAG_LEN = 16
    }

    private val symmetricState: SymmetricState = SymmetricState(crypto)
    private val ephemeralKeyPair: KeyPair = crypto.generateX25519KeyPair()

    private var remoteEphemeral: ByteArray? = null
    private var remoteStatic: ByteArray? = null

    /** Protocol step counter shared across writeMessage and readMessage calls (0–3). */
    private var step: Int = 0

    /** Populated after the final message is processed. */
    private var splitResult: Pair<CipherState, CipherState>? = null

    /** The local ephemeral public key, exposed for simultaneous-handshake race resolution. */
    val localEphemeralPublicKey: ByteArray
        get() = ephemeralKeyPair.publicKey.copyOf()

    init {
        symmetricState.initializeSymmetric(PROTOCOL_NAME)
        symmetricState.mixHash(ByteArray(0)) // empty prologue per Noise spec §5.3
    }

    /**
     * Writes a handshake message for this party.
     *
     * Valid calls:
     * - Initiator at step 0: sends `e` (message 1)
     * - Responder at step 1: sends `e, ee, s, es` (message 2)
     * - Initiator at step 2: sends `s, se` (message 3)
     *
     * @param payload Optional payload (encrypted once a key is established).
     * @return The serialized handshake message bytes.
     * @throws IllegalStateException if called in an invalid state.
     */
    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        val parts = mutableListOf<ByteArray>()
        when {
            isInitiator && step == 0 -> {
                // → e
                parts.add(writeTokenE())
            }
            !isInitiator && step == 1 -> {
                // ← e, ee, s, es
                parts.add(writeTokenE())
                mixTokenEE()
                parts.add(writeTokenS())
                mixTokenES()
            }
            isInitiator && step == 2 -> {
                // → s, se
                parts.add(writeTokenS())
                mixTokenSE()
            }
            else ->
                throw IllegalStateException(
                    "writeMessage called in invalid state: step=$step, isInitiator=$isInitiator"
                )
        }
        parts.add(symmetricState.encryptAndHash(payload))
        step++
        if (step == 3) splitResult = symmetricState.split()
        return parts.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
    }

    /**
     * Reads a received handshake message.
     *
     * Valid calls:
     * - Responder at step 0: reads `e` (message 1)
     * - Initiator at step 1: reads `e, ee, s, es` (message 2)
     * - Responder at step 2: reads `s, se` (message 3)
     *
     * @param message The received handshake message bytes.
     * @return The decrypted payload.
     * @throws IllegalStateException if called in an invalid state or AEAD authentication fails.
     */
    fun readMessage(message: ByteArray): ByteArray {
        var offset = 0
        when {
            !isInitiator && step == 0 -> {
                // Read → e
                offset = readTokenE(message, offset)
            }
            isInitiator && step == 1 -> {
                // Read ← e, ee, s, es
                offset = readTokenE(message, offset)
                mixTokenEE()
                offset = readTokenS(message, offset)
                mixTokenES()
            }
            !isInitiator && step == 2 -> {
                // Read → s, se
                offset = readTokenS(message, offset)
                mixTokenSE()
            }
            else ->
                throw IllegalStateException(
                    "readMessage called in invalid state: step=$step, isInitiator=$isInitiator"
                )
        }
        val encryptedPayload = message.copyOfRange(offset, message.size)
        val payload = symmetricState.decryptAndHash(encryptedPayload)
        step++
        if (step == 3) splitResult = symmetricState.split()
        return payload
    }

    /**
     * Returns the two transport [CipherState]s. Only valid after all 3 messages are processed.
     *
     * c1 = initiator→responder direction; c2 = responder→initiator direction.
     *
     * @throws IllegalStateException if the handshake is not yet complete.
     */
    fun split(): Pair<CipherState, CipherState> =
        splitResult
            ?: throw IllegalStateException("Handshake not complete — call all 3 messages first")

    /**
     * Returns the remote static public key (32 bytes). Only valid after the handshake completes.
     *
     * @throws IllegalStateException if the remote static key has not yet been received.
     */
    fun getRemoteStaticKey(): ByteArray =
        remoteStatic?.copyOf() ?: throw IllegalStateException("Remote static key not yet received")

    /** Returns the handshake hash for channel binding (Noise §11.2). */
    fun getHandshakeHash(): ByteArray = symmetricState.getHandshakeHash()

    // ── Token: e (write) ──────────────────────────────────────────────────────

    private fun writeTokenE(): ByteArray {
        symmetricState.mixHash(ephemeralKeyPair.publicKey)
        return ephemeralKeyPair.publicKey
    }

    // ── Token: e (read) ───────────────────────────────────────────────────────

    private fun readTokenE(message: ByteArray, offset: Int): Int {
        if (message.size < offset + DH_LEN) {
            throw IllegalArgumentException(
                "Message too short to read ephemeral key: need ${offset + DH_LEN}, got ${message.size}"
            )
        }
        val re = message.copyOfRange(offset, offset + DH_LEN)
        remoteEphemeral = re
        symmetricState.mixHash(re)
        return offset + DH_LEN
    }

    // ── Token: s (write) ──────────────────────────────────────────────────────

    private fun writeTokenS(): ByteArray = symmetricState.encryptAndHash(staticKeyPair.publicKey)

    // ── Token: s (read) ───────────────────────────────────────────────────────

    private fun readTokenS(message: ByteArray, offset: Int): Int {
        // If a key is present, the static key is encrypted: DH_LEN + TAG_LEN bytes.
        // Before the first mixKey, it is sent in cleartext: DH_LEN bytes.
        val len = if (symmetricState.hasKey()) DH_LEN + TAG_LEN else DH_LEN
        if (message.size < offset + len) {
            throw IllegalArgumentException(
                "Message too short to read static key: need ${offset + len}, got ${message.size}"
            )
        }
        val encryptedStatic = message.copyOfRange(offset, offset + len)
        remoteStatic = symmetricState.decryptAndHash(encryptedStatic)
        return offset + len
    }

    // ── Token: ee ────────────────────────────────────────────────────────────

    private fun mixTokenEE() {
        val re =
            remoteEphemeral
                ?: throw IllegalStateException("Remote ephemeral not available for 'ee'")
        symmetricState.mixKey(crypto.x25519SharedSecret(ephemeralKeyPair.privateKey, re))
    }

    // ── Token: es ────────────────────────────────────────────────────────────

    private fun mixTokenES() {
        val dh =
            if (isInitiator) {
                // Initiator: DH(e, rs)
                val rs =
                    remoteStatic
                        ?: throw IllegalStateException("Remote static not available for 'es'")
                crypto.x25519SharedSecret(ephemeralKeyPair.privateKey, rs)
            } else {
                // Responder: DH(s, re)
                val re =
                    remoteEphemeral
                        ?: throw IllegalStateException("Remote ephemeral not available for 'es'")
                crypto.x25519SharedSecret(staticKeyPair.privateKey, re)
            }
        symmetricState.mixKey(dh)
    }

    // ── Token: se ────────────────────────────────────────────────────────────

    private fun mixTokenSE() {
        val dh =
            if (isInitiator) {
                // Initiator: DH(s, re)
                val re =
                    remoteEphemeral
                        ?: throw IllegalStateException("Remote ephemeral not available for 'se'")
                crypto.x25519SharedSecret(staticKeyPair.privateKey, re)
            } else {
                // Responder: DH(e, rs)
                val rs =
                    remoteStatic
                        ?: throw IllegalStateException("Remote static not available for 'se'")
                crypto.x25519SharedSecret(ephemeralKeyPair.privateKey, rs)
            }
        symmetricState.mixKey(dh)
    }
}
