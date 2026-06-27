package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

internal fun applyPeerEvent(
    stateStore: ReferenceControllerStateStore,
    nowProvider: () -> Long,
    event: PeerEvent,
): Unit {
    when (event) {
        is PeerEvent.Found ->
            handlePeerFound(stateStore = stateStore, nowProvider = nowProvider, event = event)
        is PeerEvent.StateChanged ->
            handlePeerStateChanged(
                stateStore = stateStore,
                nowProvider = nowProvider,
                event = event,
            )
        is PeerEvent.Lost ->
            handlePeerLost(stateStore = stateStore, nowProvider = nowProvider, event = event)
    }
}

internal fun updatePeerTrustState(
    stateStore: ReferenceControllerStateStore,
    peerSuffix: String?,
    trustState: PeerTrustState,
): Unit {
    if (peerSuffix == null) {
        return
    }

    stateStore.updatePeers { peers ->
        peers.map { peer ->
            if (peer.peerSuffix == peerSuffix) {
                peer.copy(trustState = trustState)
            } else {
                peer
            }
        }
    }
}

private fun handlePeerFound(
    stateStore: ReferenceControllerStateStore,
    nowProvider: () -> Long,
    event: PeerEvent.Found,
): Unit {
    val currentSelectedPeerId = stateStore.currentSnapshot.session.selectedPeerId
    val shouldAutoSelectFirstPeer = currentSelectedPeerId == null
    if (!stateStore.shouldTrackPeer(event.peerId.value) && !shouldAutoSelectFirstPeer) {
        return
    }
    val peerSnapshot = discoveredPeerSnapshot(event = event, nowProvider = nowProvider)

    stateStore.updatePeers { peers ->
        (peers.filterNot { existing -> existing.peerId == peerSnapshot.peerId } + peerSnapshot)
            .sortedBy { peer -> peer.peerSuffix }
    }
    stateStore.updateSession(
        selectedPeerId = currentSelectedPeerId ?: event.peerId.value,
        lastOutcomeSummary = "Peer found",
    )
    stateStore.appendEvent(
        ReferenceTimelineEvent(
            family = TimelineFamily.PEER,
            severity = TimelineSeverity.SUCCESS,
            title = "Peer found",
            detail = "Discovered ${peerSnapshot.peerSuffix} on the guided path.",
            peerSuffix = peerSnapshot.peerSuffix,
        )
    )
}

private fun discoveredPeerSnapshot(event: PeerEvent.Found, nowProvider: () -> Long): PeerSnapshot {
    return PeerSnapshot(
        peerId = event.peerId.value,
        peerSuffix = redactedSuffix(event.peerId.value),
        trustState = PeerTrustState.UNKNOWN,
        connectionState = event.state.toSnapshotState(),
        lastSeenAtEpochMillis = nowProvider(),
        capabilityNotes = listOf("Discovered by live MeshLink flow"),
    )
}
