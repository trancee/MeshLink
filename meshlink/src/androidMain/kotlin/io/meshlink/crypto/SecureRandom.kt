@file:JvmName("SecureRandomAndroid")

package io.meshlink.crypto

actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes
}
