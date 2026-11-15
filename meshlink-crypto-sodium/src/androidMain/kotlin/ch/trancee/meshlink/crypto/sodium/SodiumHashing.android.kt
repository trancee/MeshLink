package ch.trancee.meshlink.crypto.sodium

internal actual fun sodiumBlake2b(
    outLength: Int,
    input: ByteArray,
    key: ByteArray?,
): ByteArray = SodiumHashingJni.blake2b(outLength, input, key)

internal actual fun sodiumSha256(input: ByteArray): ByteArray = SodiumHashingJni.sha256(input)

internal actual fun sodiumHkdfSha256Extract(
    salt: ByteArray,
    ikm: ByteArray,
): ByteArray = SodiumHashingJni.hkdfSha256Extract(salt, ikm)

internal actual fun sodiumHkdfSha256Expand(
    prk: ByteArray,
    info: ByteArray,
    length: Int,
): ByteArray = SodiumHashingJni.hkdfSha256Expand(prk, info, length)

/**
 * JNI bindings to the hashing natives of `libmeshlink_sodium.so` (`native/meshlink_sodium_jni.c`),
 * whose `JNI_OnLoad` initializes libsodium and registers them. The members are public so their JVM
 * names stay unmangled; the consumer R8 rules keep this class and its native methods.
 */
internal object SodiumHashingJni {
    init {
        System.loadLibrary("meshlink_sodium")
    }

    external fun blake2b(
        outLength: Int,
        input: ByteArray,
        key: ByteArray?,
    ): ByteArray

    external fun sha256(input: ByteArray): ByteArray

    external fun hkdfSha256Extract(
        salt: ByteArray,
        ikm: ByteArray,
    ): ByteArray

    external fun hkdfSha256Expand(
        prk: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray
}
