package io.meshlink.util

/**
 * Wrapper around [ByteArray] that provides value-based [equals] and [hashCode],
 * making it safe for use as a map key or set element.
 *
 * Kotlin's [ByteArray] uses identity-based equality — two arrays with identical
 * content are NOT considered equal. This wrapper fixes that.
 */
internal class ByteArrayKey(val bytes: ByteArray) {

    override fun equals(other: Any?): Boolean =
        other is ByteArrayKey && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = bytes.toHex()
}

internal fun ByteArray.toKey(): ByteArrayKey = ByteArrayKey(this)
