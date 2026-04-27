package ch.trancee.meshlink.api

/**
 * Details about a discovered mesh peer.
 *
 * [id] equality uses [ByteArray.contentEquals] so two [PeerDetail] instances for the same peer
 * compare equal regardless of object identity.
 *
 * @param id Key hash (12 bytes) uniquely identifying this peer.
 * @param staticPublicKey The peer's X25519 static public key (32 bytes), as pinned in the trust
 *   store.
 * @param fingerprint Human-readable hex string of [id] (24 hex chars).
 * @param isConnected Whether this peer is currently connected.
 * @param lastSeenTimestampMillis Monotonic timestamp (ms) of the most recent contact with this
 *   peer.
 * @param trustMode The current trust disposition toward this peer.
 */
public data class PeerDetail(
    val id: ByteArray,
    val staticPublicKey: ByteArray,
    val fingerprint: String,
    val isConnected: Boolean,
    val lastSeenTimestampMillis: Long,
    val trustMode: TrustMode,
) {
    override fun equals(other: Any?): Boolean = other is PeerDetail && id.contentEquals(other.id)

    override fun hashCode(): Int = id.contentHashCode()

    override fun toString(): String =
        "PeerDetail(fingerprint=$fingerprint, isConnected=$isConnected, " +
            "lastSeenTimestampMillis=$lastSeenTimestampMillis, trustMode=$trustMode)"
}
