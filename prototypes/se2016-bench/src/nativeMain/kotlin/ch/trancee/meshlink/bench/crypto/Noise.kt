package ch.trancee.meshlink.bench.crypto

/** Noise tokens, including the `hfs` KEM tokens `e1` and `ekem1` (Noise HFS spec, ADR 0002). */
internal enum class Token { E, S, EE, ES, SE, SS, E1, EKEM1 }

internal class Pattern(val protocolName: String, val responderStaticKnown: Boolean, val messages: List<List<Token>>) {
    companion object {
        val XX_HFS = Pattern(
            "Noise_XXhfs_25519+MLKEM768_ChaChaPoly_SHA256",
            responderStaticKnown = false,
            messages = listOf(
                listOf(Token.E, Token.E1),
                listOf(Token.E, Token.EE, Token.EKEM1, Token.S, Token.ES),
                listOf(Token.S, Token.SE),
            ),
        )
        val IK_HFS = Pattern(
            "Noise_IKhfs_25519+MLKEM768_ChaChaPoly_SHA256",
            responderStaticKnown = true,
            messages = listOf(
                listOf(Token.E, Token.ES, Token.E1, Token.S, Token.SS),
                listOf(Token.E, Token.EE, Token.EKEM1, Token.SE),
            ),
        )
    }
}

internal class CipherState(private val key: ByteArray) {
    private var counter = 0L

    fun encrypt(ad: ByteArray, plaintext: ByteArray): ByteArray = Sodium.aeadEncrypt(key, nextNonce(), ad, plaintext)

    fun decrypt(ad: ByteArray, ciphertext: ByteArray): ByteArray = Sodium.aeadDecrypt(key, nextNonce(), ad, ciphertext)

    private fun nextNonce(): ByteArray {
        val nonce = ByteArray(12)
        for (index in 0 until 8) nonce[4 + index] = (counter ushr (8 * index)).toByte()
        counter++
        return nonce
    }
}

internal class SymmetricState(protocolName: String) {
    private var chainingKey: ByteArray
    var handshakeHash: ByteArray
        private set
    private var cipher: CipherState? = null

    init {
        val name = protocolName.encodeToByteArray()
        handshakeHash = if (name.size <= 32) name.copyOf(32) else Sodium.sha256(name)
        chainingKey = handshakeHash.copyOf()
    }

    fun mixHash(data: ByteArray) {
        handshakeHash = Sodium.sha256(concat(handshakeHash, data))
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val (nextChainingKey, key) = hkdf(inputKeyMaterial)
        chainingKey = nextChainingKey
        cipher = CipherState(key)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipher?.encrypt(handshakeHash, plaintext) ?: plaintext
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipher?.decrypt(handshakeHash, ciphertext) ?: ciphertext
        mixHash(ciphertext)
        return plaintext
    }

    fun hasKey(): Boolean = cipher != null

    fun split(): Pair<CipherState, CipherState> {
        val (first, second) = hkdf(ByteArray(0))
        return CipherState(first) to CipherState(second)
    }

    private fun hkdf(inputKeyMaterial: ByteArray): Pair<ByteArray, ByteArray> {
        val output = Sodium.hkdfExpand(Sodium.hkdfExtract(chainingKey, inputKeyMaterial), ByteArray(0), 64)
        return output.copyOfRange(0, 32) to output.copyOfRange(32, 64)
    }
}

/**
 * One side of a Noise handshake over [Sodium]. [localStatic] is the X25519 Noise static key;
 * [remoteStatic] is known in advance only for patterns with a responder pre-message (IK).
 */
