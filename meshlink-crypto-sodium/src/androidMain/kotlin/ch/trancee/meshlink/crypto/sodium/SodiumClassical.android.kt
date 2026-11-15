package ch.trancee.meshlink.crypto.sodium

internal actual fun sodiumEd25519PublicKey(seed: ByteArray): ByteArray = SodiumClassicalJni.ed25519PublicKey(seed)

internal actual fun sodiumEd25519Sign(
    seed: ByteArray,
    message: ByteArray,
): ByteArray = SodiumClassicalJni.ed25519Sign(seed, message)

internal actual fun sodiumEd25519Verify(
    publicKey: ByteArray,
    message: ByteArray,
    signature: ByteArray,
): Boolean = SodiumClassicalJni.ed25519Verify(publicKey, message, signature)

internal actual fun sodiumX25519PublicKey(key: ByteArray): ByteArray = SodiumClassicalJni.x25519PublicKey(key)

internal actual fun sodiumX25519(
    secretKey: ByteArray,
    publicKey: ByteArray,
): ByteArray? = SodiumClassicalJni.x25519(secretKey, publicKey)

internal actual fun sodiumChaCha20Poly1305Seal(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    associatedData: ByteArray,
): ByteArray = SodiumClassicalJni.chaCha20Poly1305Seal(key, nonce, plaintext, associatedData)

internal actual fun sodiumChaCha20Poly1305Open(
    key: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
    associatedData: ByteArray,
): ByteArray? = SodiumClassicalJni.chaCha20Poly1305Open(key, nonce, ciphertext, associatedData)

/**
 * JNI bindings to the Ed25519, X25519 and ChaCha20-Poly1305 natives of `libmeshlink_sodium.so`
 * (`native/meshlink_sodium_jni.c`), whose `JNI_OnLoad` initializes libsodium and registers them. The
 * members are public so their JVM names stay unmangled; the consumer R8 rules keep this class and its
 * native methods. A nullable result is `null` when libsodium refuses bytes a peer controls.
 */
internal object SodiumClassicalJni {
    init {
        System.loadLibrary("meshlink_sodium")
    }

    external fun ed25519PublicKey(seed: ByteArray): ByteArray

    external fun ed25519Sign(
        seed: ByteArray,
        message: ByteArray,
    ): ByteArray

    external fun ed25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean

    external fun x25519PublicKey(secretKey: ByteArray): ByteArray

    external fun x25519(
        secretKey: ByteArray,
        publicKey: ByteArray,
    ): ByteArray?

    external fun chaCha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    external fun chaCha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray?
}
