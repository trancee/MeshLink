package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerConnectionState
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Shared controller abstraction for the reference app. */
public interface ReferenceMeshLinkController {
    public val snapshot: StateFlow<ReferenceControllerSnapshot>

    public suspend fun start(): Unit

    public suspend fun pause(): Unit

    public suspend fun resume(): Unit

    public suspend fun stop(): Unit

    public suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority = DeliveryPriority.NORMAL,
    ): Unit

    public suspend fun forgetPeer(peerId: String): Unit

    public suspend fun close(): Unit = Unit
}

@Serializable
public data class ReferenceControllerSnapshot(
    public val session: ReferenceSession,
    public val peers: List<PeerSnapshot>,
    public val timeline: List<TimelineEntry>,
    public val activePowerModeLabel: String,
)

internal fun PeerConnectionState.toSnapshotState(): PeerConnectionSnapshotState {
    return when (this) {
        PeerConnectionState.CONNECTED -> PeerConnectionSnapshotState.CONNECTED
        PeerConnectionState.DISCONNECTED -> PeerConnectionSnapshotState.DISCONNECTED
    }
}

internal fun redactedSuffix(peerId: String): String {
    return peerId.takeLast(REDACTED_PEER_SUFFIX_LENGTH)
}

private const val REDACTED_PEER_SUFFIX_LENGTH: Int = 6
