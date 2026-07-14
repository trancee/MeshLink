package ch.trancee.meshlink.crypto

/**
 * Mutable Noise XX handshake transcript state shared by [NoiseXXHandshakeManager]'s initiator and
 * responder paths: the running chaining key and handshake hash (mixed via [mixHash]/[mixKey]), the
 * transport cipher key derived once a DH result has been mixed in, and the local/remote key
 * material accumulated as each handshake message is processed.
 */
internal class HandshakeState
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

    /**
     * Returns an independent copy of this state, so a trial operation on the copy (see
     * [NoiseXXHandshakeManager.tryProcessMessage2AndCreateMessage3]) cannot mutate the original --
     * every field mutated by [mixHash]/[mixKey]/[encryptAndHash]/[decryptAndHash] is reassigned
     * wholesale rather than mutated in place, so copying the current byte array references (or
     * fresh [copyOf] snapshots, for ones that could still be referenced elsewhere) is sufficient;
     * no deep object graph traversal is needed.
     */
    fun copy(): HandshakeState {
        return HandshakeState(
                cryptoProvider = cryptoProvider,
                chainingKey = chainingKey,
                handshakeHash = handshakeHash,
            )
            .also { copy ->
                copy.cipherKey = cipherKey
                copy.nonce = nonce
                copy.localEphemeralKeyPair = localEphemeralKeyPair
                copy.remoteEphemeralPublicKey = remoteEphemeralPublicKey
                copy.remoteStaticPublicKey = remoteStaticPublicKey
                copy.remoteEd25519PublicKey = remoteEd25519PublicKey
                copy.localStaticIdentity = localStaticIdentity
            }
    }

    companion object {
        /**
         * Initializes handshake state and mixes in [prologue] as the Noise prologue, before any
         * other handshake material. Two peers that initialize with different prologues derive
         * different chaining keys/transcript hashes and can never complete a handshake together,
         * even if their static/ephemeral keys are otherwise compatible.
         */
        fun initialize(cryptoProvider: CryptoProvider, prologue: ByteArray): HandshakeState {
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
                .apply { mixHash(prologue) }
        }
    }
}

private fun noiseNonce(value: ULong): ByteArray {
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
