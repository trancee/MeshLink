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

    // Non-nullable: EMPTY_BYTE_ARRAY is the sentinel for "not yet received".
    // In Noise XX the fields are always populated before any DH operation reads them,
    // so nullable-guard ?: throws in the token methods would be dead code.
    private var remoteEphemeral: ByteArray = EMPTY_BYTE_ARRAY
    private var remoteStatic: ByteArray = EMPTY_BYTE_ARRAY

    /** Protocol step counter shared across writeMessage and readMessage calls (0–3). */
    private var step: Int = 0

    /** Populated after the final message is processed. */
    private var splitResult: Pair<CipherState, CipherState>? = null

    /** The local ephemeral public key, exposed for simultaneous-handshake race resolution. */
    val localEphemeralPublicKey: ByteArray
        get() = ephemeralKeyPair.publicKey.copyOf()

    init {
        symmetricState.initializeSymmetric(PROTOCOL_NAME)
        symmetricState.mixHash(EMPTY_BYTE_ARRAY) // empty prologue per Noise spec §5.3
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
    fun writeMessage(payload: ByteArray = EMPTY_BYTE_ARRAY): ByteArray {
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
        // Single allocation: compute total size, then copy sequentially.
        val totalSize = parts.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (part in parts) {
            part.copyInto(result, offset)
            offset += part.size
        }
        return result
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
    fun getRemoteStaticKey(): ByteArray {
        if (remoteStatic.isEmpty()) {
            throw IllegalStateException("Remote static key not yet received")
        }
        return remoteStatic.copyOf()
    }

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
        // In Noise XX, readTokenS is always reached after a mixKey call (ee or es),
        // so the symmetric state always has an active key. The static public key is
        // therefore always AEAD-encrypted: DH_LEN + TAG_LEN bytes.
        val length = DH_LEN + TAG_LEN
        if (message.size < offset + length) {
            throw IllegalArgumentException(
                "Message too short to read static key: need ${offset + length}, got ${message.size}"
            )
        }
        val encryptedStatic = message.copyOfRange(offset, offset + length)
        remoteStatic = symmetricState.decryptAndHash(encryptedStatic)
        return offset + length
    }

    // ── Token: ee ────────────────────────────────────────────────────────────

    private fun mixTokenEE() {
        // remoteEphemeral is always set by readTokenE before this is called in XX.
        symmetricState.mixKey(
            crypto.x25519SharedSecret(ephemeralKeyPair.privateKey, remoteEphemeral)
        )
    }

    // ── Token: es ────────────────────────────────────────────────────────────

    private fun mixTokenES() {
        // Initiator: DH(e, rs); Responder: DH(s, re). Both fields are guaranteed set in XX.
        val dh =
            if (isInitiator) {
                crypto.x25519SharedSecret(ephemeralKeyPair.privateKey, remoteStatic)
            } else {
                crypto.x25519SharedSecret(staticKeyPair.privateKey, remoteEphemeral)
            }
        symmetricState.mixKey(dh)
    }

    // ── Token: se ────────────────────────────────────────────────────────────

    private fun mixTokenSE() {
        // Initiator: DH(s, re); Responder: DH(e, rs). Both fields are guaranteed set in XX.
        val dh =
            if (isInitiator) {
                crypto.x25519SharedSecret(staticKeyPair.privateKey, remoteEphemeral)
            } else {
                crypto.x25519SharedSecret(ephemeralKeyPair.privateKey, remoteStatic)
            }
        symmetricState.mixKey(dh)
    }
}
