package ch.trancee.meshlink.util

/**
 * ByteArray wrapper suitable for use as a [HashMap] key with correct content-based equality.
 *
 * Eliminates the autoboxing overhead of `ByteArray.asList()` which wraps each byte as a
 * `java.lang.Byte` on JVM. A 12-byte peer ID creates 12 boxed objects per `asList()` call;
 * [ByteArrayKey] uses `contentHashCode()`/`contentEquals()` for O(1) native array operations.
 *
 * Plain class (not data class) so that the hand-written [equals] can be fully covered by tests.
 */
internal class ByteArrayKey(val bytes: ByteArray) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ByteArrayKey) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ByteArrayKey(${bytes.size} bytes)"
}
