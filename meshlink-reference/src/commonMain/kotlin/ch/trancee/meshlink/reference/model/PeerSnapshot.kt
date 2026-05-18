package ch.trancee.meshlink.reference.model

import kotlinx.serialization.Serializable

/** Redacted view of one peer for app surfaces and retained artifacts. */
@Serializable
public data class PeerSnapshot(
    public val peerId: String,
    public val peerSuffix: String,
    public val trustState: PeerTrustState,
    public val connectionState: PeerConnectionSnapshotState,
    public val lastSeenAtEpochMillis: Long? = null,
    public val lastDeliveryOutcome: String? = null,
    public val capabilityNotes: List<String> = emptyList(),
)

@Serializable
public enum class PeerTrustState {
    UNKNOWN,
    TRUSTED,
    CHANGED,
    FORGOTTEN,
}

@Serializable
public enum class PeerConnectionSnapshotState {
    CONNECTED,
    DISCONNECTED,
    LOST,
}
