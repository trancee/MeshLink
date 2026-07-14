package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.api.MeshLinkException

internal class NoiseXXHandshakeResult
internal constructor(
    internal val message3: ByteArray,
    internal val sendKey: ByteArray,
    internal val receiveKey: ByteArray,
    internal val remoteStaticPublicKey: ByteArray,
    internal val remoteEd25519PublicKey: ByteArray,
)

internal class NoiseXXResponderResult
internal constructor(
    internal val sendKey: ByteArray,
    internal val receiveKey: ByteArray,
    internal val remoteStaticPublicKey: ByteArray,
    internal val remoteEd25519PublicKey: ByteArray,
)

internal class NoiseXXHandshakeManager
internal constructor(private val cryptoProvider: CryptoProvider) {
    private var initiatorState: HandshakeState? = null
    private var responderState: HandshakeState? = null

    /**
     * Starts a Noise XX handshake as the initiator.
     *
     * [meshDomainHash] is mixed into the handshake transcript as the Noise "prologue" before any
     * other handshake material, cryptographically binding the resulting session keys to a specific
     * mesh. Peers that supply a different [meshDomainHash] (i.e. a different mesh/`appId`) derive a
     * divergent transcript hash and will fail authentication when processing message 2 or 3, so a
     * handshake can only complete between peers that agree on the same mesh domain. Defaults to an
     * empty prologue, which reproduces the pre-domain-binding behavior (any peer can complete the
     * handshake) for callers that do not have a mesh domain to bind.
     */
    internal fun createMessage1(meshDomainHash: ByteArray = ByteArray(0)): ByteArray {
        val state = HandshakeState.initialize(cryptoProvider, meshDomainHash)
        val ephemeralKeyPair = cryptoProvider.generateX25519KeyPair()
        state.localEphemeralKeyPair = ephemeralKeyPair
        state.mixHash(ephemeralKeyPair.publicKey)
        initiatorState = state
        return ephemeralKeyPair.publicKey.copyOf()
    }

    /**
     * Processes an initiator's message 1 and produces message 2 as the responder.
     *
     * See [createMessage1] for the mesh-domain-binding contract: [meshDomainHash] must match the
     * value the initiator used, or the handshake will fail closed once the initiator processes this
     * message 2.
     */
    internal fun processMessage1AndCreateMessage2(
        responderIdentity: NoiseIdentity,
        message1: ByteArray,
        meshDomainHash: ByteArray = ByteArray(0),
    ): ByteArray {
        require(message1.size == KEY_SIZE_BYTES) {
            "Noise XX message 1 must contain one X25519 public key"
        }
        val state = HandshakeState.initialize(cryptoProvider, meshDomainHash)
        state.remoteEphemeralPublicKey = message1.copyOf()
        state.mixHash(message1)

        val responderEphemeralKeyPair = cryptoProvider.generateX25519KeyPair()
        state.localEphemeralKeyPair = responderEphemeralKeyPair
        state.mixHash(responderEphemeralKeyPair.publicKey)
        state.mixKey(
            cryptoProvider.x25519(
                responderEphemeralKeyPair.privateKey,
                state.remoteEphemeralPublicKey!!,
            )
        )

        val responderStaticPayload = encodeStaticPayload(responderIdentity)
        val encryptedStatic = state.encryptAndHash(responderStaticPayload)
        state.mixKey(
            cryptoProvider.x25519(
                responderIdentity.x25519KeyPair.privateKey,
                state.remoteEphemeralPublicKey!!,
            )
        )
        val encryptedPayload = state.encryptAndHash(byteArrayOf())

        responderState = state.apply { localStaticIdentity = responderIdentity }

        return responderEphemeralKeyPair.publicKey + encryptedStatic + encryptedPayload
    }

    /**
     * Attempts to process [message2] as the initiator without permanently mutating this manager's
     * internal Noise state if it does not match. Ordinarily, [processMessage2AndCreateMessage3]
     * mutates transcript state (`mixHash`/`mixKey`) irreversibly as soon as it starts, even if it
     * ultimately throws -- so trying a payload that doesn't actually belong to this manager (for
     * example: trial-matching a message2 against a bounded set of superseded initiator handshake
     * attempts, to recognize a stale/late reply) would otherwise permanently corrupt the manager,
     * leaving it unable to recognize its own genuine reply if that arrives afterwards. This
     * snapshots the state before attempting, and restores the snapshot if [message2] fails to
     * decrypt, so the manager can be safely retried later against a different payload.
     *
     * Returns the handshake result on a successful match, or null if [message2] does not match (any
     * exception while processing).
     */
    internal fun tryProcessMessage2AndCreateMessage3(
        initiatorIdentity: NoiseIdentity,
        message2: ByteArray,
    ): NoiseXXHandshakeResult? {
        val originalState = initiatorState ?: return null
        initiatorState = originalState.copy()
        return try {
            processMessage2AndCreateMessage3(initiatorIdentity, message2)
        } catch (exception: Exception) {
            initiatorState = originalState
            null
        }
    }

    internal fun processMessage2AndCreateMessage3(
        initiatorIdentity: NoiseIdentity,
        message2: ByteArray,
    ): NoiseXXHandshakeResult {
        val state =
            initiatorState
                ?: throw MeshLinkException.InvalidStateTransition(
                    "Noise XX initiator state is not initialized"
                )
        require(message2.size == MESSAGE2_SIZE_BYTES) { "Noise XX message 2 size is invalid" }

        val responderEphemeralPublicKey = message2.copyOfRange(0, KEY_SIZE_BYTES)
        state.remoteEphemeralPublicKey = responderEphemeralPublicKey
        state.mixHash(responderEphemeralPublicKey)
        state.mixKey(
            cryptoProvider.x25519(
                state.localEphemeralKeyPair!!.privateKey,
                responderEphemeralPublicKey,
            )
        )

        val encryptedStatic =
            message2.copyOfRange(
                KEY_SIZE_BYTES,
                KEY_SIZE_BYTES + ENCRYPTED_STATIC_PAYLOAD_SIZE_BYTES,
            )
        val responderStaticPayload = state.decryptAndHash(encryptedStatic)
        val decodedResponderStatic = decodeStaticPayload(responderStaticPayload)
        state.remoteStaticPublicKey = decodedResponderStatic.x25519PublicKey
        state.remoteEd25519PublicKey = decodedResponderStatic.ed25519PublicKey
        state.mixKey(
            cryptoProvider.x25519(
                state.localEphemeralKeyPair!!.privateKey,
                decodedResponderStatic.x25519PublicKey,
            )
        )

        val encryptedPayload =
            message2.copyOfRange(
                KEY_SIZE_BYTES + ENCRYPTED_STATIC_PAYLOAD_SIZE_BYTES,
                message2.size,
            )
        val payload = state.decryptAndHash(encryptedPayload)
        if (payload.isNotEmpty()) {
            throw MeshLinkException.CryptoFailure("Noise XX message 2 payload must be empty")
        }

        val initiatorStaticPayload = encodeStaticPayload(initiatorIdentity)
        val encryptedInitiatorStatic = state.encryptAndHash(initiatorStaticPayload)
        state.mixKey(
            cryptoProvider.x25519(
                initiatorIdentity.x25519KeyPair.privateKey,
                responderEphemeralPublicKey,
            )
        )
        val encryptedInitiatorPayload = state.encryptAndHash(byteArrayOf())
        val split = state.split()
        val message3 = encryptedInitiatorStatic + encryptedInitiatorPayload

        return NoiseXXHandshakeResult(
            message3 = message3,
            sendKey = split.first,
            receiveKey = split.second,
            remoteStaticPublicKey = decodedResponderStatic.x25519PublicKey,
            remoteEd25519PublicKey = decodedResponderStatic.ed25519PublicKey,
        )
    }

    internal fun processMessage3(message3: ByteArray): NoiseXXResponderResult {
        val state =
            responderState
                ?: throw MeshLinkException.InvalidStateTransition(
                    "Noise XX responder state is not initialized"
                )
        require(message3.size == MESSAGE3_SIZE_BYTES) { "Noise XX message 3 size is invalid" }

        val encryptedInitiatorStatic = message3.copyOfRange(0, ENCRYPTED_STATIC_PAYLOAD_SIZE_BYTES)
        val initiatorStaticPayload = state.decryptAndHash(encryptedInitiatorStatic)
        val decodedInitiatorStatic = decodeStaticPayload(initiatorStaticPayload)
        state.remoteStaticPublicKey = decodedInitiatorStatic.x25519PublicKey
        state.remoteEd25519PublicKey = decodedInitiatorStatic.ed25519PublicKey
        state.mixKey(
            cryptoProvider.x25519(
                state.localEphemeralKeyPair!!.privateKey,
                decodedInitiatorStatic.x25519PublicKey,
            )
        )

        val encryptedPayload =
            message3.copyOfRange(ENCRYPTED_STATIC_PAYLOAD_SIZE_BYTES, message3.size)
        val payload = state.decryptAndHash(encryptedPayload)
        if (payload.isNotEmpty()) {
            throw MeshLinkException.CryptoFailure("Noise XX message 3 payload must be empty")
        }

        val split = state.split()
        return NoiseXXResponderResult(
            sendKey = split.second,
            receiveKey = split.first,
            remoteStaticPublicKey = decodedInitiatorStatic.x25519PublicKey,
            remoteEd25519PublicKey = decodedInitiatorStatic.ed25519PublicKey,
        )
    }

    private fun encodeStaticPayload(identity: NoiseIdentity): ByteArray {
        return identity.x25519KeyPair.publicKey + identity.ed25519KeyPair.publicKey
    }

    private fun decodeStaticPayload(payload: ByteArray): DecodedStaticPayload {
        require(payload.size == STATIC_PAYLOAD_SIZE_BYTES) {
            "Noise XX static payload size is invalid"
        }
        return DecodedStaticPayload(
            x25519PublicKey = payload.copyOfRange(0, KEY_SIZE_BYTES),
            ed25519PublicKey = payload.copyOfRange(KEY_SIZE_BYTES, STATIC_PAYLOAD_SIZE_BYTES),
        )
    }

    private data class DecodedStaticPayload(
        val x25519PublicKey: ByteArray,
        val ed25519PublicKey: ByteArray,
    )

    private companion object {
        private const val KEY_SIZE_BYTES: Int = 32
        private const val STATIC_PAYLOAD_SIZE_BYTES: Int = 64
        private const val AEAD_TAG_SIZE_BYTES: Int = 16
        private const val ENCRYPTED_STATIC_PAYLOAD_SIZE_BYTES: Int =
            STATIC_PAYLOAD_SIZE_BYTES + AEAD_TAG_SIZE_BYTES
        private const val ENCRYPTED_EMPTY_PAYLOAD_SIZE_BYTES: Int = AEAD_TAG_SIZE_BYTES
        private const val MESSAGE2_SIZE_BYTES: Int =
            KEY_SIZE_BYTES +
                ENCRYPTED_STATIC_PAYLOAD_SIZE_BYTES +
                ENCRYPTED_EMPTY_PAYLOAD_SIZE_BYTES
        private const val MESSAGE3_SIZE_BYTES: Int =
            ENCRYPTED_STATIC_PAYLOAD_SIZE_BYTES + ENCRYPTED_EMPTY_PAYLOAD_SIZE_BYTES
    }
}
