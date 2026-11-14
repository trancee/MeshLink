package ch.trancee.meshlink.crypto.sodium

internal actual fun sodiumBlake2b(
    outLength: Int,
    input: ByteArray,
    key: ByteArray?,
): ByteArray = SodiumJni.blake2b(outLength, input, key)

internal actual fun sodiumEd25519PublicKey(seed: ByteArray): ByteArray = SodiumJni.ed25519PublicKey(seed)

internal actual fun sodiumRandomBytes(count: Int): ByteArray = SodiumJni.randomBytes(count)

/**
 * JNI bindings to `libmeshlink_sodium.so` (`native/meshlink_sodium_jni.c`), whose `JNI_OnLoad`
 * initializes libsodium and registers these functions. The members are public so their JVM names
 * stay unmangled; the consumer R8 rules keep this class and its native methods.
 */
internal object SodiumJni {
    init {
        System.loadLibrary("meshlink_sodium")
    }

    external fun blake2b(
        outLength: Int,
        input: ByteArray,
        key: ByteArray?,
    ): ByteArray

    external fun ed25519PublicKey(seed: ByteArray): ByteArray

    external fun randomBytes(count: Int): ByteArray
}
