package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.redactedSuffix
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveProofAutomationStepRunnerTest {
    @Test
    fun passiveStartupTimeoutReportsExactReasonAndSuppressesDownstreamClaims() {
        // Arrange
        val actions = RecordingLiveProofAutomationActions()
        val progress = LiveProofAutomationProgress()
        val automationConfig =
            ReferenceAutomationConfig(
                mode = ReferenceAutomationMode.LIVE_PROOF,
                role = ReferenceAutomationRole.PASSIVE,
                appId = "demo.meshlink.reference.test",
                storageSubdirectory = "passive-timeout-tests",
            )
        val snapshot =
            automationTestSnapshot(
                sessionId = "session-passive-timeout",
                meshStateLabel = "Starting",
                lastOutcomeSummary = null,
                peers = emptyList(),
                timeline = emptyList(),
            )
        val timelineUiState =
            TechnicalTimelineUiState(
                liveSnapshot = snapshot,
                retainedSessions = emptyList(),
                lastExportPath = null,
            )

        // Act
        val coordinator =
            LiveProofAutomationCoordinator(
                automationConfig = automationConfig,
                actions = actions,
                progress = progress,
            )
        coordinator.run(snapshot = snapshot, timelineUiState = timelineUiState)

        // Assert
        assertTrue(
            actions.logs.any { log ->
                log.contains("Android passive transport did not start within 18.0 seconds") ||
                    log.contains("passive transport did not start")
            }
        )
        assertTrue(actions.logs.none { log -> log.contains("sender.started") })
        assertTrue(actions.logs.none { log -> log.contains("peer.discovered") })
        assertTrue(actions.logs.none { log -> log.contains("proof.complete") })
    }

    @Test
    fun requestSenderPayloadRecordsPlanMetadataAndUsesTheExpectedPayload() {
        // Arrange
        val targetPeer = AutoSendTargetPeer(peerId = "relay-target-abcdef", peerSuffix = "abcdef")
        val automationConfig = senderAutomationConfig(targetPeerId = targetPeer.peerId)
        val actions = RecordingLiveProofAutomationActions(platformName = "iOS")

        // Act
        requestSenderPayload(
            phase = "primary",
            targetPeer = targetPeer,
            payloadPlan = SenderPayloadPlan.LARGE_TRANSFER,
            automationConfig = automationConfig,
            actions = actions,
        )

        // Assert
        assertEquals(1, actions.sendRequests.size)
        assertEquals(targetPeer.peerId, actions.sendRequests.single().peerId)
        assertEquals(
            largeTransferPayloadBytes("iOS"),
            actions.sendRequests.single().payloadText.encodeToByteArray().size,
        )
        assertEquals(
            SenderPayloadPlan.LARGE_TRANSFER.priority,
            actions.sendRequests.single().priority,
        )
        assertTrue(actions.logs.any { log -> log.contains("payload=large-transfer") })
        assertTrue(actions.logs.any { log -> log.contains("targetPeerId=${targetPeer.peerId}") })
    }

    @Test
    fun senderObservationHelpersLogBootstrapSenderOutcomeAndFailureOnce() {
        // Arrange
        val peerId = "peer-abc123"
        val peerSuffix = redactedSuffix(peerId)
        val snapshot =
            automationTestSnapshot(
                meshStateLabel = "Running",
                timeline =
                    listOf(
                        automationTestEntry(
                            entryId = "session-1-1",
                            title = "ROUTE_AVAILABLE",
                            detail = "peerId=$peerId routeAvailable=true",
                            family = TimelineFamily.DIAGNOSTIC,
                            peerSuffix = peerSuffix,
                        )
                    ),
            )
        val failureSnapshot =
            snapshot.copy(
                session =
                    snapshot.session.copy(
                        lastOutcomeSummary = "SendResult.NotSent(reason=UNREACHABLE)"
                    )
            )
        val actions = RecordingLiveProofAutomationActions()
        val progress = LiveProofAutomationProgress().apply { bootstrapRequested = true }
        val bootstrapPeer = AutoSendTargetPeer(peerId = peerId, peerSuffix = peerSuffix)

        // Act
        announceBootstrapObservationIfNeeded(snapshot, bootstrapPeer, actions, progress)
        announceBootstrapObservationIfNeeded(snapshot, bootstrapPeer, actions, progress)
        progress.sendRequested = true
        announceSenderObservationIfNeeded(snapshot, peerSuffix, actions, progress)
        announceSenderObservationIfNeeded(snapshot, peerSuffix, actions, progress)
        announceSenderOutcomeIfNeeded(failureSnapshot, actions, progress)
        emitSenderFailure(failureSnapshot, peerSuffix, actions, progress)

        // Assert
        assertEquals(4, actions.logs.size)
        assertTrue(actions.logs[0].contains("bootstrap.observed"))
        assertTrue(actions.logs[1].contains("sender.observed"))
        assertTrue(
            actions.logs[2].contains(
                "sender.outcome role=sender summary=SendResult.NotSent(reason=UNREACHABLE)"
            )
        )
        assertTrue(actions.logs[3].contains("proof.failed role=sender"))
        assertTrue(progress.completionLogged)
    }

    @Test
    fun directSenderStepKeepsRetryingUnreachableUntilTheLinkSettles() {
        // Arrange
        val peerId = "peer-abc123"
        val peerSuffix = redactedSuffix(peerId)
        val actions = RecordingLiveProofAutomationActions()
        val progress = LiveProofAutomationProgress()
        val automationConfig = senderAutomationConfig()
        val waitingSnapshot =
            automationTestSnapshot(
                meshStateLabel = "Running",
                peers = listOf(automationTestPeer(peerId = peerId, peerSuffix = peerSuffix)),
                timeline = listOf(routeAvailableEntry(peerId, peerSuffix)),
            )
        val unreachableSnapshot =
            waitingSnapshot.copy(
                session =
                    waitingSnapshot.session.copy(
                        lastOutcomeSummary = "SendResult.NotSent(UNREACHABLE)"
                    )
            )
        val deliveredSnapshot =
            waitingSnapshot.copy(
                session = waitingSnapshot.session.copy(lastOutcomeSummary = "SendResult.Sent"),
                timeline =
                    waitingSnapshot.timeline +
                        deliverySucceededEntry(peerSuffix, entryId = "session-1-2"),
            )

        // Act
        runDirectSenderAutomationStep(
            waitingSnapshot,
            automationConfig,
            actions,
            progress,
            SenderPayloadPlan.GUIDED_HELLO,
        )
        runDirectSenderAutomationStep(
            unreachableSnapshot,
            automationConfig,
            actions,
            progress,
            SenderPayloadPlan.GUIDED_HELLO,
        )
        runDirectSenderAutomationStep(
            deliveredSnapshot,
            automationConfig,
            actions,
            progress,
            SenderPayloadPlan.GUIDED_HELLO,
        )

        // Assert
        assertEquals(1, actions.sendRequests.size)
        assertTrue(progress.sendRequested)
        assertTrue(progress.completionLogged)
        assertTrue(
            actions.logs.any { log -> log.contains("send.requested role=sender phase=primary") }
        )
        assertTrue(actions.logs.none { log -> log.contains("proof.failed role=sender") })
        assertTrue(actions.logs.any { log -> log.contains("proof.complete role=sender") })
    }

    @Test
    fun directSenderStepRequestsThePrimarySendAndCompletesAfterDelivery() {
        // Arrange
        val peerId = "peer-abc123"
        val peerSuffix = redactedSuffix(peerId)
        val actions = RecordingLiveProofAutomationActions()
        val progress = LiveProofAutomationProgress()
        val automationConfig = senderAutomationConfig()
        val initialSnapshot =
            automationTestSnapshot(
                meshStateLabel = "Running",
                peers = listOf(automationTestPeer(peerId = peerId, peerSuffix = peerSuffix)),
                timeline =
                    listOf(
                        automationTestEntry(
                            title = "ROUTE_AVAILABLE",
                            detail = "peerId=$peerId routeAvailable=true",
                            family = TimelineFamily.DIAGNOSTIC,
                            peerSuffix = peerSuffix,
                        )
                    ),
            )
        val completedSnapshot =
            initialSnapshot.copy(
                session = initialSnapshot.session.copy(lastOutcomeSummary = "SendResult.Sent"),
                timeline =
                    initialSnapshot.timeline +
                        automationTestEntry(
                            entryId = "session-1-2",
                            title = "DELIVERY_SUCCEEDED",
                            detail = "Delivered to $peerSuffix",
                            family = TimelineFamily.DIAGNOSTIC,
                            peerSuffix = peerSuffix,
                        ),
            )

        // Act
        runDirectSenderAutomationStep(
            initialSnapshot,
            automationConfig,
            actions,
            progress,
            SenderPayloadPlan.GUIDED_HELLO,
        )
        runDirectSenderAutomationStep(
            completedSnapshot,
            automationConfig,
            actions,
            progress,
            SenderPayloadPlan.GUIDED_HELLO,
        )

        // Assert
        assertEquals(1, actions.sendRequests.size)
        assertEquals(peerId, actions.sendRequests.single().peerId)
        assertTrue(progress.sendRequested)
        assertTrue(progress.completionLogged)
        assertTrue(
            actions.logs.any { log -> log.contains("send.requested role=sender phase=primary") }
        )
        assertTrue(
            actions.logs.any { log ->
                log.contains("proof.complete role=sender") && log.contains("peer=$peerSuffix")
            }
        )
    }

    @Test
    fun directSenderStepWaitsForPeersBeforeSending() {
        // Arrange
        val peerId = "peer-abc123"
        val peerSuffix = redactedSuffix(peerId)
        val actions = RecordingLiveProofAutomationActions()
        val progress = LiveProofAutomationProgress()
        val automationConfig = senderAutomationConfig()
        val emptySnapshot = automationTestSnapshot(meshStateLabel = "Running")
        val discoveredSnapshot =
            automationTestSnapshot(
                meshStateLabel = "Running",
                peers = listOf(automationTestPeer(peerId = peerId, peerSuffix = peerSuffix)),
                timeline = listOf(routeAvailableEntry(peerId, peerSuffix)),
            )

        // Act
        runDirectSenderAutomationStep(
            emptySnapshot,
            automationConfig,
            actions,
            progress,
            SenderPayloadPlan.GUIDED_HELLO,
        )
        runDirectSenderAutomationStep(
            discoveredSnapshot,
            automationConfig,
            actions,
            progress,
            SenderPayloadPlan.GUIDED_HELLO,
        )

        // Assert
        assertEquals(1, actions.sendRequests.size)
        assertEquals(peerId, actions.sendRequests.single().peerId)
        assertTrue(
            actions.logs.any { log -> log.contains("sender.waiting role=sender reason=no-peers") }
        )
        assertTrue(actions.logs.any { log -> log.contains("send.requested role=sender") })
        assertTrue(progress.senderPeerWaitLogged)
    }

    @Test
    fun directSenderStepBootstrapsWhenAnExplicitTargetPeerIsConfigured() {
        // Arrange
        val bootstrapPeerId = "bootstrap-peer-123456"
        val targetPeerId = "relay-target-abcdef"
        val bootstrapPeerSuffix = redactedSuffix(bootstrapPeerId)
        val actions = RecordingLiveProofAutomationActions()
        val progress = LiveProofAutomationProgress()
        val automationConfig = senderAutomationConfig(targetPeerId = targetPeerId)
        val emptySnapshot = automationTestSnapshot(meshStateLabel = "Running")
        val bootstrapSnapshot =
            automationTestSnapshot(
                meshStateLabel = "Running",
                peers =
                    listOf(
                        automationTestPeer(
                            peerId = bootstrapPeerId,
                            peerSuffix = bootstrapPeerSuffix,
                        )
                    ),
                timeline = listOf(routeAvailableEntry(bootstrapPeerId, bootstrapPeerSuffix)),
            )
        val routedSnapshot =
            automationTestSnapshot(
                meshStateLabel = "Running",
                peers =
                    listOf(
                        automationTestPeer(
                            peerId = bootstrapPeerId,
                            peerSuffix = bootstrapPeerSuffix,
                        ),
                        automationTestPeer(peerId = targetPeerId),
                    ),
                timeline = listOf(routeAvailableEntry(targetPeerId, redactedSuffix(targetPeerId))),
            )
        val completedSnapshot =
            routedSnapshot.copy(
                session = routedSnapshot.session.copy(lastOutcomeSummary = "SendResult.Sent"),
                timeline =
                    routedSnapshot.timeline +
                        deliverySucceededEntry(
                            redactedSuffix(targetPeerId),
                            entryId = "session-1-2",
                        ),
            )

        // Act
        runDirectSenderAutomationStep(
            emptySnapshot,
            automationConfig,
            actions,
            progress,
            SenderPayloadPlan.GUIDED_HELLO,
        )
        runDirectSenderAutomationStep(
            bootstrapSnapshot,
            automationConfig,
            actions,
            progress,
            SenderPayloadPlan.GUIDED_HELLO,
        )
        runDirectSenderAutomationStep(
            routedSnapshot,
            automationConfig,
            actions,
            progress,
            SenderPayloadPlan.GUIDED_HELLO,
        )
        runDirectSenderAutomationStep(
            completedSnapshot,
            automationConfig,
            actions,
            progress,
            SenderPayloadPlan.GUIDED_HELLO,
        )

        // Assert
        assertEquals(2, actions.sendRequests.size)
        assertEquals(bootstrapPeerId, actions.sendRequests.first().peerId)
        assertEquals(targetPeerId, actions.sendRequests.last().peerId)
        assertTrue(progress.bootstrapRequested)
        assertTrue(progress.sendRequested)
        assertTrue(progress.completionLogged)
        assertTrue(actions.logs.any { log -> log.contains("bootstrap.requested role=sender") })
        assertTrue(actions.logs.any { log -> log.contains("proof.complete role=sender") })
    }

    @Test
    fun pauseResumeSenderStepRequestsPauseResumeRecoveryAndCompletes() {
        // Arrange
        val peerId = "peer-abc123"
        val peerSuffix = redactedSuffix(peerId)
        val actions = RecordingLiveProofAutomationActions()
        val progress = LiveProofAutomationProgress()
        val automationConfig = senderAutomationConfig(targetPeerId = peerId)
        val runningSnapshot = senderSnapshot(peerId, peerSuffix)
        val deliveredSnapshot =
            runningSnapshot.copy(
                session = runningSnapshot.session.copy(lastOutcomeSummary = "SendResult.Sent"),
                timeline =
                    runningSnapshot.timeline +
                        routeAvailableEntry(peerId, peerSuffix, entryId = "session-1-2") +
                        deliverySucceededEntry(peerSuffix, entryId = "session-1-3"),
            )
        val pausedSnapshot =
            deliveredSnapshot.copy(
                session =
                    deliveredSnapshot.session.copy(
                        lastOutcomeSummary = "PauseResult.Paused",
                        meshStateLabel = "Paused",
                    ),
                timeline =
                    deliveredSnapshot.timeline +
                        automationTestEntry(
                            entryId = "session-1-4",
                            title = "Mesh paused",
                            detail = "Paused for proof automation.",
                            family = TimelineFamily.DIAGNOSTIC,
                        ),
            )
        val resumedSnapshot =
            deliveredSnapshot.copy(
                session =
                    deliveredSnapshot.session.copy(lastOutcomeSummary = "ResumeResult.Resumed"),
                timeline =
                    deliveredSnapshot.timeline +
                        automationTestEntry(
                            entryId = "session-1-5",
                            title = "Mesh resumed",
                            detail = "Resumed after proof pause.",
                            family = TimelineFamily.DIAGNOSTIC,
                        ),
            )
        val completedSnapshot =
            deliveredSnapshot.copy(
                session = deliveredSnapshot.session.copy(lastOutcomeSummary = "SendResult.Sent"),
                timeline =
                    deliveredSnapshot.timeline +
                        deliverySucceededEntry(peerSuffix, entryId = "session-1-6"),
            )

        // Act
        runPauseResumeSenderAutomationStep(runningSnapshot, automationConfig, actions, progress)
        runPauseResumeSenderAutomationStep(deliveredSnapshot, automationConfig, actions, progress)
        runPauseResumeSenderAutomationStep(pausedSnapshot, automationConfig, actions, progress)
        runPauseResumeSenderAutomationStep(resumedSnapshot, automationConfig, actions, progress)
        runPauseResumeSenderAutomationStep(completedSnapshot, automationConfig, actions, progress)

        // Assert
        assertEquals(2, actions.sendRequests.size)
        assertEquals(1, actions.meshPauseRequests)
        assertEquals(1, actions.meshResumeRequests)
        assertTrue(progress.recoverySendRequested)
        assertTrue(progress.completionLogged)
        assertTrue(actions.logs.any { log -> log.contains("pause.requested role=sender") })
        assertTrue(actions.logs.any { log -> log.contains("resume.requested role=sender") })
        assertTrue(
            actions.logs.any { log ->
                log.contains("proof.complete role=sender") && log.contains("deliveries=2")
            }
        )
    }

    @Test
    fun trustResetRecoverySenderStepRequestsForgetRecoveryAndCompletes() {
        // Arrange
        val peerId = "peer-abc123"
        val peerSuffix = redactedSuffix(peerId)
        val actions = RecordingLiveProofAutomationActions()
        val progress = LiveProofAutomationProgress()
        val automationConfig = senderAutomationConfig()
        val runningSnapshot = senderSnapshot(peerId, peerSuffix)
        val deliveredSnapshot =
            runningSnapshot.copy(
                session = runningSnapshot.session.copy(lastOutcomeSummary = "SendResult.Sent"),
                timeline =
                    runningSnapshot.timeline +
                        deliverySucceededEntry(peerSuffix, entryId = "session-1-2"),
            )
        val recoveryReadySnapshot =
            deliveredSnapshot.copy(
                timeline =
                    deliveredSnapshot.timeline +
                        automationTestEntry(
                            entryId = "session-1-3",
                            title = "Peer trust reset",
                            detail = "The scripted peer must be trusted again.",
                            family = TimelineFamily.DIAGNOSTIC,
                            peerSuffix = peerSuffix,
                        )
            )
        val completedSnapshot =
            deliveredSnapshot.copy(
                session = deliveredSnapshot.session.copy(lastOutcomeSummary = "SendResult.Sent"),
                timeline =
                    deliveredSnapshot.timeline +
                        deliverySucceededEntry(peerSuffix, entryId = "session-1-4"),
            )

        // Act
        runTrustResetRecoverySenderAutomationStep(
            runningSnapshot,
            automationConfig,
            actions,
            progress,
        )
        runTrustResetRecoverySenderAutomationStep(
            deliveredSnapshot,
            automationConfig,
            actions,
            progress,
        )
        runTrustResetRecoverySenderAutomationStep(
            recoveryReadySnapshot,
            automationConfig,
            actions,
            progress,
        )
        runTrustResetRecoverySenderAutomationStep(
            completedSnapshot,
            automationConfig,
            actions,
            progress,
        )

        // Assert
        assertEquals(2, actions.sendRequests.size)
        assertEquals(listOf(peerId), actions.forgetPeerRequests)
        assertTrue(progress.trustResetObserved)
        assertTrue(progress.completionLogged)
        assertTrue(actions.sendRequests.last().payloadText.contains("hello again"))
        assertTrue(actions.logs.any { log -> log.contains("trust.reset.requested role=sender") })
        assertTrue(
            actions.logs.any { log ->
                log.contains("proof.complete role=sender") && log.contains("deliveries=2")
            }
        )
    }

    @Test
    fun relaySenderStepBootstrapsThenSendsTheRoutedProof() {
        // Arrange
        val bootstrapPeerId = "bootstrap-peer-123456"
        val targetPeerId = "relay-target-abcdef"
        val bootstrapPeerSuffix = redactedSuffix(bootstrapPeerId)
        val targetPeerSuffix = redactedSuffix(targetPeerId)
        val actions = RecordingLiveProofAutomationActions()
        val progress = LiveProofAutomationProgress()
        val automationConfig =
            senderAutomationConfig(requiredPeerCount = 2, targetPeerId = targetPeerId)
        val bootstrapSnapshot =
            automationTestSnapshot(
                meshStateLabel = "Running",
                peers =
                    listOf(
                        automationTestPeer(
                            peerId = bootstrapPeerId,
                            peerSuffix = bootstrapPeerSuffix,
                        )
                    ),
                timeline = listOf(routeAvailableEntry(bootstrapPeerId, bootstrapPeerSuffix)),
            )
        val routedSnapshot =
            automationTestSnapshot(
                meshStateLabel = "Running",
                peers =
                    listOf(
                        automationTestPeer(
                            peerId = bootstrapPeerId,
                            peerSuffix = bootstrapPeerSuffix,
                        ),
                        automationTestPeer(peerId = targetPeerId, peerSuffix = targetPeerSuffix),
                    ),
                timeline = listOf(routeAvailableEntry(targetPeerId, targetPeerSuffix)),
            )
        val completedSnapshot =
            routedSnapshot.copy(
                session = routedSnapshot.session.copy(lastOutcomeSummary = "SendResult.Sent"),
                timeline =
                    routedSnapshot.timeline +
                        deliverySucceededEntry(targetPeerSuffix, entryId = "session-1-2"),
            )

        // Act
        runRelaySenderAutomationStep(bootstrapSnapshot, automationConfig, actions, progress)
        runRelaySenderAutomationStep(bootstrapSnapshot, automationConfig, actions, progress)
        runRelaySenderAutomationStep(routedSnapshot, automationConfig, actions, progress)
        runRelaySenderAutomationStep(completedSnapshot, automationConfig, actions, progress)

        // Assert
        assertEquals(2, actions.sendRequests.size)
        assertEquals(bootstrapPeerId, actions.sendRequests.first().peerId)
        assertEquals(targetPeerId, actions.sendRequests.last().peerId)
        assertTrue(progress.bootstrapRequested)
        assertTrue(progress.sendRequested)
        assertTrue(progress.completionLogged)
        assertTrue(
            actions.logs.any { log ->
                log.contains("bootstrap.requested role=sender") &&
                    log.contains("peer=$bootstrapPeerSuffix")
            }
        )
        assertTrue(
            actions.logs.any { log ->
                log.contains("proof.complete role=sender") && log.contains("peer=$targetPeerSuffix")
            }
        )
    }

    @Test
    fun passiveStepsDispatchByScenarioAndCompleteTheFullExportFlow() {
        // Arrange
        val snapshot =
            automationTestSnapshot(
                meshStateLabel = "Running",
                timeline =
                    listOf(
                        automationTestEntry(
                            title = "TRUST_ESTABLISHED",
                            detail = "Trust pinned.",
                            family = TimelineFamily.DIAGNOSTIC,
                            peerSuffix = "abc123",
                        ),
                        automationTestEntry(
                            entryId = "session-1-2",
                            title = "Inbound message",
                            detail = "Received 19 bytes from abc123.",
                            family = TimelineFamily.MESSAGE,
                            peerSuffix = "abc123",
                            payloadSizeBytes = largeTransferPayloadBytes("Android"),
                        ),
                        automationTestEntry(
                            entryId = "session-1-3",
                            title = "Inbound message",
                            detail = "Received another message from abc123.",
                            family = TimelineFamily.MESSAGE,
                            peerSuffix = "abc123",
                            payloadSizeBytes = 19,
                        ),
                    ),
            )
        val retainedSessions =
            listOf(
                retainedDriverSession(
                    sessionId = snapshot.session.sessionId,
                    endedAtEpochMillis = 5L,
                )
            )
        val fullExportPath = "reference/exports/${snapshot.session.sessionId}-full.json"
        val redactedExportPath = "reference/exports/${snapshot.session.sessionId}-redacted.json"
        val fullExportActions = RecordingLiveProofAutomationActions()
        val fullExportProgress = LiveProofAutomationProgress()
        val fullExportConfig =
            passiveAutomationConfig(ReferenceAutomationScenario.DIRECT_FULL_EXPORT)

        // Act
        runPassiveAutomationStep(
            snapshot,
            TechnicalTimelineUiState(liveSnapshot = snapshot),
            fullExportConfig,
            fullExportActions,
            fullExportProgress,
        )
        runPassiveAutomationStep(
            snapshot,
            TechnicalTimelineUiState(liveSnapshot = snapshot, lastExportPath = fullExportPath),
            fullExportConfig,
            fullExportActions,
            fullExportProgress,
        )
        runPassiveAutomationStep(
            snapshot,
            TechnicalTimelineUiState(
                liveSnapshot = snapshot,
                retainedSessions = retainedSessions,
                lastExportPath = fullExportPath,
            ),
            fullExportConfig,
            fullExportActions,
            fullExportProgress,
        )
        runPassiveAutomationStep(
            snapshot,
            TechnicalTimelineUiState(
                liveSnapshot = snapshot,
                retainedSessions = retainedSessions,
                lastExportPath = redactedExportPath,
            ),
            fullExportConfig,
            fullExportActions,
            fullExportProgress,
        )

        val guidedActions = RecordingLiveProofAutomationActions()
        runPassiveAutomationStep(
            snapshot,
            TechnicalTimelineUiState(liveSnapshot = snapshot),
            passiveAutomationConfig(ReferenceAutomationScenario.DIRECT_GUIDED),
            guidedActions,
            LiveProofAutomationProgress(),
        )
        runPassiveAutomationStep(
            snapshot,
            TechnicalTimelineUiState(liveSnapshot = snapshot),
            passiveAutomationConfig(ReferenceAutomationScenario.DIRECT_PAUSE_RESUME),
            RecordingLiveProofAutomationActions(),
            LiveProofAutomationProgress(),
        )
        runPassiveAutomationStep(
            snapshot,
            TechnicalTimelineUiState(liveSnapshot = snapshot),
            passiveAutomationConfig(ReferenceAutomationScenario.DIRECT_TRUST_RESET_RECOVERY),
            RecordingLiveProofAutomationActions(),
            LiveProofAutomationProgress(),
        )
        runPassiveAutomationStep(
            snapshot,
            TechnicalTimelineUiState(liveSnapshot = snapshot),
            passiveAutomationConfig(ReferenceAutomationScenario.DIRECT_LARGE_TRANSFER),
            RecordingLiveProofAutomationActions(platformName = "Android"),
            LiveProofAutomationProgress(),
        )
        val relayActions = RecordingLiveProofAutomationActions()
        runPassiveAutomationStep(
            snapshot,
            TechnicalTimelineUiState(liveSnapshot = snapshot),
            passiveAutomationConfig(ReferenceAutomationScenario.RELAY_CONSTRAINED),
            relayActions,
            LiveProofAutomationProgress(),
        )
        runRelayAutomationStep(snapshot, relayActions, LiveProofAutomationProgress())
        runRelayAutomationStep(
            snapshot,
            relayActions,
            LiveProofAutomationProgress().apply { lastRelayObservationEntryId = "session-1-3" },
        )

        // Assert
        assertEquals(
            listOf(ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN, ExportPayloadPolicy.REDACTED_PREVIEW),
            fullExportActions.exportRequests,
        )
        assertEquals(1, fullExportActions.endSessionRequests)
        assertTrue(fullExportProgress.completionLogged)
        assertTrue(fullExportActions.logs.any { log -> log.contains("policy=full-payload") })
        assertTrue(fullExportActions.logs.any { log -> log.contains("policy=redacted-preview") })
        assertTrue(
            fullExportActions.logs.any { log ->
                log.contains("proof.complete role=passive") &&
                    log.contains("fullExport=$fullExportPath") &&
                    log.contains("export=$redactedExportPath")
            }
        )
        assertEquals(1, guidedActions.endSessionRequests)
        assertTrue(relayActions.logs.any { log -> log.contains("relay.observed role=relay") })
    }

    @Test
    fun newResilienceScenarioIdsReuseTheBaselineDispatchPaths() {
        // Arrange
        val peerId = "peer-abc123"
        val peerSuffix = redactedSuffix(peerId)
        val senderSnapshot = senderSnapshot(peerId, peerSuffix)
        val completedSenderSnapshot =
            senderSnapshot.copy(
                session = senderSnapshot.session.copy(lastOutcomeSummary = "SendResult.Sent"),
                timeline =
                    senderSnapshot.timeline +
                        deliverySucceededEntry(peerSuffix, entryId = "session-1-2"),
            )
        val passiveSnapshot =
            automationTestSnapshot(
                meshStateLabel = "Running",
                timeline =
                    listOf(
                        automationTestEntry(
                            title = "TRUST_ESTABLISHED",
                            detail = "Trust pinned.",
                            family = TimelineFamily.DIAGNOSTIC,
                            peerSuffix = peerSuffix,
                        ),
                        automationTestEntry(
                            entryId = "session-1-2",
                            title = "Inbound message",
                            detail = "Received 19 bytes from $peerSuffix.",
                            family = TimelineFamily.MESSAGE,
                            peerSuffix = peerSuffix,
                            payloadSizeBytes = 19,
                        ),
                    ),
            )
        val retainedSessions =
            listOf(
                retainedDriverSession(
                    sessionId = passiveSnapshot.session.sessionId,
                    endedAtEpochMillis = 5L,
                )
            )
        val passiveTimelineUiState =
            TechnicalTimelineUiState(
                liveSnapshot = passiveSnapshot,
                retainedSessions = retainedSessions,
                lastExportPath =
                    "reference/exports/${passiveSnapshot.session.sessionId}-redacted.json",
            )
        val resilienceScenarios =
            listOf(
                ReferenceAutomationScenario.DIRECT_RESTART_RECOVERY,
                ReferenceAutomationScenario.DIRECT_ISOLATION_RECOVERY,
                ReferenceAutomationScenario.DIRECT_ROUTE_BREAK_RECOVERY,
            )

        // Act / Assert
        resilienceScenarios.forEach { scenario ->
            val senderActions = RecordingLiveProofAutomationActions()
            val senderProgress = LiveProofAutomationProgress()
            runSenderAutomationStep(
                senderSnapshot,
                senderAutomationConfig(scenario = scenario),
                senderActions,
                senderProgress,
            )
            runSenderAutomationStep(
                completedSenderSnapshot,
                senderAutomationConfig(scenario = scenario),
                senderActions,
                senderProgress,
            )
            assertEquals(1, senderActions.sendRequests.size)
            assertEquals(peerId, senderActions.sendRequests.single().peerId)
            assertTrue(senderProgress.completionLogged)
            assertTrue(
                senderActions.logs.any { log ->
                    log.contains("send.requested role=sender phase=primary")
                }
            )

            val passiveActions = RecordingLiveProofAutomationActions()
            val passiveProgress = LiveProofAutomationProgress()
            runPassiveAutomationStep(
                passiveSnapshot,
                passiveTimelineUiState,
                passiveAutomationConfig(scenario),
                passiveActions,
                passiveProgress,
            )
            assertEquals(
                listOf(ExportPayloadPolicy.REDACTED_PREVIEW),
                passiveActions.exportRequests,
            )
            assertEquals(1, passiveActions.endSessionRequests)
            assertTrue(passiveProgress.completionLogged)
            assertTrue(
                passiveActions.logs.any { log ->
                    log.contains("proof.complete role=passive") && log.contains("export=")
                }
            )
        }
    }

    @Test
    fun senderFailureLogNamesTheConcreteMissingEvidenceEvenWithoutSummaryMetadata() {
        // Arrange
        val snapshot = automationTestSnapshot(lastOutcomeSummary = null, timeline = emptyList())
        val actions = RecordingLiveProofAutomationActions()
        val progress = LiveProofAutomationProgress()

        // Act
        emitSenderFailure(
            snapshot = snapshot,
            targetPeerSuffix = null,
            actions = actions,
            progress = progress,
        )

        // Assert
        assertTrue(actions.logs.any { log -> log.contains("proof.failed role=sender") })
        assertTrue(actions.logs.any { log -> log.contains("observation=none") })
        assertTrue(actions.logs.any { log -> log.contains("detail=none") })
        assertTrue(actions.logs.any { log -> log.contains("outcome=") })
    }

    private fun senderAutomationConfig(
        scenario: ReferenceAutomationScenario = ReferenceAutomationScenario.DIRECT_GUIDED,
        requiredPeerCount: Int = 1,
        targetPeerId: String? = null,
    ): ReferenceAutomationConfig {
        return ReferenceAutomationConfig(
            mode = ReferenceAutomationMode.LIVE_PROOF,
            role = ReferenceAutomationRole.SENDER,
            appId = "demo.meshlink.reference.test",
            storageSubdirectory = "sender-tests",
            requiredPeerCount = requiredPeerCount,
            targetPeerId = targetPeerId,
            scenario = scenario,
        )
    }

    private fun passiveAutomationConfig(
        scenario: ReferenceAutomationScenario
    ): ReferenceAutomationConfig {
        return ReferenceAutomationConfig(
            mode = ReferenceAutomationMode.LIVE_PROOF,
            role = ReferenceAutomationRole.PASSIVE,
            appId = "demo.meshlink.reference.test",
            storageSubdirectory = "passive-tests",
            scenario = scenario,
        )
    }

    private fun senderSnapshot(peerId: String, peerSuffix: String) =
        automationTestSnapshot(
            meshStateLabel = "Running",
            peers = listOf(automationTestPeer(peerId = peerId, peerSuffix = peerSuffix)),
            timeline = listOf(routeAvailableEntry(peerId, peerSuffix)),
        )

    private fun routeAvailableEntry(
        peerId: String,
        peerSuffix: String,
        entryId: String = "session-1-1",
    ) =
        automationTestEntry(
            entryId = entryId,
            title = "ROUTE_AVAILABLE",
            detail = "peerId=$peerId routeAvailable=true",
            family = TimelineFamily.DIAGNOSTIC,
            peerSuffix = peerSuffix,
        )

    private fun deliverySucceededEntry(peerSuffix: String, entryId: String) =
        automationTestEntry(
            entryId = entryId,
            title = "DELIVERY_SUCCEEDED",
            detail = "Delivered to $peerSuffix",
            family = TimelineFamily.DIAGNOSTIC,
            peerSuffix = peerSuffix,
        )
}
