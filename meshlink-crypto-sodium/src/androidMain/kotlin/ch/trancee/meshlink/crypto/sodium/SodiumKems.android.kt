package ch.trancee.meshlink.crypto.sodium

internal actual fun sodiumMlKem768KeyPair(seed: ByteArray): ByteArray = SodiumKemsJni.mlKem768KeyPair(seed)

internal actual fun sodiumMlKem768Encapsulate(
    publicKey: ByteArray,
    seed: ByteArray,
): ByteArray? = SodiumKemsJni.mlKem768Encapsulate(publicKey, seed)

internal actual fun sodiumMlKem768Decapsulate(
    secretKey: ByteArray,
    ciphertext: ByteArray,
): ByteArray = SodiumKemsJni.mlKem768Decapsulate(secretKey, ciphertext)

internal actual fun sodiumXWingPublicKey(seed: ByteArray): ByteArray = SodiumKemsJni.xWingPublicKey(seed)

internal actual fun sodiumXWingEncapsulate(
    publicKey: ByteArray,
    seed: ByteArray,
): ByteArray? = SodiumKemsJni.xWingEncapsulate(publicKey, seed)

internal actual fun sodiumXWingDecapsulate(
    secretKey: ByteArray,
    ciphertext: ByteArray,
): ByteArray? = SodiumKemsJni.xWingDecapsulate(secretKey, ciphertext)

/**
 * JNI bindings to the ML-KEM-768 and X-Wing natives of `libmeshlink_sodium.so`
 * (`native/meshlink_sodium_jni.c`), whose `JNI_OnLoad` initializes libsodium and registers them. The
 * members are public so their JVM names stay unmangled; the consumer R8 rules keep this class and its
 * native methods. A nullable result is `null` when libsodium refuses bytes a peer controls.
 */
internal object SodiumKemsJni {
    init {
        System.loadLibrary("meshlink_sodium")
    }

    external fun mlKem768KeyPair(seed: ByteArray): ByteArray

    external fun mlKem768Encapsulate(
        publicKey: ByteArray,
        seed: ByteArray,
    ): ByteArray?

    external fun mlKem768Decapsulate(
        secretKey: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray

    external fun xWingPublicKey(seed: ByteArray): ByteArray

    external fun xWingEncapsulate(
        publicKey: ByteArray,
        seed: ByteArray,
    ): ByteArray?

    external fun xWingDecapsulate(
        secretKey: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray?
}
