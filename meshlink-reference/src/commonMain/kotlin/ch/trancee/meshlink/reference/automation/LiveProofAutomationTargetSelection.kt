package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.redactedSuffix
import ch.trancee.meshlink.reference.model.TimelineFamily

internal fun shouldAutoSendGuidedHello(
    snapshot: ReferenceControllerSnapshot,
    requiredPeerCount: Int = 1,
    targetPeerIndex: Int = 0,
    targetPeerId: String? = null,
): Boolean {
    return autoSendTargetPeer(
        snapshot = snapshot,
        requiredPeerCount = requiredPeerCount,
        targetPeerIndex = targetPeerIndex,
        targetPeerId = targetPeerId,
    ) != null
}

internal fun autoSendTargetPeer(
    snapshot: ReferenceControllerSnapshot,
    requiredPeerCount: Int,
    targetPeerIndex: Int,
    targetPeerId: String? = null,
): AutoSendTargetPeer? {
    val selectedPeer =
        if (targetPeerId != null) {
            snapshot.peers.firstOrNull { it.peerId == targetPeerId } ?: return null
        } else {
            snapshot.peers
                .takeIf { peers ->
                    targetPeerIndex >= 0 &&
                        peers.size >= requiredPeerCount &&
                        targetPeerIndex < peers.size
                }
                ?.get(targetPeerIndex) ?: return null
        }
    if (!hasAvailableRouteForPeer(snapshot, selectedPeer.peerId)) {
        return null
    }
    return AutoSendTargetPeer(peerId = selectedPeer.peerId, peerSuffix = selectedPeer.peerSuffix)
}

internal fun hasAvailableRouteForPeer(
    snapshot: ReferenceControllerSnapshot,
    peerId: String,
): Boolean {
    return hasAvailableRouteForPeerAfterEntry(
        snapshot = snapshot,
        peerId = peerId,
        boundaryEntryId = null,
    )
}

internal fun hasAvailableRouteForPeerAfterEntry(
    snapshot: ReferenceControllerSnapshot,
    peerId: String,
    boundaryEntryId: String?,
): Boolean {
    var boundaryReached = boundaryEntryId == null
    return snapshot.timeline.any { entry ->
        if (!boundaryReached) {
            boundaryReached = entry.entryId == boundaryEntryId
            return@any false
        }
        if (
            entry.family == TimelineFamily.DIAGNOSTIC && entry.peerSuffix == redactedSuffix(peerId)
        ) {
            if (
                entry.detail.contains("peerId=$peerId") &&
                    entry.detail.contains("routeAvailable=false")
            ) {
                return@any false
            }
            if (
                entry.detail.contains("peerId=$peerId") &&
                    entry.detail.contains("routeAvailable=true")
            ) {
                return@any true
            }
        }
        false
    }
}

internal fun bootstrapTargetPeer(
    snapshot: ReferenceControllerSnapshot,
    targetPeerId: String? = null,
): AutoSendTargetPeer? {
    return null
}

internal data class AutoSendTargetPeer(val peerId: String, val peerSuffix: String)
