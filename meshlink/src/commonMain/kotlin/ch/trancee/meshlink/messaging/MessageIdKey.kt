package ch.trancee.meshlink.messaging

/**
 * ByteArray wrapper suitable for use as a [HashMap] key with correct content-based equality.
 *
 * Plain class (not data class) so that the hand-written [equals] can be fully covered by tests:
 * a data class would generate a synthetic equals whose `!is MessageIdKey` branch is unreachable
 * via the public API (see MEM098). With a hand-written override, a test can pass any non-
 * MessageIdKey value to cover that branch.
 */
class MessageIdKey(val bytes: ByteArray) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageIdKey) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}
