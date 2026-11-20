package ch.trancee.meshlink

/**
 * A node's identifier in the mesh: 32 bytes derived from its installation key (spec "NodeId").
 * Two NodeIds are equal when their bytes are; [toString] prints them as 64 lowercase hex digits.
 */
public class NodeId internal constructor(
    bytes: ByteArray,
) {
    private val bytes = bytes.copyOf()

    init {
        require(bytes.size == NODE_ID_BYTES) { "A NodeId is $NODE_ID_BYTES bytes, not ${bytes.size}" }
    }

    /** A copy of the 32 bytes. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    /** `true` if [other] is a [NodeId] with the same 32 bytes. */
    override fun equals(other: Any?): Boolean = other is NodeId && other.bytes.contentEquals(bytes)

    /** A hash of the 32 bytes, consistent with [equals]. */
    override fun hashCode(): Int = bytes.contentHashCode()

    /** The 32 bytes as 64 lowercase hex digits. */
    override fun toString(): String = bytes.toHexString()
}

private const val NODE_ID_BYTES = 32
