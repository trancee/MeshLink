package ch.trancee.meshlink.reference.automation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.trancee.meshlink.api.DeliveryPriority
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
import kotlinx.coroutines.launch

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
                "scenario=${automationConfig.scenario.wireValue()} " +
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
        when (automationConfig.scenario) {
            ReferenceAutomationScenario.DIRECT_PAUSE_RESUME ->
                runPauseResumeSenderAutomationStep(snapshot)
            ReferenceAutomationScenario.DIRECT_TRUST_RESET_RECOVERY ->
                runTrustResetRecoverySenderAutomationStep(snapshot)
            ReferenceAutomationScenario.DIRECT_LARGE_TRANSFER ->
                runDirectSenderAutomationStep(
                    snapshot,
                    payloadPlan = SenderPayloadPlan.LARGE_TRANSFER,
                )
            ReferenceAutomationScenario.RELAY_CONSTRAINED -> runRelaySenderAutomationStep(snapshot)
            ReferenceAutomationScenario.DIRECT_FULL_EXPORT,
            ReferenceAutomationScenario.DIRECT_GUIDED ->
                runDirectSenderAutomationStep(
                    snapshot,
                    payloadPlan = SenderPayloadPlan.GUIDED_HELLO,
                )
        }
    }

    private fun runDirectSenderAutomationStep(
        snapshot: ReferenceControllerSnapshot,
        payloadPlan: SenderPayloadPlan,
    ): Unit {
        val targetPeer =
            autoSendTargetPeer(
                snapshot = snapshot,
                requiredPeerCount = automationConfig.requiredPeerCount,
                targetPeerIndex = automationConfig.targetPeerIndex,
                targetPeerId = automationConfig.targetPeerId,
            ) ?: return
        if (!isMeshRunning(snapshot.session.meshStateLabel)) {
            return
        }

        if (!progress.sendRequested) {
            requestSenderPayload(
                phase = "primary",
                targetPeer = targetPeer,
                payloadPlan = payloadPlan,
            )
            progress.sendRequested = true
        }

        val targetPeerSuffix = targetPeer.peerSuffix
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
            val completionSuffix =
                when (payloadPlan) {
                    SenderPayloadPlan.GUIDED_HELLO,
                    SenderPayloadPlan.RECOVERY_HELLO -> ""
                    SenderPayloadPlan.LARGE_TRANSFER ->
                        " bytes=${largeTransferPayloadBytes(platformServices.platformName)}"
                }
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION proof.complete role=sender " +
                    "outcome=$lastOutcomeSummary " +
                    "peer=$targetPeerSuffix " +
                    "delivery=$deliveryDetail$completionSuffix"
            )
            progress.completionLogged = true
        }
        if (
            progress.sendRequested &&
                !progress.completionLogged &&
                lastOutcomeSummary != null &&
                isTerminalSenderFailureOutcome(lastOutcomeSummary)
        ) {
            emitSenderFailure(snapshot = snapshot, targetPeerSuffix = targetPeerSuffix)
        }
    }

    private fun runPauseResumeSenderAutomationStep(snapshot: ReferenceControllerSnapshot): Unit {
        val targetPeer =
            autoSendTargetPeer(
                snapshot = snapshot,
                requiredPeerCount = automationConfig.requiredPeerCount,
                targetPeerIndex = automationConfig.targetPeerIndex,
                targetPeerId = automationConfig.targetPeerId,
            ) ?: return
        val meshRunning = isMeshRunning(snapshot.session.meshStateLabel)
        if (!progress.pauseRequested && meshRunning) {
            platformServices.emitAutomationLog("REFERENCE_AUTOMATION pause.requested role=sender")
            timelineStore.scope.launch { platformServices.meshLinkController.pause() }
            progress.pauseRequested = true
            return
        }
        if (progress.pauseRequested && !progress.pauseObserved && hasPauseObserved(snapshot)) {
            platformServices.emitAutomationLog("REFERENCE_AUTOMATION pause.observed role=sender")
            progress.pauseObserved = true
        }
        if (progress.pauseObserved && !progress.resumeRequested) {
            platformServices.emitAutomationLog("REFERENCE_AUTOMATION resume.requested role=sender")
            timelineStore.scope.launch { platformServices.meshLinkController.resume() }
            progress.resumeRequested = true
            return
        }
        if (progress.resumeRequested && !progress.resumeObserved && hasResumeObserved(snapshot)) {
            platformServices.emitAutomationLog("REFERENCE_AUTOMATION resume.observed role=sender")
            progress.resumeObserved = true
        }
        if (!progress.resumeObserved || !isMeshRunning(snapshot.session.meshStateLabel)) {
            return
        }
        runDirectSenderAutomationStep(snapshot, payloadPlan = SenderPayloadPlan.GUIDED_HELLO)
    }

    private fun runTrustResetRecoverySenderAutomationStep(
        snapshot: ReferenceControllerSnapshot
    ): Unit {
        val targetPeer =
            autoSendTargetPeer(
                snapshot = snapshot,
                requiredPeerCount = automationConfig.requiredPeerCount,
                targetPeerIndex = automationConfig.targetPeerIndex,
                targetPeerId = automationConfig.targetPeerId,
            ) ?: return
        if (!isMeshRunning(snapshot.session.meshStateLabel)) {
            return
        }

        if (!progress.sendRequested) {
            requestSenderPayload(
                phase = "primary",
                targetPeer = targetPeer,
                payloadPlan = SenderPayloadPlan.GUIDED_HELLO,
            )
            progress.sendRequested = true
        }

        val targetPeerSuffix = targetPeer.peerSuffix
        announceSenderObservationIfNeeded(snapshot = snapshot, targetPeerSuffix = targetPeerSuffix)
        announceSenderOutcomeIfNeeded(snapshot = snapshot)

        val deliveryCount =
            timelineEntryCount(
                snapshot,
                title = "DELIVERY_SUCCEEDED",
                peerSuffix = targetPeerSuffix,
            )
        if (
            progress.sendRequested &&
                !progress.trustResetRequested &&
                snapshot.session.lastOutcomeSummary == "SendResult.Sent" &&
                deliveryCount >= 1
        ) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION trust.reset.requested role=sender peer=$targetPeerSuffix"
            )
            timelineStore.scope.launch {
                platformServices.meshLinkController.forgetPeer(targetPeer.peerId)
            }
            progress.trustResetRequested = true
            return
        }
        if (
            progress.trustResetRequested &&
                !progress.trustResetObserved &&
                hasTrustResetRecoveryReady(snapshot, peerSuffix = targetPeerSuffix)
        ) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION trust.reset.observed role=sender peer=$targetPeerSuffix"
            )
            progress.trustResetObserved = true
        }
        if (progress.trustResetObserved && !progress.recoverySendRequested) {
            requestSenderPayload(
                phase = "recovery",
                targetPeer = targetPeer,
                payloadPlan = SenderPayloadPlan.RECOVERY_HELLO,
            )
            progress.recoverySendRequested = true
        }

        val deliveryDetail =
            latestSenderDeliveryDetail(snapshot = snapshot, peerSuffix = targetPeerSuffix)
        if (
            progress.recoverySendRequested &&
                !progress.completionLogged &&
                snapshot.session.lastOutcomeSummary == "SendResult.Sent" &&
                deliveryCount >= REQUIRED_RECOVERY_DELIVERY_COUNT &&
                deliveryDetail != null
        ) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION proof.complete role=sender " +
                    "outcome=${snapshot.session.lastOutcomeSummary} " +
                    "peer=$targetPeerSuffix " +
                    "delivery=$deliveryDetail " +
                    "deliveries=$deliveryCount"
            )
            progress.completionLogged = true
        }
        if (
            progress.recoverySendRequested &&
                !progress.completionLogged &&
                snapshot.session.lastOutcomeSummary != null &&
                isTerminalSenderFailureOutcome(snapshot.session.lastOutcomeSummary)
        ) {
            emitSenderFailure(snapshot = snapshot, targetPeerSuffix = targetPeerSuffix)
        }
    }

    private fun runRelaySenderAutomationStep(snapshot: ReferenceControllerSnapshot): Unit {
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
            requestSenderPayload(
                phase = "bootstrap",
                targetPeer = bootstrapPeer,
                payloadPlan = SenderPayloadPlan.GUIDED_HELLO,
            )
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
            requestSenderPayload(
                phase = "primary",
                targetPeer = targetPeer,
                payloadPlan = SenderPayloadPlan.GUIDED_HELLO,
            )
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
            emitSenderFailure(snapshot = snapshot, targetPeerSuffix = targetPeerSuffix)
        }
    }

    private fun requestSenderPayload(
        phase: String,
        targetPeer: AutoSendTargetPeer,
        payloadPlan: SenderPayloadPlan,
    ): Unit {
        val payloadText = payloadPlan.payload(platformServices.platformName)
        val priority = payloadPlan.priority
        val payloadBytes = payloadText.encodeToByteArray().size
        platformServices.emitAutomationLog(
            "REFERENCE_AUTOMATION send.requested role=sender " +
                "phase=$phase " +
                "peer=${targetPeer.peerSuffix} " +
                "priority=$priority " +
                "bytes=$payloadBytes " +
                "payload=${payloadPlan.label} " +
                "targetIndex=${automationConfig.targetPeerIndex} " +
                "requiredPeerCount=${automationConfig.requiredPeerCount} " +
                "targetPeerId=${automationConfig.targetPeerId ?: "auto"}"
        )
        timelineStore.scope.launch {
            platformServices.meshLinkController.sendSamplePayload(
                peerId = targetPeer.peerId,
                payloadText = payloadText,
                priority = priority,
            )
        }
    }

    private fun emitSenderFailure(
        snapshot: ReferenceControllerSnapshot,
        targetPeerSuffix: String?,
    ): Unit {
        val latestObservation =
            latestAutomationObservation(snapshot = snapshot, peerSuffix = targetPeerSuffix)
        platformServices.emitAutomationLog(
            "REFERENCE_AUTOMATION proof.failed role=sender " +
                "outcome=${snapshot.session.lastOutcomeSummary.orEmpty()} " +
                "peer=${targetPeerSuffix ?: "none"} " +
                "observation=${latestObservation?.title ?: "none"} " +
                "detail=${latestObservation?.detail ?: "none"}"
        )
        progress.completionLogged = true
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
        if (
            (!progress.sendRequested && !progress.recoverySendRequested) || targetPeerSuffix == null
        ) {
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
        if (
            !progress.bootstrapRequested &&
                !progress.sendRequested &&
                !progress.recoverySendRequested
        ) {
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
            "REFERENCE_AUTOMATION sender.outcome role=sender summary=$lastOutcomeSummary"
        )
        progress.lastSenderOutcomeSummary = lastOutcomeSummary
    }

    private fun runPassiveAutomationStep(
        snapshot: ReferenceControllerSnapshot,
        timelineUiState: TechnicalTimelineUiState,
    ): Unit {
        when (automationConfig.scenario) {
            ReferenceAutomationScenario.DIRECT_FULL_EXPORT ->
                runPassiveFullExportAutomationStep(snapshot, timelineUiState)
            ReferenceAutomationScenario.DIRECT_TRUST_RESET_RECOVERY ->
                runPassiveBaselineAutomationStep(
                    snapshot = snapshot,
                    timelineUiState = timelineUiState,
                    requiredInboundCount = REQUIRED_RECOVERY_INBOUND_COUNT,
                )
            ReferenceAutomationScenario.DIRECT_LARGE_TRANSFER ->
                runPassiveBaselineAutomationStep(
                    snapshot = snapshot,
                    timelineUiState = timelineUiState,
                    requiredInboundCount = 1,
                    requiredLargestInboundBytes =
                        largeTransferPayloadBytes(platformServices.platformName),
                )
            ReferenceAutomationScenario.RELAY_CONSTRAINED,
            ReferenceAutomationScenario.DIRECT_PAUSE_RESUME,
            ReferenceAutomationScenario.DIRECT_GUIDED ->
                runPassiveBaselineAutomationStep(
                    snapshot = snapshot,
                    timelineUiState = timelineUiState,
                )
        }
    }

    private fun runPassiveBaselineAutomationStep(
        snapshot: ReferenceControllerSnapshot,
        timelineUiState: TechnicalTimelineUiState,
        requiredInboundCount: Int = 1,
        requiredLargestInboundBytes: Int? = null,
    ): Unit {
        val trustEstablished = hasTimelineEntry(snapshot, title = "TRUST_ESTABLISHED")
        val inboundCount = timelineEntryCount(snapshot, title = "Inbound message")
        val largestInboundBytes = largestInboundPayloadBytes(snapshot)
        val inboundReady =
            inboundCount >= requiredInboundCount &&
                (requiredLargestInboundBytes == null ||
                    (largestInboundBytes ?: 0) >= requiredLargestInboundBytes)
        announcePassiveObservationIfNeeded(snapshot = snapshot)

        if (
            shouldRetainPassiveLiveProof(
                retainRequested = progress.retainRequested,
                hasTrustEstablished = trustEstablished,
                hasInboundMessage = inboundReady,
            )
        ) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION session.end.requested role=passive inboundCount=$inboundCount"
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
                "REFERENCE_AUTOMATION export.requested role=passive policy=redacted-preview"
            )
            timelineStore.exportCurrentSession(ExportPayloadPolicy.REDACTED_PREVIEW)
            progress.exportRequested = true
        }

        val exportPath =
            trackExportPath(
                currentExportPath = timelineUiState.lastExportPath,
                targetPolicy = ExportPayloadPolicy.REDACTED_PREVIEW,
            )
        if (!progress.completionLogged && exportPath != null) {
            val largestInboundText =
                largestInboundBytes?.let { bytes -> " largestInboundBytes=$bytes" }.orEmpty()
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION proof.complete role=passive " +
                    "inbound=$inboundReady " +
                    "inboundCount=$inboundCount " +
                    "trust=$trustEstablished " +
                    "export=$exportPath$largestInboundText"
            )
            progress.completionLogged = true
        }
    }

    private fun runPassiveFullExportAutomationStep(
        snapshot: ReferenceControllerSnapshot,
        timelineUiState: TechnicalTimelineUiState,
    ): Unit {
        val trustEstablished = hasTimelineEntry(snapshot, title = "TRUST_ESTABLISHED")
        val inboundCount = timelineEntryCount(snapshot, title = "Inbound message")
        val inboundReady = inboundCount >= 1
        announcePassiveObservationIfNeeded(snapshot = snapshot)

        if (!progress.fullExportRequested && trustEstablished && inboundReady) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION export.requested role=passive policy=full-payload"
            )
            timelineStore.exportCurrentSession(ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN)
            progress.fullExportRequested = true
        }
        trackExportPath(
            currentExportPath = timelineUiState.lastExportPath,
            targetPolicy = ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN,
        )

        if (progress.fullExportPath != null && !progress.retainRequested) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION session.end.requested role=passive inboundCount=$inboundCount"
            )
            timelineStore.endCurrentSession()
            progress.retainRequested = true
        }
        if (
            progress.fullExportPath != null &&
                shouldExportPassiveLiveProof(
                    retainRequested = progress.retainRequested,
                    exportRequested = progress.exportRequested,
                    retainedSessionCount = timelineUiState.retainedSessions.size,
                )
        ) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION export.requested role=passive policy=redacted-preview"
            )
            timelineStore.exportCurrentSession(ExportPayloadPolicy.REDACTED_PREVIEW)
            progress.exportRequested = true
        }
        trackExportPath(
            currentExportPath = timelineUiState.lastExportPath,
            targetPolicy = ExportPayloadPolicy.REDACTED_PREVIEW,
        )

        if (
            !progress.completionLogged &&
                progress.fullExportPath != null &&
                progress.redactedExportPath != null
        ) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION proof.complete role=passive " +
                    "inbound=$inboundReady " +
                    "inboundCount=$inboundCount " +
                    "trust=$trustEstablished " +
                    "fullExport=${progress.fullExportPath} " +
                    "export=${progress.redactedExportPath}"
            )
            progress.completionLogged = true
        }
    }

    private fun trackExportPath(
        currentExportPath: String?,
        targetPolicy: ExportPayloadPolicy,
    ): String? {
        if (currentExportPath == null || currentExportPath == progress.lastObservedExportPath) {
            return when (targetPolicy) {
                ExportPayloadPolicy.REDACTED_PREVIEW -> progress.redactedExportPath
                ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN -> progress.fullExportPath
            }
        }
        progress.lastObservedExportPath = currentExportPath
        when (targetPolicy) {
            ExportPayloadPolicy.REDACTED_PREVIEW -> {
                if (currentExportPath == progress.fullExportPath) {
                    return progress.redactedExportPath
                }
                progress.redactedExportPath = currentExportPath
                platformServices.emitAutomationLog(
                    "REFERENCE_AUTOMATION export.completed role=passive policy=redacted-preview path=$currentExportPath"
                )
                return progress.redactedExportPath
            }
            ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN -> {
                if (currentExportPath == progress.redactedExportPath) {
                    return progress.fullExportPath
                }
                progress.fullExportPath = currentExportPath
                platformServices.emitAutomationLog(
                    "REFERENCE_AUTOMATION export.completed role=passive policy=full-payload path=$currentExportPath"
                )
                return progress.fullExportPath
            }
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
    var pauseRequested by mutableStateOf(false)
    var pauseObserved by mutableStateOf(false)
    var resumeRequested by mutableStateOf(false)
    var resumeObserved by mutableStateOf(false)
    var trustResetRequested by mutableStateOf(false)
    var trustResetObserved by mutableStateOf(false)
    var recoverySendRequested by mutableStateOf(false)
    var retainRequested by mutableStateOf(false)
    var exportRequested by mutableStateOf(false)
    var fullExportRequested by mutableStateOf(false)
    var fullExportPath by mutableStateOf<String?>(null)
    var redactedExportPath by mutableStateOf<String?>(null)
    var lastObservedExportPath by mutableStateOf<String?>(null)
    var completionLogged by mutableStateOf(false)
    var lastPeerSnapshotSummary by mutableStateOf<String?>(null)
    var lastBootstrapObservationEntryId by mutableStateOf<String?>(null)
    var lastSenderObservationEntryId by mutableStateOf<String?>(null)
    var lastPassiveObservationEntryId by mutableStateOf<String?>(null)
    var lastRelayObservationEntryId by mutableStateOf<String?>(null)
    var lastSenderOutcomeSummary by mutableStateOf<String?>(null)
}

private enum class SenderPayloadPlan(val label: String, val priority: DeliveryPriority) {
    GUIDED_HELLO(label = "guided-hello", priority = DeliveryPriority.NORMAL),
    RECOVERY_HELLO(label = "trust-reset-recovery", priority = DeliveryPriority.NORMAL),
    LARGE_TRANSFER(label = "large-transfer", priority = DeliveryPriority.HIGH),
}

private fun SenderPayloadPlan.payload(platformName: String): String {
    return when (this) {
        SenderPayloadPlan.GUIDED_HELLO -> "hello mesh from $platformName"
        SenderPayloadPlan.RECOVERY_HELLO -> "hello again from $platformName after trust reset"
        SenderPayloadPlan.LARGE_TRANSFER -> buildLargeTransferPayload(platformName)
    }
}

private fun largeTransferPayloadBytes(platformName: String): Int {
    return buildLargeTransferPayload(platformName).encodeToByteArray().size
}

private const val REQUIRED_RECOVERY_DELIVERY_COUNT: Int = 2
private const val REQUIRED_RECOVERY_INBOUND_COUNT: Int = 2
