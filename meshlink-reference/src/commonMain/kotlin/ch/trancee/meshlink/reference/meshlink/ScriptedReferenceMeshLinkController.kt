package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Deterministic reference-app controller used by host-platform UI automation. */
public class ScriptedReferenceMeshLinkController(
    private val platformName: String,
    private val authorityMode: ReferenceAuthorityMode,
    private val nowProvider: () -> Long,
    private val appId: String = "demo.meshlink.reference.automation",
) : ReferenceMeshLinkController {
    private val startedAtEpochMillis: Long = nowProvider()
    private val sessionId: String = "automation-${platformName.lowercase()}-$startedAtEpochMillis"
    private val scriptedPeerId: String = "automation-peer-654321"
    private val scriptedPeerSuffix: String = scriptedPeerId.takeLast(6)
    private val stateFlow: MutableStateFlow<ReferenceControllerSnapshot> =
        MutableStateFlow(
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = sessionId,
                        scenarioId = "guided-first-exchange",
                        authorityMode = authorityMode,
                        startedAtEpochMillis = startedAtEpochMillis,
                        meshStateLabel = MeshLinkState.Uninitialized.toString(),
                        configurationSnapshot =
                            mapOf(
                                "platform" to platformName,
                                "surface" to "main",
                                "appId" to appId,
                                "regulatoryRegion" to "DEFAULT",
                                "powerMode" to "Automatic",
                                "deliveryRetryDeadline" to "15s",
                            ),
                        historyStatus = ReferenceHistoryStatus.LIVE,
                    ),
                peers = emptyList(),
                timeline =
                    listOf(
                        timelineEntry(
                            index = 1,
                            family = TimelineFamily.USER,
                            severity = TimelineSeverity.INFO,
                            title = "Automation session created",
                            detail =
                                "A deterministic scripted controller is active for $platformName UI automation.",
                        )
                    ),
                activePowerModeLabel = "Automatic",
            )
        )

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = stateFlow.asStateFlow()

    override suspend fun start(): Unit {
        val currentState = stateFlow.value.session.meshStateLabel
        if (currentState == MeshLinkState.Running.toString()) {
            appendEvent(
                family = TimelineFamily.LIFECYCLE,
                severity = TimelineSeverity.INFO,
                title = "Mesh already running",
                detail = "The scripted automation mesh was already running.",
            )
            return
        }

        updateSession(
            meshStateLabel = MeshLinkState.Running.toString(),
            lastOutcomeSummary = "StartResult.Started",
        )
        appendEvent(
            family = TimelineFamily.LIFECYCLE,
            severity = TimelineSeverity.SUCCESS,
            title = "Mesh started",
            detail = "The scripted automation mesh moved into Running.",
        )
        ensurePeerAvailable()
    }

    override suspend fun pause(): Unit {
        updateSession(
            meshStateLabel = MeshLinkState.Paused.toString(),
            lastOutcomeSummary = "PauseResult.Paused",
        )
        appendEvent(
            family = TimelineFamily.LIFECYCLE,
            severity = TimelineSeverity.INFO,
            title = "Mesh paused",
            detail = "The scripted automation mesh moved into Paused.",
        )
    }

    override suspend fun resume(): Unit {
        updateSession(
            meshStateLabel = MeshLinkState.Running.toString(),
            lastOutcomeSummary = "ResumeResult.Resumed",
        )
        appendEvent(
            family = TimelineFamily.LIFECYCLE,
            severity = TimelineSeverity.SUCCESS,
            title = "Mesh resumed",
            detail = "The scripted automation mesh returned to Running.",
        )
        ensurePeerAvailable()
    }

    override suspend fun stop(): Unit {
        updateSession(
            meshStateLabel = MeshLinkState.Stopped.toString(),
            lastOutcomeSummary = "StopResult.Stopped",
        )
        updatePeers { peers ->
            peers.map { peer ->
                peer.copy(connectionState = PeerConnectionSnapshotState.DISCONNECTED)
            }
        }
        appendEvent(
            family = TimelineFamily.LIFECYCLE,
            severity = TimelineSeverity.INFO,
            title = "Mesh stopped",
            detail = "The scripted automation mesh moved into Stopped.",
        )
    }

    override suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit {
        if (peerId != scriptedPeerId) {
            appendEvent(
                family = TimelineFamily.MESSAGE,
                severity = TimelineSeverity.ERROR,
                title = "Send failed",
                detail = "The scripted controller could not find ${peerId.takeLast(6)}.",
                peerSuffix = peerId.takeLast(6),
            )
            return
        }

        if (stateFlow.value.session.meshStateLabel != MeshLinkState.Running.toString()) {
            appendEvent(
                family = TimelineFamily.MESSAGE,
                severity = TimelineSeverity.ERROR,
                title = "Send blocked",
                detail = "The scripted automation mesh must be Running before a send can proceed.",
                peerSuffix = scriptedPeerSuffix,
            )
            return
        }

        ensurePeerAvailable()
        promotePeerTrust()
        val payloadBytes = payloadText.encodeToByteArray().size
        if (payloadBytes > LARGE_TRANSFER_THRESHOLD_BYTES) {
            appendEvent(
                family = TimelineFamily.TRANSFER,
                severity = TimelineSeverity.SUCCESS,
                title = "Large transfer completed",
                detail =
                    "Transferred $payloadBytes bytes to $scriptedPeerSuffix with $priority priority.",
                peerSuffix = scriptedPeerSuffix,
                payloadPreview = payloadText.take(PAYLOAD_PREVIEW_CHARACTERS),
            )
            updatePeerOutcome("Large transfer complete ($payloadBytes bytes)")
            updateSession(
                meshStateLabel = MeshLinkState.Running.toString(),
                lastOutcomeSummary = "Large transfer complete",
                selectedPeerId = scriptedPeerId,
            )
            return
        }

        appendEvent(
            family = TimelineFamily.MESSAGE,
            severity = TimelineSeverity.SUCCESS,
            title = "Guided message sent",
            detail = "Sent $payloadBytes bytes to $scriptedPeerSuffix with $priority priority.",
            peerSuffix = scriptedPeerSuffix,
            payloadPreview = payloadText.take(PAYLOAD_PREVIEW_CHARACTERS),
        )
        appendEvent(
            family = TimelineFamily.MESSAGE,
            severity = TimelineSeverity.SUCCESS,
            title = "Delivery confirmed",
            detail = "The scripted peer acknowledged the latest guided payload.",
            peerSuffix = scriptedPeerSuffix,
            payloadPreview = payloadText.take(PAYLOAD_PREVIEW_CHARACTERS),
        )
        updatePeerOutcome("Delivery confirmed")
        updateSession(
            meshStateLabel = MeshLinkState.Running.toString(),
            lastOutcomeSummary = "SendResult.Sent",
            selectedPeerId = scriptedPeerId,
        )
    }

    override suspend fun forgetPeer(peerId: String): Unit {
        if (peerId != scriptedPeerId) {
            return
        }
        updatePeers { peers ->
            peers.map { peer ->
                if (peer.peerId == peerId) {
                    peer.copy(trustState = PeerTrustState.FORGOTTEN)
                } else {
                    peer
                }
            }
        }
        appendEvent(
            family = TimelineFamily.PEER,
            severity = TimelineSeverity.INFO,
            title = "Peer trust reset",
            detail =
                "The scripted peer $scriptedPeerSuffix was forgotten and must be trusted again.",
            peerSuffix = scriptedPeerSuffix,
        )
        updateSession(
            meshStateLabel = stateFlow.value.session.meshStateLabel,
            lastOutcomeSummary = "ForgetPeerResult.Forgotten",
            selectedPeerId = scriptedPeerId,
        )
    }

    private fun ensurePeerAvailable(): Unit {
        if (stateFlow.value.peers.any { peer -> peer.peerId == scriptedPeerId }) {
            updatePeers { peers ->
                peers.map { peer ->
                    if (peer.peerId == scriptedPeerId) {
                        peer.copy(
                            connectionState = PeerConnectionSnapshotState.CONNECTED,
                            lastSeenAtEpochMillis = nowProvider(),
                        )
                    } else {
                        peer
                    }
                }
            }
            return
        }

        updatePeers { peers ->
            peers +
                PeerSnapshot(
                    peerId = scriptedPeerId,
                    peerSuffix = scriptedPeerSuffix,
                    trustState = PeerTrustState.UNKNOWN,
                    connectionState = PeerConnectionSnapshotState.CONNECTED,
                    lastSeenAtEpochMillis = nowProvider(),
                    capabilityNotes = listOf("Scripted UI automation peer"),
                )
        }
        appendEvent(
            family = TimelineFamily.PEER,
            severity = TimelineSeverity.SUCCESS,
            title = "Peer found",
            detail = "Discovered scripted peer $scriptedPeerSuffix for the guided exchange.",
            peerSuffix = scriptedPeerSuffix,
        )
    }

    private fun promotePeerTrust(): Unit {
        val peer =
            stateFlow.value.peers.firstOrNull { existing -> existing.peerId == scriptedPeerId }
        if (peer?.trustState == PeerTrustState.TRUSTED) {
            return
        }
        updatePeers { peers ->
            peers.map { existing ->
                if (existing.peerId == scriptedPeerId) {
                    existing.copy(trustState = PeerTrustState.TRUSTED)
                } else {
                    existing
                }
            }
        }
        appendEvent(
            family = TimelineFamily.DIAGNOSTIC,
            severity = TimelineSeverity.SUCCESS,
            title = "Trust established",
            detail = "The scripted peer $scriptedPeerSuffix is now treated as trusted.",
            peerSuffix = scriptedPeerSuffix,
        )
    }

    private fun updatePeerOutcome(lastDeliveryOutcome: String): Unit {
        updatePeers { peers ->
            peers.map { peer ->
                if (peer.peerId == scriptedPeerId) {
                    peer.copy(
                        trustState =
                            if (peer.trustState == PeerTrustState.FORGOTTEN) {
                                PeerTrustState.TRUSTED
                            } else {
                                peer.trustState
                            },
                        lastDeliveryOutcome = lastDeliveryOutcome,
                        lastSeenAtEpochMillis = nowProvider(),
                    )
                } else {
                    peer
                }
            }
        }
    }

    private fun updatePeers(transform: (List<PeerSnapshot>) -> List<PeerSnapshot>): Unit {
        stateFlow.value = stateFlow.value.copy(peers = transform(stateFlow.value.peers))
    }

    private fun updateSession(
        meshStateLabel: String = stateFlow.value.session.meshStateLabel,
        lastOutcomeSummary: String? = stateFlow.value.session.lastOutcomeSummary,
        selectedPeerId: String? = stateFlow.value.session.selectedPeerId,
    ): Unit {
        stateFlow.value =
            stateFlow.value.copy(
                session =
                    stateFlow.value.session.copy(
                        meshStateLabel = meshStateLabel,
                        lastOutcomeSummary = lastOutcomeSummary,
                        selectedPeerId = selectedPeerId,
                    )
            )
    }

    private fun appendEvent(
        family: TimelineFamily,
        severity: TimelineSeverity,
        title: String,
        detail: String,
        peerSuffix: String? = null,
        payloadPreview: String? = null,
    ): Unit {
        val current = stateFlow.value
        val nextIndex = current.timeline.size + 1
        stateFlow.value =
            current.copy(
                timeline =
                    current.timeline +
                        timelineEntry(
                            index = nextIndex,
                            family = family,
                            severity = severity,
                            title = title,
                            detail = detail,
                            peerSuffix = peerSuffix,
                            payloadPreview = payloadPreview,
                        )
            )
    }

    private fun timelineEntry(
        index: Int,
        family: TimelineFamily,
        severity: TimelineSeverity,
        title: String,
        detail: String,
        peerSuffix: String? = null,
        payloadPreview: String? = null,
    ): TimelineEntry {
        return TimelineEntry(
            entryId = "$sessionId-$index",
            sessionId = sessionId,
            occurredAtEpochMillis = nowProvider(),
            family = family,
            severity = severity,
            title = title,
            detail = detail,
            peerSuffix = peerSuffix,
            searchText = listOf(title, detail, peerSuffix.orEmpty()).joinToString(" "),
            payloadPreview = payloadPreview,
        )
    }

    private companion object {
        private const val LARGE_TRANSFER_THRESHOLD_BYTES: Int = 4_096
        private const val PAYLOAD_PREVIEW_CHARACTERS: Int = 96
    }
}
