package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlinx.coroutines.flow.StateFlow

/** Deterministic reference-app controller used by host-platform UI automation. */
public class ScriptedReferenceMeshLinkController(
    private val platformName: String,
    private val authorityMode: ReferenceAuthorityMode,
    private val nowProvider: () -> Long,
    private val appId: String = "demo.meshlink.reference.automation",
    private val surfaceOfOrigin: String = "main-guided",
) : ReferenceMeshLinkController {
    private val startedAtEpochMillis: Long = nowProvider()
    private val sessionId: String = "automation-${platformName.lowercase()}-$startedAtEpochMillis"
    private val scriptedPeerId: String = "automation-peer-654321"
    private val scriptedPeerSuffix: String = redactedSuffix(scriptedPeerId)
    private val stateStore: ReferenceControllerStateStore =
        ReferenceControllerStateStore(
            initialSnapshot =
                createScriptedReferenceInitialSnapshot(
                    platformName = platformName,
                    authorityMode = authorityMode,
                    nowProvider = nowProvider,
                    appId = appId,
                    surfaceOfOrigin = surfaceOfOrigin,
                    sessionId = sessionId,
                ),
            sessionId = sessionId,
            nowProvider = nowProvider,
        )
    private val sendRecorder: ScriptedReferenceSendRecorder =
        ScriptedReferenceSendRecorder(
            stateStore = stateStore,
            scriptedPeerId = scriptedPeerId,
            scriptedPeerSuffix = scriptedPeerSuffix,
            updatePeerOutcome = { outcome ->
                updateScriptedPeerOutcome(
                    stateStore = stateStore,
                    nowProvider = nowProvider,
                    scriptedPeerId = scriptedPeerId,
                    lastDeliveryOutcome = outcome,
                )
            },
        )

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = stateStore.snapshot

    override suspend fun start(): Unit {
        val currentState = stateStore.currentSnapshot.session.meshStateLabel
        if (currentState == MeshLinkState.Running.toString()) {
            stateStore.appendEvent(
                ReferenceTimelineEvent(
                    family = TimelineFamily.LIFECYCLE,
                    severity = TimelineSeverity.INFO,
                    title = "Mesh already running",
                    detail = "The scripted automation mesh was already running.",
                )
            )
            return
        }

        stateStore.updateSession(
            meshStateLabel = MeshLinkState.Running.toString(),
            lastOutcomeSummary = "StartResult.Started",
        )
        stateStore.appendEvent(
            ReferenceTimelineEvent(
                family = TimelineFamily.LIFECYCLE,
                severity = TimelineSeverity.SUCCESS,
                title = "Mesh started",
                detail = "The scripted automation mesh moved into Running.",
            )
        )
        ensureScriptedPeerAvailable(
            stateStore = stateStore,
            nowProvider = nowProvider,
            scriptedPeerId = scriptedPeerId,
            scriptedPeerSuffix = scriptedPeerSuffix,
        )
    }

    override suspend fun pause(): Unit {
        stateStore.updateSession(
            meshStateLabel = MeshLinkState.Paused.toString(),
            lastOutcomeSummary = "PauseResult.Paused",
        )
        stateStore.appendEvent(
            ReferenceTimelineEvent(
                family = TimelineFamily.LIFECYCLE,
                severity = TimelineSeverity.INFO,
                title = "Mesh paused",
                detail = "The scripted automation mesh moved into Paused.",
            )
        )
    }

    override suspend fun resume(): Unit {
        stateStore.updateSession(
            meshStateLabel = MeshLinkState.Running.toString(),
            lastOutcomeSummary = "ResumeResult.Resumed",
        )
        stateStore.appendEvent(
            ReferenceTimelineEvent(
                family = TimelineFamily.LIFECYCLE,
                severity = TimelineSeverity.SUCCESS,
                title = "Mesh resumed",
                detail = "The scripted automation mesh returned to Running.",
            )
        )
        ensureScriptedPeerAvailable(
            stateStore = stateStore,
            nowProvider = nowProvider,
            scriptedPeerId = scriptedPeerId,
            scriptedPeerSuffix = scriptedPeerSuffix,
        )
    }

    override suspend fun stop(): Unit {
        stateStore.updateSession(
            meshStateLabel = MeshLinkState.Stopped.toString(),
            lastOutcomeSummary = "StopResult.Stopped",
        )
        stateStore.updatePeers { peers ->
            peers.map { peer ->
                peer.copy(connectionState = PeerConnectionSnapshotState.DISCONNECTED)
            }
        }
        stateStore.appendEvent(
            ReferenceTimelineEvent(
                family = TimelineFamily.LIFECYCLE,
                severity = TimelineSeverity.INFO,
                title = "Mesh stopped",
                detail = "The scripted automation mesh moved into Stopped.",
            )
        )
    }

    override suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit {
        val blocker =
            sendRecorder.blockerFor(
                peerId = peerId,
                meshStateLabel = stateStore.currentSnapshot.session.meshStateLabel,
            )
        if (blocker != null) {
            sendRecorder.recordBlockedSend(peerId = peerId, blocker = blocker)
            return
        }

        ensureScriptedPeerAvailable(
            stateStore = stateStore,
            nowProvider = nowProvider,
            scriptedPeerId = scriptedPeerId,
            scriptedPeerSuffix = scriptedPeerSuffix,
        )
        promoteScriptedPeerTrust(
            stateStore = stateStore,
            scriptedPeerId = scriptedPeerId,
            scriptedPeerSuffix = scriptedPeerSuffix,
        )
        sendRecorder.recordCompletion(
            payloadText = payloadText,
            priority = priority,
            largeTransferThresholdBytes = LARGE_TRANSFER_THRESHOLD_BYTES,
            payloadPreviewCharacters = PAYLOAD_PREVIEW_CHARACTERS,
        )
    }

    override suspend fun forgetPeer(peerId: String): Unit {
        if (peerId != scriptedPeerId) {
            return
        }
        stateStore.updatePeers { peers ->
            peers.map { peer ->
                if (peer.peerId == peerId) {
                    peer.copy(trustState = PeerTrustState.FORGOTTEN)
                } else {
                    peer
                }
            }
        }
        stateStore.appendEvent(
            ReferenceTimelineEvent(
                family = TimelineFamily.PEER,
                severity = TimelineSeverity.INFO,
                title = "Peer trust reset",
                detail =
                    "The scripted peer $scriptedPeerSuffix was forgotten and must be trusted again.",
                peerSuffix = scriptedPeerSuffix,
            )
        )
        stateStore.updateSession(
            meshStateLabel = stateStore.currentSnapshot.session.meshStateLabel,
            lastOutcomeSummary = "ForgetPeerResult.Forgotten",
            selectedPeerId = scriptedPeerId,
        )
    }

    override suspend fun close(): Unit = Unit

    private companion object {
        private const val LARGE_TRANSFER_THRESHOLD_BYTES: Int = 4_096
        private const val PAYLOAD_PREVIEW_CHARACTERS: Int = 96
    }
}
