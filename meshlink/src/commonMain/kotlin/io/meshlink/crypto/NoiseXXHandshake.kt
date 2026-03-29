package io.meshlink.crypto

/**
 * Noise XX handshake state machine.
 *
 * Pattern (initiator perspective):
 *   → e                    (msg1: initiator sends ephemeral public key)
 *   ← e, ee, s, es         (msg2: responder sends ephemeral + encrypted static)
 *   → s, se                (msg3: initiator sends encrypted static)
 *
 * After 3 messages, both sides derive symmetric transport keys.
 * This is a pure state machine — no I/O, no threads, fully testable.
 */
class NoiseXXHandshake private constructor(
    private val crypto: CryptoProvider,
    private val localStatic: CryptoKeyPair,
    private val isInitiator: Boolean,
) {
    private var localEphemeral: CryptoKeyPair? = null
    private var remoteEphemeralPub: ByteArray? = null
    private var remoteStaticPub: ByteArray? = null
    private var chainingKey: ByteArray = ByteArray(32)
    private var handshakeHash: ByteArray = ByteArray(32)
    private var messageIndex = 0
    private var _peerPayload: ByteArray? = null

    /** Payload received from the remote peer during the handshake (available after completion). */
    val peerPayload: ByteArray? get() = _peerPayload

    data class HandshakeResult(
        val sendKey: ByteArray,
        val receiveKey: ByteArray,
        val remoteStaticKey: ByteArray,
        val peerPayload: ByteArray? = null,
    )

    companion object {
        private val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256".encodeToByteArray()

        fun initiator(crypto: CryptoProvider, staticKeyPair: CryptoKeyPair): NoiseXXHandshake {
            return NoiseXXHandshake(crypto, staticKeyPair, isInitiator = true).apply { initialize() }
        }

        fun responder(crypto: CryptoProvider, staticKeyPair: CryptoKeyPair): NoiseXXHandshake {
            return NoiseXXHandshake(crypto, staticKeyPair, isInitiator = false).apply { initialize() }
        }
    }

    private fun initialize() {
        // h = SHA256(protocol_name padded or hashed to 32 bytes)
        handshakeHash = if (PROTOCOL_NAME.size <= 32) {
            PROTOCOL_NAME.copyOf(32)
        } else {
            crypto.sha256(PROTOCOL_NAME)
        }
        chainingKey = handshakeHash.copyOf()
    }

    val isComplete: Boolean get() = messageIndex >= 3

    /**
     * Write the next outbound handshake message.
     * Call order: initiator writes msg1, msg3; responder writes msg2.
     */
    fun writeMessage(payload: ByteArray = byteArrayOf()): ByteArray {
        check(!isComplete) { "Handshake already complete" }
        return when {
            isInitiator && messageIndex == 0 -> writeMsg1(payload)
            !isInitiator && messageIndex == 1 -> writeMsg2(payload)
            isInitiator && messageIndex == 2 -> writeMsg3(payload)
            else -> error(
                "Unexpected writeMessage at index $messageIndex for ${if (isInitiator) "initiator" else "responder"}",
            )
        }
    }

    /**
     * Read an inbound handshake message.
     * Call order: responder reads msg1; initiator reads msg2; responder reads msg3.
     */
    fun readMessage(message: ByteArray): ByteArray {
        check(!isComplete) { "Handshake already complete" }
        val payload = when {
            !isInitiator && messageIndex == 0 -> readMsg1(message)
            isInitiator && messageIndex == 1 -> readMsg2(message)
            !isInitiator && messageIndex == 2 -> readMsg3(message)
            else -> error(
                "Unexpected readMessage at index $messageIndex for ${if (isInitiator) "initiator" else "responder"}",
            )
        }
        if (payload.isNotEmpty()) {
            _peerPayload = payload
        }
        return payload
    }

    /**
     * After handshake completes, derive transport keys.
     */
    fun finalize(): HandshakeResult {
        check(isComplete) { "Handshake not yet complete" }
        val (k1, k2) = hkdfPair(chainingKey, byteArrayOf())
        return if (isInitiator) {
            HandshakeResult(
                sendKey = k1,
                receiveKey = k2,
                remoteStaticKey = remoteStaticPub!!,
                peerPayload = _peerPayload,
            )
        } else {
            HandshakeResult(
                sendKey = k2,
                receiveKey = k1,
                remoteStaticKey = remoteStaticPub!!,
                peerPayload = _peerPayload,
            )
        }
    }

    // ── Message 1: → e ──

    private fun writeMsg1(payload: ByteArray): ByteArray {
        localEphemeral = crypto.generateX25519KeyPair()
        val ePub = localEphemeral!!.publicKey
        mixHash(ePub)
        val encPayload = encryptAndHash(payload)
        messageIndex = 1
        return ePub + encPayload
    }

    private fun readMsg1(message: ByteArray): ByteArray {
        require(message.size >= 32) { "msg1 too short" }
        remoteEphemeralPub = message.copyOfRange(0, 32)
        mixHash(remoteEphemeralPub!!)
        val payload = decryptAndHash(message.copyOfRange(32, message.size))
        messageIndex = 1
        return payload
    }

    // ── Message 2: ← e, ee, s, es ──

    private fun writeMsg2(payload: ByteArray): ByteArray {
        localEphemeral = crypto.generateX25519KeyPair()
        val ePub = localEphemeral!!.publicKey
        mixHash(ePub)

        // ee
        mixKey(crypto.x25519SharedSecret(localEphemeral!!.privateKey, remoteEphemeralPub!!))

        // s (encrypted static)
        val encStatic = encryptAndHash(localStatic.publicKey)

        // es
        mixKey(crypto.x25519SharedSecret(localStatic.privateKey, remoteEphemeralPub!!))

        val encPayload = encryptAndHash(payload)
        messageIndex = 2
        return ePub + encStatic + encPayload
    }

    private fun readMsg2(message: ByteArray): ByteArray {
        var offset = 0

        // e
        remoteEphemeralPub = message.copyOfRange(offset, offset + 32)
        offset += 32
        mixHash(remoteEphemeralPub!!)

        // ee
        mixKey(crypto.x25519SharedSecret(localEphemeral!!.privateKey, remoteEphemeralPub!!))

        // s (encrypted static = 32 bytes + 16 byte tag)
        val encStatic = message.copyOfRange(offset, offset + 48)
        offset += 48
        remoteStaticPub = decryptAndHash(encStatic)

        // es
        mixKey(crypto.x25519SharedSecret(localEphemeral!!.privateKey, remoteStaticPub!!))

        val payload = decryptAndHash(message.copyOfRange(offset, message.size))
        messageIndex = 2
        return payload
    }

    // ── Message 3: → s, se ──

    private fun writeMsg3(payload: ByteArray): ByteArray {
        // s (encrypted static)
        val encStatic = encryptAndHash(localStatic.publicKey)

        // se
        mixKey(crypto.x25519SharedSecret(localStatic.privateKey, remoteEphemeralPub!!))

        val encPayload = encryptAndHash(payload)
        messageIndex = 3
        return encStatic + encPayload
    }

    private fun readMsg3(message: ByteArray): ByteArray {
        var offset = 0

        // s (encrypted static = 32 bytes + 16 byte tag)
        val encStatic = message.copyOfRange(offset, offset + 48)
        offset += 48
        remoteStaticPub = decryptAndHash(encStatic)

        // se
        mixKey(crypto.x25519SharedSecret(localEphemeral!!.privateKey, remoteStaticPub!!))

        val payload = decryptAndHash(message.copyOfRange(offset, message.size))
        messageIndex = 3
        return payload
    }

    // ── Symmetric state operations ──

    private var hasKey = false
    private var symmetricKey = ByteArray(32)
    private var nonce: Long = 0

    private fun mixHash(data: ByteArray) {
        handshakeHash = crypto.sha256(handshakeHash + data)
    }

    private fun mixKey(inputKeyMaterial: ByteArray) {
        val (ck, tempK) = hkdfPair(chainingKey, inputKeyMaterial)
        chainingKey = ck
        symmetricKey = tempK
        nonce = 0
        hasKey = true
    }

    private fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = if (hasKey) {
            val n = nonceBytes()
            nonce++
            crypto.aeadEncrypt(symmetricKey, n, plaintext, handshakeHash)
        } else {
            plaintext
        }
        mixHash(ciphertext)
        return ciphertext
    }

    private fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = if (hasKey) {
            val n = nonceBytes()
            nonce++
            crypto.aeadDecrypt(symmetricKey, n, ciphertext, handshakeHash)
        } else {
            ciphertext
        }
        mixHash(ciphertext)
        return plaintext
    }

    private fun nonceBytes(): ByteArray {
        // 12-byte nonce: 4 zero bytes + 8-byte little-endian counter
        val n = ByteArray(12)
        var v = nonce
        for (i in 4..11) {
            n[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return n
    }

    private fun hkdfPair(ck: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val output = crypto.hkdfSha256(ikm, ck, byteArrayOf(), 64)
        return Pair(output.copyOfRange(0, 32), output.copyOfRange(32, 64))
    }
}
