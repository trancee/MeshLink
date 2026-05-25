package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

internal fun recordProjectedPeerTrustReset(
    stateStore: ReferenceControllerStateStore,
    peerId: String,
    result: ForgetPeerResult,
): Unit {
    val trustState =
        if (result == ForgetPeerResult.Forgotten) {
            PeerTrustState.FORGOTTEN
        } else {
            PeerTrustState.UNKNOWN
        }
    stateStore.updatePeers { peers ->
        peers.map { peer ->
            if (peer.peerId == peerId) {
                peer.copy(trustState = trustState)
            } else {
                peer
            }
        }
    }
    stateStore.appendEvent(
        ReferenceTimelineEvent(
            family = TimelineFamily.PEER,
            severity = TimelineSeverity.INFO,
            title = "Peer trust reset",
            detail = "forgetPeer(${redactedSuffix(peerId)}) -> $result",
            peerSuffix = redactedSuffix(peerId),
        )
    )
}

internal fun recordProjectedPeerTrustResetFailure(
    stateStore: ReferenceControllerStateStore,
    peerId: String,
    error: Throwable,
): Unit {
    stateStore.appendEvent(
        ReferenceTimelineEvent(
            family = TimelineFamily.PEER,
            severity = TimelineSeverity.ERROR,
            title = "Peer trust reset failed",
            detail = error.message ?: error.toString(),
            peerSuffix = redactedSuffix(peerId),
        )
    )
}
