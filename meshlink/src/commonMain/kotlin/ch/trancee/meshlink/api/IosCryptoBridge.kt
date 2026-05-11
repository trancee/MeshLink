package ch.trancee.meshlink.api

/**
 * Raw 32-byte key material returned by an iOS-native cryptography bridge.
 *
 * Both Curve25519 key agreement keys and Ed25519 signing keys use 32-byte
 * private and public key encodings in MeshLink's current iOS bridge contract.
 */
public class IosCryptoRawKeyPair public constructor(
    public val privateKey: ByteArray,
    public val publicKey: ByteArray,
) {
    init {
        require(privateKey.size == KEY_SIZE_BYTES) { "privateKey must be 32 bytes" }
        require(publicKey.size == KEY_SIZE_BYTES) { "publicKey must be 32 bytes" }
    }

    private companion object {
        private const val KEY_SIZE_BYTES: Int = 32
    }
}

/**
 * Registers iOS-native cryptography callbacks for MeshLink.
 *
 * Install these callbacks during iOS application startup before any future
 * MeshLink runtime path needs real cryptography. The callbacks must be backed
 * by Apple-native implementations, such as CryptoKit, and must follow the
 * MeshLink contract for raw key material and AEAD output:
 *
 * - X25519 keys use 32-byte raw private/public encodings
 * - Ed25519 keys use 32-byte raw private/public encodings
 * - Ed25519 signatures use the 64-byte raw signature format
 * - ChaCha20-Poly1305 returns `ciphertext || tag`
 */
public object IosCryptoBridge {
    public fun install(
        randomBytes: (Int) -> ByteArray,
        sha256: (ByteArray) -> ByteArray,
        hmacSha256: (ByteArray, ByteArray) -> ByteArray,
        generateX25519KeyPair: () -> IosCryptoRawKeyPair,
        generateEd25519KeyPair: () -> IosCryptoRawKeyPair,
        x25519: (ByteArray, ByteArray) -> ByteArray,
        ed25519Sign: (ByteArray, ByteArray) -> ByteArray,
        ed25519Verify: (ByteArray, ByteArray, ByteArray) -> Boolean,
        chacha20Poly1305Seal: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
        chacha20Poly1305Open: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
    ): Unit {
        IosCryptoBridgeRegistry.install(
            IosCryptoCallbacks(
                randomBytes = randomBytes,
                sha256 = sha256,
                hmacSha256 = hmacSha256,
                generateX25519KeyPair = generateX25519KeyPair,
                generateEd25519KeyPair = generateEd25519KeyPair,
                x25519 = x25519,
                ed25519Sign = ed25519Sign,
                ed25519Verify = ed25519Verify,
                chacha20Poly1305Seal = chacha20Poly1305Seal,
                chacha20Poly1305Open = chacha20Poly1305Open,
            ),
        )
    }
}

internal class IosCryptoCallbacks internal constructor(
    internal val randomBytes: (Int) -> ByteArray,
    internal val sha256: (ByteArray) -> ByteArray,
    internal val hmacSha256: (ByteArray, ByteArray) -> ByteArray,
    internal val generateX25519KeyPair: () -> IosCryptoRawKeyPair,
    internal val generateEd25519KeyPair: () -> IosCryptoRawKeyPair,
    internal val x25519: (ByteArray, ByteArray) -> ByteArray,
    internal val ed25519Sign: (ByteArray, ByteArray) -> ByteArray,
    internal val ed25519Verify: (ByteArray, ByteArray, ByteArray) -> Boolean,
    internal val chacha20Poly1305Seal: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
    internal val chacha20Poly1305Open: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
)

internal object IosCryptoBridgeRegistry {
    private var callbacks: IosCryptoCallbacks? = null

    internal fun install(callbacks: IosCryptoCallbacks): Unit {
        this.callbacks = callbacks
    }

    internal fun clear(): Unit {
        callbacks = null
    }

    internal fun requireCallbacks(): IosCryptoCallbacks {
        return callbacks ?: throw MeshLinkException.PlatformFailure(
            message = "iOS crypto bridge is not installed. Call IosCryptoBridge.install(...) during app startup.",
        )
    }
}
