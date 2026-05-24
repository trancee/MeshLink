package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.redactedSuffix
import ch.trancee.meshlink.reference.model.TimelineEntry
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
    if (targetPeerId != null) {
        return targetPeerId
            .takeIf { hasAvailableRouteForPeer(snapshot, it) }
            ?.let { AutoSendTargetPeer(peerId = it, peerSuffix = redactedSuffix(it)) }
    }
    val selectedPeer =
        snapshot.peers
            .takeIf { peers ->
                targetPeerIndex >= 0 &&
                    peers.size >= requiredPeerCount &&
                    targetPeerIndex < peers.size
            }
            ?.get(targetPeerIndex) ?: return null
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
        entry.family == TimelineFamily.DIAGNOSTIC &&
            entry.peerSuffix == redactedSuffix(peerId) &&
            entry.detail.contains("peerId=$peerId") &&
            entry.detail.contains("routeAvailable=true")
    }
}

internal fun bootstrapTargetPeer(
    snapshot: ReferenceControllerSnapshot,
    targetPeerId: String? = null,
): AutoSendTargetPeer? {
    if (targetPeerId == null || hasAvailableRouteForPeer(snapshot, targetPeerId)) {
        return null
    }
    val bootstrapPeer = snapshot.peers.firstOrNull() ?: return null
    return AutoSendTargetPeer(peerId = bootstrapPeer.peerId, peerSuffix = bootstrapPeer.peerSuffix)
}

internal data class AutoSendTargetPeer(val peerId: String, val peerSuffix: String)

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

internal fun shouldExportPassiveLiveProof(
    retainRequested: Boolean,
    exportRequested: Boolean,
    retainedSessionCount: Int,
): Boolean {
    return retainRequested && !exportRequested && retainedSessionCount > 0
}

internal fun hasTimelineEntry(
    snapshot: ReferenceControllerSnapshot,
    title: String,
    peerSuffix: String? = null,
): Boolean {
    return snapshot.timeline.any { entry ->
        entry.title == title && (peerSuffix == null || entry.peerSuffix == peerSuffix)
    }
}

internal fun timelineEntryCount(
    snapshot: ReferenceControllerSnapshot,
    title: String,
    peerSuffix: String? = null,
): Int {
    return snapshot.timeline.count { entry ->
        entry.title == title && (peerSuffix == null || entry.peerSuffix == peerSuffix)
    }
}

internal fun largestInboundPayloadBytes(snapshot: ReferenceControllerSnapshot): Int? {
    return snapshot.timeline
        .filter { entry -> entry.title == "Inbound message" }
        .maxOfOrNull { entry -> entry.payloadSizeBytes ?: 0 }
        ?.takeIf { bytes -> bytes > 0 }
}

internal fun hasPauseObserved(snapshot: ReferenceControllerSnapshot): Boolean {
    return snapshot.session.lastOutcomeSummary == "PauseResult.Paused" ||
        hasTimelineEntry(snapshot, title = "Mesh paused")
}

internal fun hasResumeObserved(snapshot: ReferenceControllerSnapshot): Boolean {
    return snapshot.session.lastOutcomeSummary == "ResumeResult.Resumed" ||
        hasTimelineEntry(snapshot, title = "Mesh resumed")
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

internal fun latestAutomationObservation(
    snapshot: ReferenceControllerSnapshot,
    peerSuffix: String? = null,
    families: Set<TimelineFamily> = DEFAULT_AUTOMATION_OBSERVATION_FAMILIES,
): TimelineEntry? {
    return snapshot.timeline.lastOrNull { entry ->
        entry.family in families && (peerSuffix == null || entry.peerSuffix == peerSuffix)
    }
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

private val DEFAULT_AUTOMATION_OBSERVATION_FAMILIES: Set<TimelineFamily> =
    setOf(TimelineFamily.DIAGNOSTIC, TimelineFamily.MESSAGE, TimelineFamily.TRANSFER)
