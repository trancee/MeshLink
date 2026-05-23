package ch.trancee.meshlink.reference.automation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeViewModel
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.redactedSuffix
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState
import ch.trancee.meshlink.reference.timeline.endCurrentSession
import ch.trancee.meshlink.reference.timeline.exportCurrentSession

internal class LiveProofAutomationCoordinator(
    private val automationConfig: ReferenceAutomationConfig,
    private val platformServices: PlatformServices,
    private val guidedViewModel: GuidedFirstExchangeViewModel,
    private val timelineStore: TechnicalTimelineStore,
    private val progress: LiveProofAutomationProgress,
) {
    internal fun run(
        snapshot: ReferenceControllerSnapshot,
        timelineUiState: TechnicalTimelineUiState,
    ): Unit {
        announceAutomationStartIfNeeded()
        announcePeerDiscoveryIfNeeded(snapshot)
        announcePeerSnapshotIfNeeded(snapshot)
        requestMeshStartIfNeeded(snapshot)

        when (automationConfig.role) {
            ReferenceAutomationRole.SENDER -> runSenderAutomationStep(snapshot)
            ReferenceAutomationRole.PASSIVE -> runPassiveAutomationStep(snapshot, timelineUiState)
            ReferenceAutomationRole.RELAY -> runRelayAutomationStep(snapshot)
        }
    }

    private fun announceAutomationStartIfNeeded(): Unit {
        if (progress.announced) {
            return
        }

        platformServices.emitAutomationLog(
            "REFERENCE_AUTOMATION started " +
                "mode=${automationConfig.mode} " +
                "role=${automationConfig.role} " +
                "appId=${automationConfig.appId} " +
                "storage=${automationConfig.storageSubdirectory}"
        )
        progress.announced = true
    }

    private fun announcePeerDiscoveryIfNeeded(snapshot: ReferenceControllerSnapshot): Unit {
        val firstPeer = snapshot.peers.firstOrNull() ?: return
        if (progress.peerAnnounced) {
            return
        }

        platformServices.emitAutomationLog(
            "REFERENCE_AUTOMATION peer.discovered " +
                "role=${automationConfig.role} " +
                "peer=${firstPeer.peerSuffix}"
        )
        progress.peerAnnounced = true
    }

    private fun announcePeerSnapshotIfNeeded(snapshot: ReferenceControllerSnapshot): Unit {
        val peerSnapshotSummary =
            snapshot.peers.joinToString(separator = ",") { peer -> peer.peerSuffix }
        if (peerSnapshotSummary == progress.lastPeerSnapshotSummary) {
            return
        }
        platformServices.emitAutomationLog(
            "REFERENCE_AUTOMATION peers " +
                "role=${automationConfig.role} " +
                "count=${snapshot.peers.size} " +
                "suffixes=$peerSnapshotSummary"
        )
        progress.lastPeerSnapshotSummary = peerSnapshotSummary
    }

    private fun requestMeshStartIfNeeded(snapshot: ReferenceControllerSnapshot): Unit {
        if (
            !shouldRequestLiveProofMeshStart(
                meshStartRequested = progress.meshStartRequested,
                snapshot = snapshot,
                readinessBlockers = platformServices.readinessBlockers,
            )
        ) {
            return
        }

        platformServices.emitAutomationLog(
            "REFERENCE_AUTOMATION mesh.start.requested role=${automationConfig.role}"
        )
        guidedViewModel.startMesh()
        progress.meshStartRequested = true
    }

    private fun runSenderAutomationStep(snapshot: ReferenceControllerSnapshot): Unit {
        val targetPeer =
            autoSendTargetPeer(
                snapshot = snapshot,
                requiredPeerCount = automationConfig.requiredPeerCount,
                targetPeerIndex = automationConfig.targetPeerIndex,
                targetPeerId = automationConfig.targetPeerId,
            )
        val bootstrapPeer =
            bootstrapTargetPeer(snapshot = snapshot, targetPeerId = automationConfig.targetPeerId)
        val meshRunning = isMeshRunning(snapshot.session.meshStateLabel)
        if (!meshRunning) {
            return
        }
        if (
            !progress.bootstrapRequested &&
                !progress.sendRequested &&
                targetPeer == null &&
                bootstrapPeer != null
        ) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION bootstrap.requested role=sender " +
                    "peer=${bootstrapPeer.peerSuffix} " +
                    "targetPeerId=${automationConfig.targetPeerId ?: "auto"}"
            )
            guidedViewModel.sendHelloToPeer(bootstrapPeer.peerId)
            progress.bootstrapRequested = true
        }
        announceBootstrapObservationIfNeeded(snapshot = snapshot, bootstrapPeer = bootstrapPeer)
        if (
            !progress.sendRequested &&
                targetPeer != null &&
                shouldAutoSendGuidedHello(
                    snapshot = snapshot,
                    requiredPeerCount = automationConfig.requiredPeerCount,
                    targetPeerIndex = automationConfig.targetPeerIndex,
                    targetPeerId = automationConfig.targetPeerId,
                )
        ) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION send.requested role=sender " +
                    "peer=${targetPeer.peerSuffix} " +
                    "targetIndex=${automationConfig.targetPeerIndex} " +
                    "requiredPeerCount=${automationConfig.requiredPeerCount} " +
                    "targetPeerId=${automationConfig.targetPeerId ?: "auto"}"
            )
            guidedViewModel.sendHelloToPeer(targetPeer.peerId)
            progress.sendRequested = true
        }

        val targetPeerSuffix =
            targetPeer?.peerSuffix ?: automationConfig.targetPeerId?.let(::redactedSuffix)
        announceSenderObservationIfNeeded(snapshot = snapshot, targetPeerSuffix = targetPeerSuffix)
        announceSenderOutcomeIfNeeded(snapshot = snapshot)

        val lastOutcomeSummary = snapshot.session.lastOutcomeSummary
        val deliveryDetail =
            latestSenderDeliveryDetail(snapshot = snapshot, peerSuffix = targetPeerSuffix)
        if (
            progress.sendRequested &&
                !progress.completionLogged &&
                lastOutcomeSummary == "SendResult.Sent" &&
                deliveryDetail != null
        ) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION proof.complete role=sender " +
                    "outcome=$lastOutcomeSummary " +
                    "peer=${targetPeerSuffix ?: "none"} " +
                    "delivery=$deliveryDetail"
            )
            progress.completionLogged = true
        }
        if (
            progress.sendRequested &&
                !progress.completionLogged &&
                lastOutcomeSummary != null &&
                isTerminalSenderFailureOutcome(lastOutcomeSummary)
        ) {
            val latestObservation =
                latestAutomationObservation(snapshot = snapshot, peerSuffix = targetPeerSuffix)
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION proof.failed role=sender " +
                    "outcome=$lastOutcomeSummary " +
                    "peer=${targetPeerSuffix ?: "none"} " +
                    "observation=${latestObservation?.title ?: "none"} " +
                    "detail=${latestObservation?.detail ?: "none"}"
            )
            progress.completionLogged = true
        }
    }

    private fun announceBootstrapObservationIfNeeded(
        snapshot: ReferenceControllerSnapshot,
        bootstrapPeer: AutoSendTargetPeer?,
    ): Unit {
        if (!progress.bootstrapRequested || progress.sendRequested || bootstrapPeer == null) {
            return
        }
        val observation =
            latestAutomationObservation(snapshot = snapshot, peerSuffix = bootstrapPeer.peerSuffix)
                ?: return
        if (progress.lastBootstrapObservationEntryId == observation.entryId) {
            return
        }
        platformServices.emitAutomationLog(
            "REFERENCE_AUTOMATION bootstrap.observed role=sender " +
                "family=${observation.family} " +
                "title=${observation.title} " +
                "peer=${bootstrapPeer.peerSuffix} " +
                "detail=${observation.detail}"
        )
        progress.lastBootstrapObservationEntryId = observation.entryId
    }

    private fun announceSenderObservationIfNeeded(
        snapshot: ReferenceControllerSnapshot,
        targetPeerSuffix: String?,
    ): Unit {
        if (!progress.sendRequested || targetPeerSuffix == null) {
            return
        }
        val observation =
            latestAutomationObservation(snapshot = snapshot, peerSuffix = targetPeerSuffix)
                ?: return
        if (progress.lastSenderObservationEntryId == observation.entryId) {
            return
        }
        platformServices.emitAutomationLog(
            "REFERENCE_AUTOMATION sender.observed role=sender " +
                "family=${observation.family} " +
                "title=${observation.title} " +
                "peer=$targetPeerSuffix " +
                "detail=${observation.detail}"
        )
        progress.lastSenderObservationEntryId = observation.entryId
    }

    private fun announceSenderOutcomeIfNeeded(snapshot: ReferenceControllerSnapshot): Unit {
        if (!progress.bootstrapRequested && !progress.sendRequested) {
            return
        }
        val lastOutcomeSummary = snapshot.session.lastOutcomeSummary ?: return
        if (
            lastOutcomeSummary == "Peer found" ||
                progress.lastSenderOutcomeSummary == lastOutcomeSummary
        ) {
            return
        }
        platformServices.emitAutomationLog(
            "REFERENCE_AUTOMATION sender.outcome role=sender " + "summary=$lastOutcomeSummary"
        )
        progress.lastSenderOutcomeSummary = lastOutcomeSummary
    }

    private fun runPassiveAutomationStep(
        snapshot: ReferenceControllerSnapshot,
        timelineUiState: TechnicalTimelineUiState,
    ): Unit {
        val trustEstablished = hasTimelineEntry(snapshot, "TRUST_ESTABLISHED")
        val inboundMessage = hasTimelineEntry(snapshot, "Inbound message")
        announcePassiveObservationIfNeeded(snapshot = snapshot)

        if (
            shouldRetainPassiveLiveProof(
                retainRequested = progress.retainRequested,
                hasTrustEstablished = trustEstablished,
                hasInboundMessage = inboundMessage,
            )
        ) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION session.end.requested role=passive"
            )
            timelineStore.endCurrentSession()
            progress.retainRequested = true
        }

        if (
            shouldExportPassiveLiveProof(
                retainRequested = progress.retainRequested,
                exportRequested = progress.exportRequested,
                retainedSessionCount = timelineUiState.retainedSessions.size,
            )
        ) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION export.requested " + "role=passive policy=redacted-preview"
            )
            timelineStore.exportCurrentSession(ExportPayloadPolicy.REDACTED_PREVIEW)
            progress.exportRequested = true
        }

        val exportPath = timelineUiState.lastExportPath
        if (!progress.completionLogged && exportPath != null) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION proof.complete role=passive " +
                    "inbound=$inboundMessage trust=$trustEstablished " +
                    "export=$exportPath"
            )
            progress.completionLogged = true
        }
    }

    private fun announcePassiveObservationIfNeeded(snapshot: ReferenceControllerSnapshot): Unit {
        val observation = latestAutomationObservation(snapshot = snapshot) ?: return
        if (progress.lastPassiveObservationEntryId == observation.entryId) {
            return
        }
        platformServices.emitAutomationLog(
            "REFERENCE_AUTOMATION passive.observed role=passive " +
                "family=${observation.family} " +
                "title=${observation.title} " +
                "peer=${observation.peerSuffix ?: "none"} " +
                "detail=${observation.detail}"
        )
        progress.lastPassiveObservationEntryId = observation.entryId
    }

    private fun runRelayAutomationStep(snapshot: ReferenceControllerSnapshot): Unit {
        val observation =
            latestAutomationObservation(
                snapshot = snapshot,
                families = setOf(TimelineFamily.DIAGNOSTIC, TimelineFamily.TRANSFER),
            ) ?: return
        if (progress.lastRelayObservationEntryId == observation.entryId) {
            return
        }
        platformServices.emitAutomationLog(
            "REFERENCE_AUTOMATION relay.observed role=relay " +
                "family=${observation.family} " +
                "title=${observation.title} " +
                "peer=${observation.peerSuffix ?: "none"} " +
                "detail=${observation.detail}"
        )
        progress.lastRelayObservationEntryId = observation.entryId
    }
}

