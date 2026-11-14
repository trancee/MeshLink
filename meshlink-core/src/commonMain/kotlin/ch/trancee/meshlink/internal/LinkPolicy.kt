package ch.trancee.meshlink.internal

/**
 * The `unsecured` link handshake rules (spec "Version selection", "Unsecured link", "Keep one link
 * per neighbor") for a node of the mesh with [meshIdDigest] whose NodeId is [nodeId].
 */
public class LinkPolicy(
    private val meshIdDigest: ByteArray,
    private val nodeId: ByteArray,
    /** The protocol versions this node speaks, strictly descending. */
    public val versions: List<Int> = listOf(PROTOCOL_VERSION),
) {
    /** The highest version both this node and [record] list, or `null`: the central then closes the bearer. */
    public fun selectVersion(record: CapabilityRecord): Int? = versions.firstOrNull { it in record.versions }

    /** This node's `PLAIN_HELLO` for [version]. */
    public fun hello(version: Int): LinkFrame.PlainHello = LinkFrame.PlainHello(version, meshIdDigest, nodeId)

    /**
     * Checks a peer's [hello]. A peripheral passes `selected = null` and needs a version it speaks;
     * a central passes the version it [selected] and needs it echoed. Returns the reason to close
     * with, or `null` to accept the peer.
     */
    public fun checkHello(
        hello: LinkFrame.PlainHello,
        selected: Int?,
    ): CloseReason? =
        when {
            selected == null && hello.version !in versions -> CloseReason.UNSUPPORTED_VERSION
            selected != null && hello.version != selected -> CloseReason.PROTOCOL_ERROR
            !hello.meshIdDigest.contentEquals(meshIdDigest) -> CloseReason.WRONG_MESH
            hello.nodeId.contentEquals(nodeId) -> CloseReason.PROTOCOL_ERROR
            else -> null
        }

    public companion object {
        /** The protocol version of this implementation (spec `protocol-version`). */
        public const val PROTOCOL_VERSION: Int = 1

        /** Orders NodeIds bytewise as unsigned bytes; the link whose central is lower is kept. */
        public fun compareNodeIds(
            a: ByteArray,
            b: ByteArray,
        ): Int = a.indices.firstOrNull { a[it] != b[it] }?.let { a.u8(it) - b.u8(it) } ?: 0
    }
}
