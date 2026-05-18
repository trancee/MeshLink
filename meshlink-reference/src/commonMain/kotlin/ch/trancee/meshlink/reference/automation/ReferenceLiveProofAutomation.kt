package ch.trancee.meshlink.reference.automation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeViewModel
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
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
    if (automationConfig.mode != ReferenceAutomationMode.LIVE_PROOF) {
        return
    }

    val timelineUiState by timelineStore.uiState.collectAsState()
    var announced by remember(automationConfig.storageSubdirectory) { mutableStateOf(false) }
    var peerAnnounced by remember(automationConfig.storageSubdirectory) { mutableStateOf(false) }
    var meshStartRequested by
        remember(automationConfig.storageSubdirectory) { mutableStateOf(false) }
    var sendRequested by remember(automationConfig.storageSubdirectory) { mutableStateOf(false) }
    var retainRequested by remember(automationConfig.storageSubdirectory) { mutableStateOf(false) }
    var exportRequested by remember(automationConfig.storageSubdirectory) { mutableStateOf(false) }
    var completionLogged by remember(automationConfig.storageSubdirectory) { mutableStateOf(false) }

    LaunchedEffect(
        automationConfig,
        snapshot.session.meshStateLabel,
        snapshot.session.lastOutcomeSummary,
        snapshot.peers.size,
        snapshot.timeline.size,
        timelineUiState.retainedSessions.size,
        timelineUiState.lastExportPath,
    ) {
        if (!announced) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION started mode=${automationConfig.mode} role=${automationConfig.role} appId=${automationConfig.appId} storage=${automationConfig.storageSubdirectory}"
            )
            announced = true
        }

        if (!peerAnnounced && snapshot.peers.isNotEmpty()) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION peer.discovered role=${automationConfig.role} peer=${snapshot.peers.first().peerSuffix}"
            )
            peerAnnounced = true
        }

        if (
            !meshStartRequested &&
                !snapshot.session.meshStateLabel.contains("Running") &&
                !snapshot.session.meshStateLabel.contains("Paused") &&
                !snapshot.session.meshStateLabel.contains("Stopped") &&
                platformServices.readinessBlockers.isEmpty()
        ) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION mesh.start.requested role=${automationConfig.role}"
            )
            guidedViewModel.startMesh()
            meshStartRequested = true
        }

        when (automationConfig.role) {
            ReferenceAutomationRole.SENDER -> {
                val canAttemptSend = shouldAutoSendGuidedHello(snapshot)
                if (!sendRequested && canAttemptSend) {
                    platformServices.emitAutomationLog(
                        "REFERENCE_AUTOMATION send.requested role=sender peer=${snapshot.peers.first().peerSuffix}"
                    )
                    guidedViewModel.sendHelloToFirstPeer()
                    sendRequested = true
                }
                if (!completionLogged && snapshot.session.lastOutcomeSummary == "SendResult.Sent") {
                    platformServices.emitAutomationLog(
                        "REFERENCE_AUTOMATION proof.complete role=sender outcome=${snapshot.session.lastOutcomeSummary} peer=${snapshot.peers.firstOrNull()?.peerSuffix ?: "none"}"
                    )
                    completionLogged = true
                }
            }

            ReferenceAutomationRole.PASSIVE -> {
                val hasTrustEstablished =
                    snapshot.timeline.any { entry -> entry.title == "TRUST_ESTABLISHED" }
                val hasInboundMessage =
                    snapshot.timeline.any { entry -> entry.title == "Inbound message" }
                if (!retainRequested && hasTrustEstablished && hasInboundMessage) {
                    platformServices.emitAutomationLog(
                        "REFERENCE_AUTOMATION history.retain.requested role=passive"
                    )
                    timelineStore.retainCurrentSession()
                    retainRequested = true
                }
                if (
                    retainRequested &&
                        !exportRequested &&
                        timelineUiState.retainedSessions.isNotEmpty()
                ) {
                    platformServices.emitAutomationLog(
                        "REFERENCE_AUTOMATION export.requested role=passive policy=redacted-preview"
                    )
                    timelineStore.exportCurrentSession(ExportPayloadPolicy.REDACTED_PREVIEW)
                    exportRequested = true
                }
                if (!completionLogged && timelineUiState.lastExportPath != null) {
                    platformServices.emitAutomationLog(
                        "REFERENCE_AUTOMATION proof.complete role=passive inbound=$hasInboundMessage trust=$hasTrustEstablished export=${timelineUiState.lastExportPath}"
                    )
                    completionLogged = true
                }
            }
        }
    }
}

internal fun shouldAutoSendGuidedHello(snapshot: ReferenceControllerSnapshot): Boolean {
    return snapshot.peers.isNotEmpty()
}