internal class HandshakeState(
    private val pattern: Pattern,
    private val initiator: Boolean,
    private val localStatic: KeyPair,
    remoteStatic: ByteArray? = null,
    prologue: ByteArray = ByteArray(0),
) {
    private val symmetric = SymmetricState(pattern.protocolName)
    private var localEphemeral: KeyPair? = null
    private var localKem: KeyPair? = null
    private var remoteEphemeral: ByteArray? = null
    private var remoteKem: ByteArray? = null
    var remoteStaticKey: ByteArray? = remoteStatic
        private set
    private var messageIndex = 0

    init {
        symmetric.mixHash(prologue)
        if (pattern.responderStaticKnown) {
            symmetric.mixHash(if (initiator) requireNotNull(remoteStatic) else localStatic.public)
        }
    }

    val isComplete: Boolean get() = messageIndex == pattern.messages.size

    fun writeMessage(payload: ByteArray): ByteArray {
        val parts = ArrayList<ByteArray>()
        for (token in nextTokens(writing = true)) {
            when (token) {
                Token.E -> Sodium.x25519Keypair().also { localEphemeral = it; symmetric.mixHash(it.public); parts += it.public }
                Token.S -> parts += symmetric.encryptAndHash(localStatic.public)
                Token.E1 -> Sodium.mlkemKeypair().also { localKem = it; parts += symmetric.encryptAndHash(it.public) }
                Token.EKEM1 -> {
                    val encapsulation = Sodium.mlkemEncapsulate(requireNotNull(remoteKem))
                    parts += symmetric.encryptAndHash(encapsulation.ciphertext)
                    symmetric.mixKey(encapsulation.sharedSecret)
                }
                else -> mixDh(token)
            }
        }
        parts += symmetric.encryptAndHash(payload)
        return concat(*parts.toTypedArray())
    }

    fun readMessage(message: ByteArray): ByteArray {
        var offset = 0
        fun take(size: Int): ByteArray {
            if (offset + size > message.size) throw CryptoFailure("handshake message truncated")
            return message.copyOfRange(offset, offset + size).also { offset += size }
        }
        fun tag(): Int = if (symmetric.hasKey()) Sodium.TAG_BYTES else 0
        for (token in nextTokens(writing = false)) {
            when (token) {
                Token.E -> take(Sodium.KEY_BYTES).also { remoteEphemeral = it; symmetric.mixHash(it) }
                Token.S -> remoteStaticKey = symmetric.decryptAndHash(take(Sodium.KEY_BYTES + tag()))
                Token.E1 -> remoteKem = symmetric.decryptAndHash(take(Sodium.MLKEM_PUBLIC_KEY_BYTES + tag()))
                Token.EKEM1 -> {
                    val ciphertext = symmetric.decryptAndHash(take(Sodium.MLKEM_CIPHERTEXT_BYTES + tag()))
                    symmetric.mixKey(Sodium.mlkemDecapsulate(requireNotNull(localKem).secret, ciphertext))
                }
                else -> mixDh(token)
            }
        }
        return symmetric.decryptAndHash(take(message.size - offset))
    }

    fun split(): Pair<CipherState, CipherState> {
        check(isComplete) { "handshake not complete" }
        return symmetric.split()
    }

    private fun nextTokens(writing: Boolean): List<Token> {
        check(!isComplete) { "handshake already complete" }
        val initiatorTurn = messageIndex % 2 == 0
        check(writing == (initiatorTurn == initiator)) { "out-of-turn handshake call" }
        return pattern.messages[messageIndex++]
    }

    private fun mixDh(token: Token) {
        val ephemeral = { requireNotNull(localEphemeral).secret }
        val static = { localStatic.secret }
        val remoteE = { requireNotNull(remoteEphemeral) }
        val remoteS = { requireNotNull(remoteStaticKey) }
        val shared = when (token) {
            Token.EE -> Sodium.x25519(ephemeral(), remoteE())
            Token.ES -> if (initiator) Sodium.x25519(ephemeral(), remoteS()) else Sodium.x25519(static(), remoteE())
            Token.SE -> if (initiator) Sodium.x25519(static(), remoteE()) else Sodium.x25519(ephemeral(), remoteS())
            Token.SS -> Sodium.x25519(static(), remoteS())
            else -> error("not a DH token: $token")
        }
        symmetric.mixKey(shared)
    }
}
