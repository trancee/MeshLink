package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState

internal fun shouldRequestLiveProofMeshStart(
    meshStartRequested: Boolean,
    snapshot: ReferenceControllerSnapshot,
    readinessBlockers: List<String>,
): Boolean {
    return !meshStartRequested &&
        !hasMeshEnteredLifecycle(snapshot.session.meshStateLabel) &&
        readinessBlockers.isEmpty()
}

internal fun shouldRequestPauseForPauseResume(
    pauseRequested: Boolean,
    snapshot: ReferenceControllerSnapshot,
    targetPeerId: String,
): Boolean {
    return !pauseRequested &&
        isMeshRunning(snapshot.session.meshStateLabel) &&
        hasAvailableRouteForPeer(snapshot, targetPeerId)
}

internal fun shouldSendAfterPauseResumeRecovery(
    resumeObserved: Boolean,
    snapshot: ReferenceControllerSnapshot,
): Boolean {
    return resumeObserved && isMeshRunning(snapshot.session.meshStateLabel)
}

internal fun shouldRetainPassiveLiveProof(
    retainRequested: Boolean,
    hasTrustEstablished: Boolean,
    hasInboundMessage: Boolean,
): Boolean {
    return !retainRequested && hasTrustEstablished && hasInboundMessage
}

internal fun hasTrustedSelectedPeer(snapshot: ReferenceControllerSnapshot): Boolean {
    val selectedPeerId = snapshot.session.selectedPeerId ?: return false
    return snapshot.peers.any { peer ->
        peer.peerId == selectedPeerId && peer.trustState == PeerTrustState.TRUSTED
    }
}

internal fun shouldExportPassiveLiveProof(
    retainRequested: Boolean,
    exportRequested: Boolean,
    retainedSessionCount: Int,
): Boolean {
    return retainRequested && !exportRequested && retainedSessionCount > 0
}

internal fun hasPauseObserved(snapshot: ReferenceControllerSnapshot): Boolean {
    return snapshot.session.lastOutcomeSummary == "PauseResult.Paused" ||
        hasTimelineEntry(snapshot, title = "Mesh paused")
}

internal fun hasResumeObserved(snapshot: ReferenceControllerSnapshot): Boolean {
    return snapshot.session.lastOutcomeSummary == "ResumeResult.Resumed" ||
        hasTimelineEntry(snapshot, title = "Mesh resumed")
}

internal fun hasPauseResumeRecoveryWindowOpened(snapshot: ReferenceControllerSnapshot): Boolean {
    return hasPauseObserved(snapshot)
}

internal fun hasTrustResetRecoveryWindowOpened(
    snapshot: ReferenceControllerSnapshot,
    peerSuffix: String? = null,
): Boolean {
    return hasTrustResetRecoveryReady(snapshot, peerSuffix = peerSuffix)
}

internal fun hasPeerTrustReset(
    snapshot: ReferenceControllerSnapshot,
    peerSuffix: String? = null,
): Boolean {
    return hasTimelineEntry(snapshot, title = "Peer trust reset", peerSuffix = peerSuffix)
}

internal fun hasTrustResetRecoveryReady(
    snapshot: ReferenceControllerSnapshot,
    peerSuffix: String? = null,
): Boolean {
    return hasPeerTrustReset(snapshot, peerSuffix = peerSuffix) ||
        hasTimelineEntry(snapshot, title = "ROUTE_RETRACTED", peerSuffix = peerSuffix)
}

internal fun hasMeshEnteredLifecycle(meshStateLabel: String): Boolean {
    return meshStateLabel.contains("Running") ||
        meshStateLabel.contains("Paused") ||
        meshStateLabel.contains("Stopped")
}

internal fun isMeshRunning(meshStateLabel: String): Boolean {
    return meshStateLabel.contains("Running")
}

internal fun isTerminalSenderFailureOutcome(lastOutcomeSummary: String): Boolean {
    return lastOutcomeSummary.startsWith("SendResult.NotSent(")
}

internal fun buildLargeTransferPayload(platformName: String): String {
    return buildString {
        repeat(LARGE_TRANSFER_PREVIEW_REPEAT_COUNT) {
            append("MeshLink reference large transfer preview from ")
            append(platformName)
            append(" · ")
        }
    }
}

private const val LARGE_TRANSFER_PREVIEW_REPEAT_COUNT: Int = 256
