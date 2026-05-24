package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.redactedSuffix
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState

internal class LiveProofAutomationCoordinator(
    private val automationConfig: ReferenceAutomationConfig,
    private val actions: LiveProofAutomationActions,
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

        actions.emitAutomationLog(
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

        actions.emitAutomationLog(
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
        actions.emitAutomationLog(
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
                readinessBlockers = actions.readinessBlockers,
            )
        ) {
            return
        }

        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION mesh.start.requested role=${automationConfig.role}"
        )
        actions.requestMeshStart()
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
                        " bytes=${largeTransferPayloadBytes(actions.platformName)}"
                }
            actions.emitAutomationLog(
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
        val resolvedTargetPeer =
            autoSendTargetPeer(
                snapshot = snapshot,
                requiredPeerCount = automationConfig.requiredPeerCount,
                targetPeerIndex = automationConfig.targetPeerIndex,
                targetPeerId = automationConfig.targetPeerId,
            )
        if (progress.pauseResumeTargetPeerId == null && resolvedTargetPeer != null) {
            progress.pauseResumeTargetPeerId = resolvedTargetPeer.peerId
            progress.pauseResumeTargetPeerSuffix = resolvedTargetPeer.peerSuffix
        }
        val targetPeer =
            resolvedTargetPeer
                ?: progress.pauseResumeTargetPeerId?.let { peerId ->
                    AutoSendTargetPeer(
                        peerId = peerId,
                        peerSuffix = progress.pauseResumeTargetPeerSuffix ?: redactedSuffix(peerId),
                    )
                }
                ?: return
        if (!progress.pauseRequested && !isMeshRunning(snapshot.session.meshStateLabel)) {
            return
        }

        if (!progress.sendRequested) {
            requestSenderPayload(
                phase = "warmup",
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
            shouldRequestPauseForPauseResume(
                pauseRequested = progress.pauseRequested,
                snapshot = snapshot,
                targetPeerId = targetPeer.peerId,
            ) && snapshot.session.lastOutcomeSummary == "SendResult.Sent" && deliveryCount >= 1
        ) {
            actions.emitAutomationLog("REFERENCE_AUTOMATION pause.requested role=sender")
            actions.requestMeshPause()
            progress.pauseRequested = true
            return
        }
        if (progress.pauseRequested && !progress.pauseObserved && hasPauseObserved(snapshot)) {
            actions.emitAutomationLog("REFERENCE_AUTOMATION pause.observed role=sender")
            progress.pauseObserved = true
        }
        if (progress.pauseObserved && !progress.resumeRequested) {
            actions.emitAutomationLog("REFERENCE_AUTOMATION resume.requested role=sender")
            actions.requestMeshResume()
            progress.resumeRequested = true
            return
        }
        if (progress.resumeRequested && !progress.resumeObserved && hasResumeObserved(snapshot)) {
            actions.emitAutomationLog("REFERENCE_AUTOMATION resume.observed role=sender")
            progress.resumeObserved = true
        }
        if (
            !progress.recoverySendRequested &&
                shouldSendAfterPauseResumeRecovery(
                    resumeObserved = progress.resumeObserved,
                    snapshot = snapshot,
                )
        ) {
            requestSenderPayload(
                phase = "recovery",
                targetPeer = targetPeer,
                payloadPlan = SenderPayloadPlan.GUIDED_HELLO,
            )
            progress.recoverySendRequested = true
        }

        val deliveryDetail =
            latestSenderDeliveryDetail(snapshot = snapshot, peerSuffix = targetPeerSuffix)
        if (
            progress.recoverySendRequested &&
                !progress.completionLogged &&
                snapshot.session.lastOutcomeSummary == "SendResult.Sent" &&
                deliveryCount >= REQUIRED_PAUSE_RESUME_DELIVERY_COUNT &&
                deliveryDetail != null
        ) {
            actions.emitAutomationLog(
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
            actions.emitAutomationLog(
                "REFERENCE_AUTOMATION trust.reset.requested role=sender peer=$targetPeerSuffix"
            )
            actions.requestForgetPeer(targetPeer.peerId)
            progress.trustResetRequested = true
            return
        }
        if (
            progress.trustResetRequested &&
                !progress.trustResetObserved &&
                hasTrustResetRecoveryReady(snapshot, peerSuffix = targetPeerSuffix)
        ) {
            actions.emitAutomationLog(
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
            actions.emitAutomationLog(
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
            actions.emitAutomationLog(
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
            actions.emitAutomationLog(
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
        val payloadText = payloadPlan.payload(actions.platformName)
        val priority = payloadPlan.priority
        val payloadBytes = payloadText.encodeToByteArray().size
        actions.emitAutomationLog(
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
        actions.requestSendSamplePayload(
            peerId = targetPeer.peerId,
            payloadText = payloadText,
            priority = priority,
        )
    }

    private fun emitSenderFailure(
        snapshot: ReferenceControllerSnapshot,
        targetPeerSuffix: String?,
    ): Unit {
        val latestObservation =
            latestAutomationObservation(snapshot = snapshot, peerSuffix = targetPeerSuffix)
        actions.emitAutomationLog(
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
        actions.emitAutomationLog(
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
        actions.emitAutomationLog(
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
        actions.emitAutomationLog(
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
                    requiredLargestInboundBytes = largeTransferPayloadBytes(actions.platformName),
                )
            ReferenceAutomationScenario.DIRECT_PAUSE_RESUME ->
                runPassiveBaselineAutomationStep(
                    snapshot = snapshot,
                    timelineUiState = timelineUiState,
                    requiredInboundCount = REQUIRED_PAUSE_RESUME_INBOUND_COUNT,
                )
            ReferenceAutomationScenario.RELAY_CONSTRAINED,
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
            actions.emitAutomationLog(
                "REFERENCE_AUTOMATION session.end.requested role=passive inboundCount=$inboundCount"
            )
            actions.requestEndCurrentSession()
            progress.retainRequested = true
        }

        if (
            shouldExportPassiveLiveProof(
                retainRequested = progress.retainRequested,
                exportRequested = progress.exportRequested,
                retainedSessionCount = timelineUiState.retainedSessions.size,
            )
        ) {
            actions.emitAutomationLog(
                "REFERENCE_AUTOMATION export.requested role=passive policy=redacted-preview"
            )
            actions.requestExportCurrentSession(ExportPayloadPolicy.REDACTED_PREVIEW)
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
            actions.emitAutomationLog(
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
            actions.emitAutomationLog(
                "REFERENCE_AUTOMATION export.requested role=passive policy=full-payload"
            )
            actions.requestExportCurrentSession(ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN)
            progress.fullExportRequested = true
        }
        trackExportPath(
            currentExportPath = timelineUiState.lastExportPath,
            targetPolicy = ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN,
        )

        if (progress.fullExportPath != null && !progress.retainRequested) {
            actions.emitAutomationLog(
                "REFERENCE_AUTOMATION session.end.requested role=passive inboundCount=$inboundCount"
            )
            actions.requestEndCurrentSession()
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
            actions.emitAutomationLog(
                "REFERENCE_AUTOMATION export.requested role=passive policy=redacted-preview"
            )
            actions.requestExportCurrentSession(ExportPayloadPolicy.REDACTED_PREVIEW)
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
            actions.emitAutomationLog(
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
                actions.emitAutomationLog(
                    "REFERENCE_AUTOMATION export.completed role=passive policy=redacted-preview path=$currentExportPath"
                )
                return progress.redactedExportPath
            }
            ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN -> {
                if (currentExportPath == progress.redactedExportPath) {
                    return progress.fullExportPath
                }
                progress.fullExportPath = currentExportPath
                actions.emitAutomationLog(
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
        actions.emitAutomationLog(
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
        actions.emitAutomationLog(
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
    var announced: Boolean = false
    var peerAnnounced: Boolean = false
    var meshStartRequested: Boolean = false
    var bootstrapRequested: Boolean = false
    var sendRequested: Boolean = false
    var pauseRequested: Boolean = false
    var pauseObserved: Boolean = false
    var pauseResumeTargetPeerId: String? = null
    var pauseResumeTargetPeerSuffix: String? = null
    var resumeRequested: Boolean = false
    var resumeObserved: Boolean = false
    var trustResetRequested: Boolean = false
    var trustResetObserved: Boolean = false
    var recoverySendRequested: Boolean = false
    var retainRequested: Boolean = false
    var exportRequested: Boolean = false
    var fullExportRequested: Boolean = false
    var fullExportPath: String? = null
    var redactedExportPath: String? = null
    var lastObservedExportPath: String? = null
    var completionLogged: Boolean = false
    var lastPeerSnapshotSummary: String? = null
    var lastBootstrapObservationEntryId: String? = null
    var lastSenderObservationEntryId: String? = null
    var lastPassiveObservationEntryId: String? = null
    var lastRelayObservationEntryId: String? = null
    var lastSenderOutcomeSummary: String? = null
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

private const val REQUIRED_PAUSE_RESUME_DELIVERY_COUNT: Int = 2
private const val REQUIRED_PAUSE_RESUME_INBOUND_COUNT: Int = 2
private const val REQUIRED_RECOVERY_DELIVERY_COUNT: Int = 2
private const val REQUIRED_RECOVERY_INBOUND_COUNT: Int = 2
