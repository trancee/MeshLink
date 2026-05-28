package ch.trancee.meshlink.api.apple

import ch.trancee.meshlink.api.MeshLinkException

/**
 * Raw 32-byte key material returned by an iOS-native cryptography bridge.
 *
 * Both Curve25519 key agreement keys and Ed25519 signing keys use 32-byte private and public key
 * encodings in MeshLink's current iOS bridge contract.
 */
public class CryptoRawKeyPair public constructor(privateKey: ByteArray, publicKey: ByteArray) {
    private val privateKeyBytes: ByteArray
    private val publicKeyBytes: ByteArray

    init {
        require(privateKey.size == KEY_SIZE_BYTES) { "privateKey must be 32 bytes" }
        require(publicKey.size == KEY_SIZE_BYTES) { "publicKey must be 32 bytes" }
        privateKeyBytes = privateKey.copyOf()
        publicKeyBytes = publicKey.copyOf()
    }

    /** Returns a defensive copy of the private key bytes. */
    public val privateKey: ByteArray
        get() = copyPrivateKey()

    /** Returns a defensive copy of the public key bytes. */
    public val publicKey: ByteArray
        get() = copyPublicKey()

    internal fun copyPrivateKey(): ByteArray = privateKeyBytes.copyOf()

    internal fun copyPublicKey(): ByteArray = publicKeyBytes.copyOf()

    private companion object {
        private const val KEY_SIZE_BYTES: Int = 32
    }
}

/** Groups the iOS-native hashing callbacks required by MeshLink. */
public class HashCallbacks
public constructor(
    public val sha256: (ByteArray) -> ByteArray,
    public val hmacSha256: (ByteArray, ByteArray) -> ByteArray,
)

/** Groups the iOS-native asymmetric key-generation callbacks required by MeshLink. */
public class KeyGenerationCallbacks
public constructor(
    public val generateX25519KeyPair: () -> CryptoRawKeyPair,
    public val generateEd25519KeyPair: () -> CryptoRawKeyPair,
)

/** Groups the iOS-native Ed25519 signing and verification callbacks required by MeshLink. */
public class Ed25519Callbacks
public constructor(
    public val sign: (ByteArray, ByteArray) -> ByteArray,
    public val verify: (ByteArray, ByteArray, ByteArray) -> Boolean,
)

/** Groups the iOS-native ChaCha20-Poly1305 callbacks required by MeshLink. */
public class ChaCha20Poly1305Callbacks
public constructor(
    public val seal: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
    public val open: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
)

/**
 * Structured iOS-native cryptography callbacks for MeshLink.
 *
 * This groups the required callbacks into smaller related callback families so app-owned iOS code
 * can keep hashing, key generation, signing, and AEAD glue together instead of passing a long flat
 * parameter list at the call site.
 */
public class CryptoCallbacks
public constructor(
    public val randomBytes: (Int) -> ByteArray,
    public val hashes: HashCallbacks,
    public val keyGeneration: KeyGenerationCallbacks,
    public val x25519: (ByteArray, ByteArray) -> ByteArray,
    public val ed25519: Ed25519Callbacks,
    public val chacha20Poly1305: ChaCha20Poly1305Callbacks,
) {
    internal val sha256: (ByteArray) -> ByteArray = hashes.sha256
    internal val hmacSha256: (ByteArray, ByteArray) -> ByteArray = hashes.hmacSha256
    internal val generateX25519KeyPair: () -> CryptoRawKeyPair = keyGeneration.generateX25519KeyPair
    internal val generateEd25519KeyPair: () -> CryptoRawKeyPair =
        keyGeneration.generateEd25519KeyPair
    internal val ed25519Sign: (ByteArray, ByteArray) -> ByteArray = ed25519.sign
    internal val ed25519Verify: (ByteArray, ByteArray, ByteArray) -> Boolean = ed25519.verify
    internal val chacha20Poly1305Seal: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray =
        chacha20Poly1305.seal
    internal val chacha20Poly1305Open: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray =
        chacha20Poly1305.open
}

/**
 * Registers iOS-native cryptography callbacks for MeshLink.
 *
 * Install these callbacks during iOS application startup before any future MeshLink runtime path
 * needs real cryptography. The callbacks must be backed by Apple-native implementations, such as
 * CryptoKit, and must follow the MeshLink contract for raw key material and AEAD output:
 *
 * - X25519 keys use 32-byte raw private/public encodings
 * - Ed25519 keys use 32-byte raw private/public encodings
 * - Ed25519 signatures use the 64-byte raw signature format
 * - ChaCha20-Poly1305 returns `ciphertext || tag`
 */
public object CryptoBridge {
    /** Installs a structured iOS cryptography callback set. */
    public fun install(callbacks: CryptoCallbacks): Unit {
        CryptoBridgeRegistry.install(callbacks)
    }

    @Suppress("LongParameterList")
    public fun install(
        randomBytes: (Int) -> ByteArray,
        sha256: (ByteArray) -> ByteArray,
        hmacSha256: (ByteArray, ByteArray) -> ByteArray,
        generateX25519KeyPair: () -> CryptoRawKeyPair,
        generateEd25519KeyPair: () -> CryptoRawKeyPair,
        x25519: (ByteArray, ByteArray) -> ByteArray,
        ed25519Sign: (ByteArray, ByteArray) -> ByteArray,
        ed25519Verify: (ByteArray, ByteArray, ByteArray) -> Boolean,
        chacha20Poly1305Seal: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
        chacha20Poly1305Open: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
    ): Unit {
        install(
            callbacks =
                CryptoCallbacks(
                    randomBytes = randomBytes,
                    hashes = HashCallbacks(sha256 = sha256, hmacSha256 = hmacSha256),
                    keyGeneration =
                        KeyGenerationCallbacks(
                            generateX25519KeyPair = generateX25519KeyPair,
                            generateEd25519KeyPair = generateEd25519KeyPair,
                        ),
                    x25519 = x25519,
                    ed25519 = Ed25519Callbacks(sign = ed25519Sign, verify = ed25519Verify),
                    chacha20Poly1305 =
                        ChaCha20Poly1305Callbacks(
                            seal = chacha20Poly1305Seal,
                            open = chacha20Poly1305Open,
                        ),
                )
        )
    }
}

internal object CryptoBridgeRegistry {
    private var callbacks: CryptoCallbacks? = null

    internal fun install(callbacks: CryptoCallbacks): Unit {
        this.callbacks = callbacks
    }

    internal fun clear(): Unit {
        callbacks = null
    }

    internal fun requireCallbacks(): CryptoCallbacks {
        return callbacks
            ?: throw MeshLinkException.PlatformFailure(
                message =
                    "iOS crypto bridge is not installed. Call CryptoBridge.install(...) during app startup."
            )
    }
}
