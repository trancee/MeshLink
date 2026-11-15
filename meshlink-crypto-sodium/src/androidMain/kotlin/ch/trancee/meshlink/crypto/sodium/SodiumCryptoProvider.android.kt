package ch.trancee.meshlink.crypto.sodium

internal actual fun sodiumRandomBytes(count: Int): ByteArray = SodiumRandomJni.randomBytes(count)

/**
 * JNI binding to the random-bytes native of `libmeshlink_sodium.so` (`native/meshlink_sodium_jni.c`),
 * whose `JNI_OnLoad` initializes libsodium and registers it. The member is public so its JVM name
 * stays unmangled; the consumer R8 rules keep this class and its native method.
 */
internal object SodiumRandomJni {
    init {
        System.loadLibrary("meshlink_sodium")
    }

    external fun randomBytes(count: Int): ByteArray
}
