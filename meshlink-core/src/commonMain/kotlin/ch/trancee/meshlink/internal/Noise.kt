package ch.trancee.meshlink.internal

/**
 * The Noise Protocol Framework (revision 34) for MeshLink's link handshakes, written once over
 * [CryptoProvider] (ADR 0002, ADR 0004): DH 25519, cipher ChaChaPoly, hash SHA-256, and `HKDF` as
 * HKDF-SHA-256 with empty `info`. The state machine knows nothing of links, frames or the radio.
 */
public enum class NoisePattern(
    /** The Noise protocol name, which the handshake hashes first. */
    public val protocolName: String,
    internal val messages: List<List<NoiseToken>>,
) {
    /** `XX`: the open mesh's link handshake in the `Classical` profile. */
    XX("Noise_XX_25519_ChaChaPoly_SHA256", xx()),

    /** `XXpsk3`: the gated mesh's, with the mesh psk mixed in after `se` of message 3. */
    XX_PSK3("Noise_XXpsk3_25519_ChaChaPoly_SHA256", xx(NoiseToken.PSK)),
    ;

    internal val usesPsk: Boolean get() = messages.any { NoiseToken.PSK in it }
}

internal enum class NoiseToken { E, S, EE, ES, SE, PSK }

private fun xx(vararg last: NoiseToken): List<List<NoiseToken>> =
    listOf(
        listOf(NoiseToken.E),
        listOf(NoiseToken.E, NoiseToken.EE, NoiseToken.S, NoiseToken.ES),
        listOf(NoiseToken.S, NoiseToken.SE, *last),
    )

/** Which side of a handshake this node is; the initiator writes the first message. */
public enum class NoiseRole { INITIATOR, RESPONDER }

/**
 * One side's keys: its X25519 [static] key pair, the 32-byte [psk] if the pattern has one, and the
 * [ephemeral] key pair; `null` draws a fresh one from the provider. Only tests fix the ephemeral.
 */
public class NoiseKeys(
    internal val static: KeyPair,
    psk: ByteArray? = null,
    internal val ephemeral: KeyPair? = null,
) {
    internal val psk: ByteArray? = psk?.copyOf()

    init {
        psk?.let { require(it.size == KEY_LENGTH) { "A Noise psk is $KEY_LENGTH bytes" } }
    }
}

/** The outcome of a Noise step: [Ok] or [Failed]. A failed state accepts no further call. */
public sealed interface NoiseResult<out T> {
    /** The step succeeded with [value]. */
    public class Ok<out T>(
        public val value: T,
    ) : NoiseResult<T>

    /** The step failed for [failure]; the handshake or transport is closed. */
    public class Failed(
        public val failure: NoiseFailure,
    ) : NoiseResult<Nothing>
}

/** Why a Noise step failed. Each closes the handshake or transport it happened in. */
public enum class NoiseFailure {
    /** A message ends before a public key it must carry. */
    MESSAGE_TOO_SHORT,

    /** A ciphertext's tag didn't verify: a changed, truncated, extended, replayed or wrong-psk message. */
    AUTHENTICATION_FAILED,

    /** A peer's public key gives an all-zero X25519 result. */
    INVALID_PUBLIC_KEY,

    /** The call doesn't fit the pattern: writing on the peer's turn, reading on ours, or splitting early. */
    OUT_OF_ORDER,

    /** The cipher state's nonce reached `2^64 − 1`, which Noise reserves. */
    NONCE_EXHAUSTED,

    /** The handshake or transport already failed, or the handshake already split. */
    CLOSED,
}

/**
 * A Noise cipher state: a 32-byte key and the implicit 64-bit nonce, starting at [nonce]. The nonce
 * `2^64 − 1` is never used; at it, encrypting and decrypting fail with [NoiseFailure.NONCE_EXHAUSTED].
 */
