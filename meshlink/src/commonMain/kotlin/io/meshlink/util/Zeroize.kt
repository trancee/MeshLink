package io.meshlink.util

/**
 * Overwrites a byte array with zeros to reduce the window during which
 * sensitive key material is readable from process memory.
 *
 * Note: On JVM, the garbage collector may retain copies of the original
 * array contents in freed heap regions. This is a best-effort mitigation
 * that still significantly reduces the practical attack window compared
 * to leaving key material indefinitely in live objects.
 */
fun zeroize(data: ByteArray) {
    data.fill(0)
}
