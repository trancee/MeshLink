@file:Suppress("FunctionNaming")

package ch.trancee.meshlink.reference.automation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeViewModel
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore

/** Drives one real-device proof flow using the live reference app surfaces. */
@Composable
internal fun ReferenceLiveProofAutomation(
    platformServices: PlatformServices,
    guidedViewModel: GuidedFirstExchangeViewModel,
    timelineStore: TechnicalTimelineStore,
    snapshot: ReferenceControllerSnapshot,
) {
    val automationConfig = platformServices.automationConfig ?: return
    if (!automationConfig.isLiveProofMode()) {
        return
    }

    val timelineUiState by timelineStore.uiState.collectAsState()
    val progress = rememberLiveProofAutomationProgress(automationConfig.storageSubdirectory)

    LaunchedEffect(
        automationConfig,
        snapshot.session.meshStateLabel,
        snapshot.session.lastOutcomeSummary,
        snapshot.peers.size,
        snapshot.timeline.size,
        timelineUiState.retainedSessions.size,
        timelineUiState.lastExportPath,
    ) {
        LiveProofAutomationCoordinator(
                automationConfig = automationConfig,
                platformServices = platformServices,
                guidedViewModel = guidedViewModel,
                timelineStore = timelineStore,
                progress = progress,
            )
            .run(snapshot = snapshot, timelineUiState = timelineUiState)
    }
}

private fun ReferenceAutomationConfig.isLiveProofMode(): Boolean {
    return mode == ReferenceAutomationMode.LIVE_PROOF
}

@Composable
private fun rememberLiveProofAutomationProgress(
    storageSubdirectory: String
): LiveProofAutomationProgress {
    return remember(storageSubdirectory) { LiveProofAutomationProgress() }
}

internal fun shouldAutoSendGuidedHello(
    snapshot: ReferenceControllerSnapshot,
    requiredPeerCount: Int = 1,
    targetPeerIndex: Int = 0,
): Boolean {
    return autoSendTargetPeer(
        snapshot = snapshot,
        requiredPeerCount = requiredPeerCount,
        targetPeerIndex = targetPeerIndex,
    ) != null
}

internal fun autoSendTargetPeer(
    snapshot: ReferenceControllerSnapshot,
    requiredPeerCount: Int,
    targetPeerIndex: Int,
) =
    snapshot.peers
        .takeIf { peers ->
            targetPeerIndex >= 0 && peers.size >= requiredPeerCount && targetPeerIndex < peers.size
        }
        ?.get(targetPeerIndex)

internal fun shouldRequestLiveProofMeshStart(
    meshStartRequested: Boolean,
    snapshot: ReferenceControllerSnapshot,
    readinessBlockers: List<String>,
): Boolean {
    return !meshStartRequested &&
        !hasMeshEnteredLifecycle(snapshot.session.meshStateLabel) &&
        readinessBlockers.isEmpty()
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

internal fun hasTimelineEntry(snapshot: ReferenceControllerSnapshot, title: String): Boolean {
    return snapshot.timeline.any { entry -> entry.title == title }
}

internal fun hasMeshEnteredLifecycle(meshStateLabel: String): Boolean {
    return meshStateLabel.contains("Running") ||
        meshStateLabel.contains("Paused") ||
        meshStateLabel.contains("Stopped")
}
