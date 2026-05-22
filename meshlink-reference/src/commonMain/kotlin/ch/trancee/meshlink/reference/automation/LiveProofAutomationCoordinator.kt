package ch.trancee.meshlink.reference.automation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeViewModel
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
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
        requestMeshStartIfNeeded(snapshot)

        when (automationConfig.role) {
            ReferenceAutomationRole.SENDER -> runSenderAutomationStep(snapshot)
            ReferenceAutomationRole.PASSIVE -> runPassiveAutomationStep(snapshot, timelineUiState)
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
        val firstPeer = snapshot.peers.firstOrNull()
        if (!progress.sendRequested && firstPeer != null && shouldAutoSendGuidedHello(snapshot)) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION send.requested role=sender peer=${firstPeer.peerSuffix}"
            )
            guidedViewModel.sendHelloToFirstPeer()
            progress.sendRequested = true
        }

        if (
            !progress.completionLogged && snapshot.session.lastOutcomeSummary == "SendResult.Sent"
        ) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION proof.complete role=sender " +
                    "outcome=${snapshot.session.lastOutcomeSummary} " +
                    "peer=${snapshot.peers.firstOrNull()?.peerSuffix ?: "none"}"
            )
            progress.completionLogged = true
        }
    }

    private fun runPassiveAutomationStep(
        snapshot: ReferenceControllerSnapshot,
        timelineUiState: TechnicalTimelineUiState,
    ): Unit {
        val trustEstablished = hasTimelineEntry(snapshot, "TRUST_ESTABLISHED")
        val inboundMessage = hasTimelineEntry(snapshot, "Inbound message")

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
}

internal class LiveProofAutomationProgress {
    var announced by mutableStateOf(false)
    var peerAnnounced by mutableStateOf(false)
    var meshStartRequested by mutableStateOf(false)
    var sendRequested by mutableStateOf(false)
    var retainRequested by mutableStateOf(false)
    var exportRequested by mutableStateOf(false)
    var completionLogged by mutableStateOf(false)
}
