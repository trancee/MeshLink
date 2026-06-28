package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

internal fun handlePeerStateChanged(
    stateStore: ReferenceControllerStateStore,
    nowProvider: () -> Long,
    event: PeerEvent.StateChanged,
): Unit {
    if (!stateStore.shouldTrackPeer(event.peerId.value)) {
        return
    }
    updatePeerConnectionState(
        stateStore = stateStore,
        peerId = event.peerId.value,
        connectionState = event.state.toSnapshotState(),
        lastSeenAtEpochMillis = nowProvider(),
    )
    stateStore.appendEvent(
        ReferenceTimelineEvent(
            family = TimelineFamily.PEER,
            severity = TimelineSeverity.INFO,
            title = "Peer state changed",
            detail = "${redactedSuffix(event.peerId.value)} -> ${event.state}",
            peerSuffix = redactedSuffix(event.peerId.value),
        )
    )
}

internal fun handlePeerLost(
    stateStore: ReferenceControllerStateStore,
    nowProvider: () -> Long,
    event: PeerEvent.Lost,
): Unit {
    if (!stateStore.shouldTrackPeer(event.peerId.value)) {
        return
    }
    updatePeerConnectionState(
        stateStore = stateStore,
        peerId = event.peerId.value,
        connectionState = PeerConnectionSnapshotState.LOST,
        lastSeenAtEpochMillis = nowProvider(),
    )
    stateStore.appendEvent(
        ReferenceTimelineEvent(
            family = TimelineFamily.PEER,
            severity = TimelineSeverity.WARNING,
            title = "Peer lost",
            detail = "${redactedSuffix(event.peerId.value)} left the current guided view.",
            peerSuffix = redactedSuffix(event.peerId.value),
        )
    )
}

private fun updatePeerConnectionState(
    stateStore: ReferenceControllerStateStore,
    peerId: String,
    connectionState: PeerConnectionSnapshotState,
    lastSeenAtEpochMillis: Long,
): Unit {
    stateStore.updatePeers { peers ->
        peers.map { peer ->
            if (peer.peerId == peerId) {
                peer.copy(
                    connectionState = connectionState,
                    lastSeenAtEpochMillis = lastSeenAtEpochMillis,
                )
            } else {
                peer
            }
        }
    }
}
