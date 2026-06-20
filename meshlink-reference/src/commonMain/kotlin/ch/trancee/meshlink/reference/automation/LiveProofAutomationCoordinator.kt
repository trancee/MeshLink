package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
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
        announceSnapshotState(snapshot)
        announcePeerDiscoveryIfNeeded(snapshot)
        announcePeerSnapshotIfNeeded(snapshot)
        requestMeshStartIfNeeded(snapshot)

        when (automationConfig.role) {
            ReferenceAutomationRole.SENDER ->
                runSenderAutomationStep(snapshot, automationConfig, actions, progress)
            ReferenceAutomationRole.PASSIVE ->
                runPassiveAutomationStep(
                    snapshot = snapshot,
                    timelineUiState = timelineUiState,
                    automationConfig = automationConfig,
                    actions = actions,
                    progress = progress,
                )
            ReferenceAutomationRole.RELAY -> runRelayAutomationStep(snapshot, actions, progress)
        }
    }

    private fun announceSnapshotState(snapshot: ReferenceControllerSnapshot): Unit {
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION snapshot role=${automationConfig.role} peers=${snapshot.peers.size} " +
                "hasPeers=${snapshot.peers.isNotEmpty()} meshStartRequested=${progress.meshStartRequested} " +
                "peerAnnounced=${progress.peerAnnounced} announced=${progress.announced}"
        )
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
        val readinessBlockers = actions.readinessBlockers
        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION mesh.start.evaluate role=${automationConfig.role} meshStartRequested=${progress.meshStartRequested} meshState=${snapshot.session.meshStateLabel} readinessBlockers=${readinessBlockers.joinToString(separator = "|")}"
        )
        val shouldRequest =
            shouldRequestLiveProofMeshStart(
                meshStartRequested = progress.meshStartRequested,
                snapshot = snapshot,
                readinessBlockers = readinessBlockers,
                benchmarkTransport = automationConfig.benchmarkTransport,
            )
        if (!shouldRequest) {
            actions.emitAutomationLog(
                "REFERENCE_AUTOMATION mesh.start.skipped role=${automationConfig.role} meshStartRequested=${progress.meshStartRequested} meshState=${snapshot.session.meshStateLabel} benchmarkTransport=${automationConfig.benchmarkTransport} readinessBlockers=${readinessBlockers.joinToString(separator = "|")}"
            )
            return
        }

        actions.emitAutomationLog(
            "REFERENCE_AUTOMATION mesh.start.requested role=${automationConfig.role} meshState=${snapshot.session.meshStateLabel} readinessBlockers=${readinessBlockers.joinToString(separator = "|")}"
        )
        actions.requestMeshStart()
        progress.meshStartRequested = true
    }
}
