package ch.trancee.meshlink

/**
 * Identifies a node by its installation identity.
 *
 * A regular class rather than a value class so that it exports to Swift as a hashable object.
 * Two instances are equal when their bytes are equal.
 *
 * @sample ch.trancee.meshlink.samples.nodeIdRoundTrip
 */
public class NodeId private constructor(private val bytes: ByteArray) {
    /** Returns the identity as lowercase hexadecimal, suitable for storing in the application. */
    public fun toHex(): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Returns a redacted form (first four hex digits) for logs and diagnostics. */
    public fun redacted(): String = toHex().take(REDACTED_DIGITS) + "…"

    override fun equals(other: Any?): Boolean = other is NodeId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "NodeId(${toHex()})"

    /** Factory functions. */
    public companion object {
        /** Number of bytes in a node identity. */
        public const val SIZE_BYTES: Int = 8
        private const val REDACTED_DIGITS = 4

        /**
         * Parses an identity previously produced by [toHex].
         *
         * @throws MeshLinkException.InvalidNodeId if [hex] is not exactly [SIZE_BYTES] bytes of hexadecimal.
         */
        @Throws(MeshLinkException::class)
        public fun fromHex(hex: String): NodeId {
            if (hex.length != SIZE_BYTES * 2 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) {
                throw MeshLinkException.InvalidNodeId(hex)
            }
            return NodeId(ByteArray(SIZE_BYTES) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        }

        internal fun fromBytes(bytes: ByteArray): NodeId = NodeId(bytes.copyOf())
    }
}

/** Identifies one message; unique per sending node. */
public class MessageId internal constructor(private val value: Long) {
    override fun equals(other: Any?): Boolean = other is MessageId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "MessageId($value)"
}
