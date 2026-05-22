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

    internal fun createMessage1(): ByteArray {
        val state = HandshakeState.initialize(cryptoProvider)
        val ephemeralKeyPair = cryptoProvider.generateX25519KeyPair()
        state.localEphemeralKeyPair = ephemeralKeyPair
        state.mixHash(ephemeralKeyPair.publicKey)
        initiatorState = state
        return ephemeralKeyPair.publicKey.copyOf()
    }

    internal fun processMessage1AndCreateMessage2(
        responderIdentity: NoiseIdentity,
        message1: ByteArray,
    ): ByteArray {
        require(message1.size == KEY_SIZE_BYTES) {
            "Noise XX message 1 must contain one X25519 public key"
        }
        val state = HandshakeState.initialize(cryptoProvider)
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

    private class HandshakeState
    private constructor(
        private val cryptoProvider: CryptoProvider,
        private var chainingKey: ByteArray,
        private var handshakeHash: ByteArray,
    ) {
        var cipherKey: ByteArray? = null
        var nonce: ULong = 0u
        var localEphemeralKeyPair: X25519KeyPair? = null
        var remoteEphemeralPublicKey: ByteArray? = null
        var remoteStaticPublicKey: ByteArray? = null
        var remoteEd25519PublicKey: ByteArray? = null
        var localStaticIdentity: NoiseIdentity? = null

        fun mixHash(data: ByteArray): Unit {
            handshakeHash = cryptoProvider.sha256(handshakeHash + data)
        }

        fun mixKey(inputKeyMaterial: ByteArray): Unit {
            val okm =
                hkdfSha256(
                    cryptoProvider,
                    chainingKey,
                    inputKeyMaterial,
                    byteArrayOf(),
                    HASH_LEN_BYTES * 2,
                )
            chainingKey = okm.copyOfRange(0, HASH_LEN_BYTES)
            cipherKey = okm.copyOfRange(HASH_LEN_BYTES, HASH_LEN_BYTES * 2)
            nonce = 0u
        }

        fun encryptAndHash(plaintext: ByteArray): ByteArray {
            val key = cipherKey ?: return plaintext.also(::mixHash)
            val ciphertext =
                cryptoProvider.chacha20Poly1305Seal(
                    key = key,
                    nonce = noiseNonce(nonce),
                    aad = handshakeHash,
                    plaintext = plaintext,
                )
            nonce += 1u
            mixHash(ciphertext)
            return ciphertext
        }

        fun decryptAndHash(ciphertext: ByteArray): ByteArray {
            val key = cipherKey ?: return ciphertext.also(::mixHash)
            val plaintext =
                cryptoProvider.chacha20Poly1305Open(
                    key = key,
                    nonce = noiseNonce(nonce),
                    aad = handshakeHash,
                    ciphertext = ciphertext,
                )
            nonce += 1u
            mixHash(ciphertext)
            return plaintext
        }

        fun split(): Pair<ByteArray, ByteArray> {
            val okm =
                hkdfSha256(
                    cryptoProvider,
                    chainingKey,
                    byteArrayOf(),
                    byteArrayOf(),
                    HASH_LEN_BYTES * 2,
                )
            return okm.copyOfRange(0, HASH_LEN_BYTES) to
                okm.copyOfRange(HASH_LEN_BYTES, HASH_LEN_BYTES * 2)
        }

        companion object {
            fun initialize(cryptoProvider: CryptoProvider): HandshakeState {
                val protocolName = NOISE_PROTOCOL_NAME.encodeToByteArray()
                val handshakeHash =
                    if (protocolName.size <= HASH_LEN_BYTES) {
                        protocolName + ByteArray(HASH_LEN_BYTES - protocolName.size)
                    } else {
                        cryptoProvider.sha256(protocolName)
                    }
                return HandshakeState(
                    cryptoProvider = cryptoProvider,
                    chainingKey = handshakeHash.copyOf(),
                    handshakeHash = handshakeHash,
                )
            }
        }
    }

    private companion object {
        fun noiseNonce(value: ULong): ByteArray {
            val nonce = ByteArray(NONCE_SIZE_BYTES)
            repeat(NONCE_COUNTER_SIZE_BYTES) { index ->
                nonce[NONCE_PREFIX_SIZE_BYTES + index] =
                    ((value shr (index * NONCE_BITS_PER_BYTE)) and NONCE_BYTE_MASK).toByte()
            }
            return nonce
        }

        private const val NOISE_PROTOCOL_NAME: String = "Noise_XX_25519_ChaChaPoly_SHA256"
        private const val NONCE_SIZE_BYTES: Int = 12
        private const val NONCE_COUNTER_SIZE_BYTES: Int = 8
        private const val NONCE_PREFIX_SIZE_BYTES: Int = 4
        private const val NONCE_BITS_PER_BYTE: Int = 8
        private const val NONCE_BYTE_MASK: ULong = 0xFFu
        private const val HASH_LEN_BYTES: Int = 32
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

internal fun hkdfSha256(
    provider: CryptoProvider,
    salt: ByteArray,
    ikm: ByteArray,
    info: ByteArray,
    outputLength: Int,
): ByteArray {
    require(outputLength <= HKDF_MAX_OUTPUT_BLOCKS * HKDF_HASH_LEN_BYTES) {
        "HKDF output too large"
    }
    val effectiveSalt = if (salt.isEmpty()) ByteArray(HKDF_HASH_LEN_BYTES) else salt
    val pseudoRandomKey = provider.hmacSha256(effectiveSalt, ikm)
    val output = ByteArray(outputLength)
    var previous = byteArrayOf()
    var offset = 0
    var counter = 1
    while (offset < outputLength) {
        previous =
            provider.hmacSha256(pseudoRandomKey, previous + info + byteArrayOf(counter.toByte()))
        val bytesToCopy = minOf(previous.size, outputLength - offset)
        previous.copyOfRange(0, bytesToCopy).copyInto(output, destinationOffset = offset)
        offset += bytesToCopy
        counter += 1
    }
    return output
}

private const val HKDF_HASH_LEN_BYTES: Int = 32
private const val HKDF_MAX_OUTPUT_BLOCKS: Int = 255
