package ch.trancee.meshlink.crypto.noise

import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.KeyPair

/**
 * Result of [resolveHandshakeRace] — indicates which role this party should take when both peers
 * simultaneously initiate a Noise XX handshake.
 */
internal enum class HandshakeRole {
    /** This party has the higher ephemeral key and remains the initiator. */
    STAY_INITIATOR,

    /** This party has the lower ephemeral key and must restart as responder. */
    BECOME_RESPONDER,
}

/**
 * Resolves a simultaneous-handshake race by comparing ephemeral public keys lexicographically as
 * unsigned byte arrays.
 *
 * The party with the higher key stays as initiator; the party with the lower key restarts as
 * responder. Equal keys (same device or extreme collision) default to
 * [HandshakeRole.STAY_INITIATOR].
 *
 * @param localEphemeral The local party's ephemeral public key (32 bytes).
 * @param remoteEphemeral The remote party's ephemeral public key (32 bytes).
 */
internal fun resolveHandshakeRace(
    localEphemeral: ByteArray,
    remoteEphemeral: ByteArray,
): HandshakeRole {
    for (i in localEphemeral.indices) {
        val local = localEphemeral[i].toInt() and 0xFF
        val remote = remoteEphemeral[i].toInt() and 0xFF
        if (local > remote) return HandshakeRole.STAY_INITIATOR
        if (local < remote) return HandshakeRole.BECOME_RESPONDER
    }
    return HandshakeRole.STAY_INITIATOR // equal — default to initiator
}

/**
 * High-level initiator side of a Noise_XX_25519_ChaChaPoly_SHA256 handshake.
 *
 * **Message sequence:**
 * 1. [writeMessage1] — sends ephemeral key (→ e)
 * 2. [readMessage2] — receives `e, ee, s, es`
 * 3. [writeMessage3] — sends `s, se`
 * 4. [finalize] — returns a [NoiseSession] with derived transport keys
 */
internal class NoiseXXInitiator(crypto: CryptoProvider, staticKeyPair: KeyPair) {
    private val handshakeState = HandshakeState(crypto, isInitiator = true, staticKeyPair)

    /** The local ephemeral public key, available for race resolution before message exchange. */
    val localEphemeralPublicKey: ByteArray
        get() = handshakeState.localEphemeralPublicKey

    /**
     * Writes message 1 (→ e). Call this first.
     *
     * @param payload Optional application payload to include.
     * @return The serialized message bytes.
     */
    fun writeMessage1(payload: ByteArray = ByteArray(0)): ByteArray =
        handshakeState.writeMessage(payload)

    /**
     * Reads message 2 (← e, ee, s, es).
     *
     * @param message The received message bytes.
     * @return The decrypted payload from the responder.
     * @throws IllegalStateException if authentication fails.
     */
    fun readMessage2(message: ByteArray): ByteArray = handshakeState.readMessage(message)

    /**
     * Writes message 3 (→ s, se).
     *
     * @param payload Optional application payload.
     * @return The serialized message bytes.
     */
    fun writeMessage3(payload: ByteArray = ByteArray(0)): ByteArray =
        handshakeState.writeMessage(payload)

    /**
     * Finalizes the handshake and returns a [NoiseSession].
     *
     * Must be called after [writeMessage3] completes.
     *
     * @throws IllegalStateException if the handshake is not yet complete.
     */
    fun finalize(): NoiseSession {
        val (c1, c2) = handshakeState.split()
        return NoiseSession(
            sendState = c1,
            receiveState = c2,
            remoteStaticKey = handshakeState.getRemoteStaticKey(),
            handshakeHash = handshakeState.getHandshakeHash(),
        )
    }
}

/**
 * High-level responder side of a Noise_XX_25519_ChaChaPoly_SHA256 handshake.
 *
 * **Message sequence:**
 * 1. [readMessage1] — receives `e` from the initiator
 * 2. [writeMessage2] — sends `e, ee, s, es`
 * 3. [readMessage3] — receives `s, se`
 * 4. [finalize] — returns a [NoiseSession] with derived transport keys
 */
internal class NoiseXXResponder(crypto: CryptoProvider, staticKeyPair: KeyPair) {
    private val handshakeState = HandshakeState(crypto, isInitiator = false, staticKeyPair)

    /** The local ephemeral public key, available for race resolution before message exchange. */
    val localEphemeralPublicKey: ByteArray
        get() = handshakeState.localEphemeralPublicKey

    /**
     * Reads message 1 (→ e) from the initiator.
     *
     * @param message The received message bytes.
     * @return The payload from the initiator (typically empty in message 1).
     */
    fun readMessage1(message: ByteArray): ByteArray = handshakeState.readMessage(message)

    /**
     * Writes message 2 (← e, ee, s, es).
     *
     * @param payload Optional application payload.
     * @return The serialized message bytes.
     */
    fun writeMessage2(payload: ByteArray = ByteArray(0)): ByteArray =
        handshakeState.writeMessage(payload)

    /**
     * Reads message 3 (→ s, se) from the initiator.
     *
     * @param message The received message bytes.
     * @return The decrypted payload from the initiator.
     * @throws IllegalStateException if authentication fails.
     */
    fun readMessage3(message: ByteArray): ByteArray = handshakeState.readMessage(message)

    /**
     * Finalizes the handshake and returns a [NoiseSession].
     *
     * Must be called after [readMessage3] completes.
     *
     * @throws IllegalStateException if the handshake is not yet complete.
     */
    fun finalize(): NoiseSession {
        val (c1, c2) = handshakeState.split()
        return NoiseSession(
            sendState = c2,
            receiveState = c1,
            remoteStaticKey = handshakeState.getRemoteStaticKey(),
            handshakeHash = handshakeState.getHandshakeHash(),
        )
    }
}