private fun latestSenderDeliveryDetail(
    snapshot: ReferenceControllerSnapshot,
    peerSuffix: String?,
): String? {
    return snapshot.timeline
        .lastOrNull { entry ->
            entry.family == TimelineFamily.DIAGNOSTIC &&
                entry.title == "DELIVERY_SUCCEEDED" &&
                (peerSuffix == null || entry.peerSuffix == peerSuffix)
        }
        ?.detail
}

internal class LiveProofAutomationProgress {
    var announced by mutableStateOf(false)
    var peerAnnounced by mutableStateOf(false)
    var meshStartRequested by mutableStateOf(false)
    var bootstrapRequested by mutableStateOf(false)
    var sendRequested by mutableStateOf(false)
    var retainRequested by mutableStateOf(false)
    var exportRequested by mutableStateOf(false)
    var completionLogged by mutableStateOf(false)
    var lastPeerSnapshotSummary by mutableStateOf<String?>(null)
    var lastBootstrapObservationEntryId by mutableStateOf<String?>(null)
    var lastSenderObservationEntryId by mutableStateOf<String?>(null)
    var lastPassiveObservationEntryId by mutableStateOf<String?>(null)
    var lastRelayObservationEntryId by mutableStateOf<String?>(null)
    var lastSenderOutcomeSummary by mutableStateOf<String?>(null)
}