public class CipherState(
    private val crypto: CryptoProvider,
    key: ByteArray,
    private var nonce: ULong = 0u,
) {
    private var key = key.copyOf()

    init {
        require(key.size == KEY_LENGTH) { "A Noise cipher key is $KEY_LENGTH bytes" }
    }

    /** `EncryptWithAd`: [plaintext] encrypted at the next nonce, with its tag, authenticating [associatedData]. */
    public fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray = EMPTY,
    ): NoiseResult<ByteArray> =
        if (nonce == MAX_NONCE) {
            NoiseResult.Failed(NoiseFailure.NONCE_EXHAUSTED)
        } else {
            NoiseResult.Ok(seal(associatedData, plaintext))
        }

    /** `DecryptWithAd`: the plaintext of [ciphertext] at the next nonce; the nonce advances only on success. */
    public fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray = EMPTY,
    ): NoiseResult<ByteArray> =
        when {
            nonce == MAX_NONCE -> NoiseResult.Failed(NoiseFailure.NONCE_EXHAUSTED)
            else -> open(associatedData, ciphertext)?.let { NoiseResult.Ok(it) } ?: AUTHENTICATION_FAILED
        }

    /** `Rekey()`: the key becomes the first 32 bytes of `ENCRYPT(k, 2^64 − 1, empty, 32 zeros)`; the nonce stays. */
    public fun rekey() {
        val next = crypto.chaCha20Poly1305Seal(key, nonceBytes(MAX_NONCE), ByteArray(KEY_LENGTH), EMPTY)
        destroy()
        key = next.copyOf(KEY_LENGTH)
    }

    internal fun seal(
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray = crypto.chaCha20Poly1305Seal(key, nonceBytes(nonce), plaintext, associatedData).also { nonce++ }

    internal fun open(
        associatedData: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray? =
        when (val result = crypto.chaCha20Poly1305Open(key, nonceBytes(nonce), ciphertext, associatedData)) {
            is CryptoResult.Ok -> result.value.also { nonce++ }
            is CryptoResult.Failed -> null
        }

    internal fun destroy() {
        key.fill(0)
    }

    /** ChaChaPoly's 96-bit nonce: 32 zero bits, then [n] little-endian. */
    private fun nonceBytes(n: ULong): ByteArray =
        ByteArray(NONCE_LENGTH) {
            if (it < NONCE_PREFIX) 0 else (n shr (Byte.SIZE_BITS * (it - NONCE_PREFIX))).toByte()
        }
}

/** Noise's `SymmetricState`: the chaining key, the handshake hash and the current cipher state. */
private class SymmetricState(
    private val crypto: CryptoProvider,
    protocolName: String,
) {
    var hash: ByteArray
    private var chainingKey: ByteArray
    private var cipher: CipherState? = null

    init {
        val name = protocolName.encodeToByteArray()
        hash = if (name.size <= HASH_LENGTH) name.copyOf(HASH_LENGTH) else crypto.sha256(name)
        chainingKey = hash.copyOf()
    }

    fun mixHash(data: ByteArray) {
        hash = crypto.sha256(hash + data)
    }

    fun mixKey(input: ByteArray) {
        val (chaining, key) = hkdf(input, 2)
        chainingKey = chaining
        replaceCipher(key)
    }

    fun mixKeyAndHash(input: ByteArray) {
        val (chaining, hashInput, key) = hkdf(input, THREE_OUTPUTS)
        chainingKey = chaining
        mixHash(hashInput)
        replaceCipher(key)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray =
        cipher.let { if (it == null) plaintext else it.seal(hash, plaintext) }.also(::mixHash)

    /** The plaintext, or `null` if the tag doesn't verify. */
    fun decryptAndHash(ciphertext: ByteArray): ByteArray? {
        val plaintext = cipher.let { if (it == null) ciphertext else it.open(hash, ciphertext) }
        plaintext?.let { mixHash(ciphertext) }
        return plaintext
    }

    /** The initiator's sending and receiving cipher states. */
    fun split(): Pair<CipherState, CipherState> {
        val (first, second) = hkdf(EMPTY, 2)
        return CipherState(crypto, first) to CipherState(crypto, second)
    }

    fun destroy() {
        cipher?.destroy()
        chainingKey.fill(0)
    }

    private fun replaceCipher(key: ByteArray) {
        cipher?.destroy()
        cipher = CipherState(crypto, key)
    }

    /** Noise's `HKDF(ck, input, outputs)`: HKDF-SHA-256 with salt `ck` and empty `info`, split in 32-byte outputs. */
    private fun hkdf(
        input: ByteArray,
        outputs: Int,
    ): List<ByteArray> {
        val okm = crypto.hkdfSha256Expand(crypto.hkdfSha256Extract(chainingKey, input), EMPTY, HASH_LENGTH * outputs)
        return List(outputs) { okm.copyOfRange(HASH_LENGTH * it, HASH_LENGTH * (it + 1)) }
    }
}

/**
 * One side of a Noise handshake (`HandshakeState`). Call [writeMessage] and [readMessage] in pattern
 * order, then [split] once [isComplete]. Any failure closes the handshake: its keys are erased and
 * every later call fails with [NoiseFailure.CLOSED], as does every call after [split].
 *
 * @param prologue bytes both sides must agree on; they are hashed before the first message.
 */
public class NoiseHandshake(
    private val crypto: CryptoProvider,
    private val pattern: NoisePattern,
    role: NoiseRole,
    prologue: ByteArray,
    keys: NoiseKeys,
) {
    private val initiator = role == NoiseRole.INITIATOR
    private val static = keys.static
    private val ephemeral = keys.ephemeral ?: crypto.x25519KeyPair()
    private val psk = keys.psk ?: EMPTY
    private val symmetric = SymmetricState(crypto, pattern.protocolName)
    private var remoteEphemeral = EMPTY
    private var remoteStatic = EMPTY
    private var index = 0
    private var closed = false

    init {
        require(pattern.usesPsk == (keys.psk != null)) { "${pattern.protocolName} needs a psk exactly if it has one" }
        symmetric.mixHash(prologue)
    }

    /** Whether every handshake message has been written or read, so [split] may follow. */
    public val isComplete: Boolean get() = !closed && index == pattern.messages.size

    /** The next handshake message, carrying [payload]: encrypted once the pattern has mixed in a key. */
    public fun writeMessage(payload: ByteArray): NoiseResult<ByteArray> =
        turn(writing = true) {
            val parts = mutableListOf<ByteArray>()
            val failure = pattern.messages[index].firstNotNullOfOrNull { write(it, parts) }
            if (failure == null) {
                parts += symmetric.encryptAndHash(payload)
                index++
                NoiseResult.Ok(parts.reduce(ByteArray::plus))
            } else {
                close(failure)
            }
        }

    /** The payload of the peer's next handshake [message]. */
    public fun readMessage(message: ByteArray): NoiseResult<ByteArray> =
        turn(writing = false) {
            val reader = Reader(message)
            val failure = pattern.messages[index].firstNotNullOfOrNull { read(it, reader) }
            val payload = if (failure == null) symmetric.decryptAndHash(reader.rest()) else null
            when {
                failure != null -> close(failure)
                payload == null -> close(NoiseFailure.AUTHENTICATION_FAILED)
                else -> NoiseResult.Ok(payload).also { index++ }
            }
        }

    /** The transport cipher states of a complete handshake (`Split()`); the handshake closes. */
    public fun split(): NoiseResult<NoiseTransport> =
        when {
            closed -> {
                NoiseResult.Failed(NoiseFailure.CLOSED)
            }

            !isComplete -> {
                close(NoiseFailure.OUT_OF_ORDER)
            }

            else -> {
                val (first, second) = symmetric.split()
                val (sending, receiving) = if (initiator) first to second else second to first
                val transport = NoiseTransport(symmetric.hash.copyOf(), remoteStatic, sending, receiving)
                close(NoiseFailure.CLOSED)
                NoiseResult.Ok(transport)
            }
        }

    private fun <T> turn(
        writing: Boolean,
        step: () -> NoiseResult<T>,
    ): NoiseResult<T> =
        when {
            closed -> {
                NoiseResult.Failed(NoiseFailure.CLOSED)
            }

            index == pattern.messages.size || (index % 2 == 0) != (writing == initiator) -> {
                close(NoiseFailure.OUT_OF_ORDER)
            }

            else -> {
                step()
            }
        }

    private fun write(
        token: NoiseToken,
        parts: MutableList<ByteArray>,
    ): NoiseFailure? =
        when (token) {
            NoiseToken.E -> {
                parts += ephemeral.publicKey
                mixEphemeral(ephemeral.publicKey)
            }

            NoiseToken.S -> {
                parts += symmetric.encryptAndHash(static.publicKey)
                null
            }

            else -> {
                mix(token)
            }
        }

    private fun read(
        token: NoiseToken,
        reader: Reader,
    ): NoiseFailure? =
        when (token) {
            NoiseToken.E -> {
                val key = reader.take(KEY_LENGTH)
                if (key == null) {
                    NoiseFailure.MESSAGE_TOO_SHORT
                } else {
                    remoteEphemeral = key
                    mixEphemeral(key)
                }
            }

            NoiseToken.S -> {
                // Every supported pattern sends `s` after a DH, so it is always encrypted.
                val encrypted = reader.take(KEY_LENGTH + TAG_LENGTH)
                val key = encrypted?.let(symmetric::decryptAndHash)
                remoteStatic = key ?: EMPTY
                when {
                    encrypted == null -> NoiseFailure.MESSAGE_TOO_SHORT
                    key == null -> NoiseFailure.AUTHENTICATION_FAILED
                    else -> null
                }
            }

            else -> {
                mix(token)
            }
        }

    /** The `e` token's hashing; with a psk in the pattern, `e` is also mixed into the key. */
    private fun mixEphemeral(publicKey: ByteArray): NoiseFailure? {
        symmetric.mixHash(publicKey)
        if (pattern.usesPsk) symmetric.mixKey(publicKey)
        return null
    }

    /** A DH token or `psk`. */
    private fun mix(token: NoiseToken): NoiseFailure? =
        when (token) {
            NoiseToken.PSK -> {
                symmetric.mixKeyAndHash(psk)
                null
            }

            NoiseToken.EE -> {
                dh(ephemeral, remoteEphemeral)
            }

            NoiseToken.ES -> {
                if (initiator) dh(ephemeral, remoteStatic) else dh(static, remoteEphemeral)
            }

            else -> {
                if (initiator) dh(static, remoteEphemeral) else dh(ephemeral, remoteStatic)
            }
        }

    private fun dh(
        local: KeyPair,
        remote: ByteArray,
    ): NoiseFailure? =
        when (val shared = crypto.x25519(local.secretKey, remote)) {
            is CryptoResult.Ok -> {
                symmetric.mixKey(shared.value)
                null
            }

            is CryptoResult.Failed -> {
                NoiseFailure.INVALID_PUBLIC_KEY
            }
        }

    private fun close(failure: NoiseFailure): NoiseResult.Failed {
        closed = true
        symmetric.destroy()
        return NoiseResult.Failed(failure)
    }

    private class Reader(
        private val message: ByteArray,
    ) {
        private var at = 0

        fun take(count: Int): ByteArray? {
            val end = at + count
            return if (end > message.size) null else message.copyOfRange(at, end).also { at = end }
        }

        fun rest(): ByteArray = message.copyOfRange(at, message.size)
    }
}

/**
 * The two cipher states of a finished handshake, with its [handshakeHash] and the peer's
 * [remoteStatic] key. Transport messages carry no associated data. A failure closes both directions;
 * every later call fails with [NoiseFailure.CLOSED].
 */
public class NoiseTransport(
    public val handshakeHash: ByteArray,
    public val remoteStatic: ByteArray,
    private val sending: CipherState,
    private val receiving: CipherState,
) {
    private var closed = false

    /** [plaintext] as the next transport message. */
    public fun encrypt(plaintext: ByteArray): NoiseResult<ByteArray> = guard { sending.encrypt(plaintext) }

    /** The plaintext of the peer's next transport message. */
    public fun decrypt(ciphertext: ByteArray): NoiseResult<ByteArray> = guard { receiving.decrypt(ciphertext) }

    /** `Rekey()` on the sending cipher state, after sending the record that announces it. */
    public fun rekeySending(): NoiseResult<Unit> = guard { NoiseResult.Ok(sending.rekey()) }

    /** `Rekey()` on the receiving cipher state, after processing the record that announces it. */
    public fun rekeyReceiving(): NoiseResult<Unit> = guard { NoiseResult.Ok(receiving.rekey()) }

    private fun <T> guard(step: () -> NoiseResult<T>): NoiseResult<T> =
        if (closed) {
            NoiseResult.Failed(NoiseFailure.CLOSED)
        } else {
            step().also {
                if (it is NoiseResult.Failed) {
                    closed = true
                    sending.destroy()
                    receiving.destroy()
                }
            }
        }
}

private const val KEY_LENGTH = 32
private const val HASH_LENGTH = 32
private const val TAG_LENGTH = 16
private const val NONCE_LENGTH = 12
private const val NONCE_PREFIX = 4
private const val MAX_NONCE = ULong.MAX_VALUE
private const val THREE_OUTPUTS = 3
private val AUTHENTICATION_FAILED = NoiseResult.Failed(NoiseFailure.AUTHENTICATION_FAILED)
private val EMPTY = ByteArray(0)
