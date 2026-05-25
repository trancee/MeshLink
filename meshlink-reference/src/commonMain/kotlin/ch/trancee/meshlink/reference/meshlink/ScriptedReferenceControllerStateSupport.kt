package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

internal fun createScriptedReferenceInitialSnapshot(
    platformName: String,
    authorityMode: ReferenceAuthorityMode,
    nowProvider: () -> Long,
    appId: String,
    surfaceOfOrigin: String,
    sessionId: String,
): ReferenceControllerSnapshot {
    val startedAtEpochMillis = nowProvider()
    return ReferenceControllerSnapshot(
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
                        "surface" to surfaceOfOrigin,
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
                ReferenceTimelineEvent(
                        family = TimelineFamily.USER,
                        severity = TimelineSeverity.INFO,
                        title = "Automation session created",
                        detail =
                            "A deterministic scripted controller is active for $platformName UI automation.",
                    )
                    .toTimelineEntry(
                        sessionId = sessionId,
                        entryIndex = 1,
                        occurredAtEpochMillis = startedAtEpochMillis,
                    )
            ),
        activePowerModeLabel = "Automatic",
    )
}

internal fun ensureScriptedPeerAvailable(
    stateStore: ReferenceControllerStateStore,
    nowProvider: () -> Long,
    scriptedPeerId: String,
    scriptedPeerSuffix: String,
): Unit {
    if (stateStore.currentSnapshot.peers.any { peer -> peer.peerId == scriptedPeerId }) {
        stateStore.updatePeers { peers ->
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

    stateStore.updatePeers { peers ->
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
    stateStore.appendEvent(
        ReferenceTimelineEvent(
            family = TimelineFamily.PEER,
            severity = TimelineSeverity.SUCCESS,
            title = "Peer found",
            detail = "Discovered scripted peer $scriptedPeerSuffix for the guided exchange.",
            peerSuffix = scriptedPeerSuffix,
        )
    )
}

internal fun promoteScriptedPeerTrust(
    stateStore: ReferenceControllerStateStore,
    scriptedPeerId: String,
    scriptedPeerSuffix: String,
): Unit {
    val peer =
        stateStore.currentSnapshot.peers.firstOrNull { existing ->
            existing.peerId == scriptedPeerId
        }
    if (peer?.trustState == PeerTrustState.TRUSTED) {
        return
    }
    stateStore.updatePeers { peers ->
        peers.map { existing ->
            if (existing.peerId == scriptedPeerId) {
                existing.copy(trustState = PeerTrustState.TRUSTED)
            } else {
                existing
            }
        }
    }
    stateStore.appendEvent(
        ReferenceTimelineEvent(
            family = TimelineFamily.DIAGNOSTIC,
            severity = TimelineSeverity.SUCCESS,
            title = "Trust established",
            detail = "The scripted peer $scriptedPeerSuffix is now treated as trusted.",
            peerSuffix = scriptedPeerSuffix,
        )
    )
}

internal fun updateScriptedPeerOutcome(
    stateStore: ReferenceControllerStateStore,
    nowProvider: () -> Long,
    scriptedPeerId: String,
    lastDeliveryOutcome: String,
): Unit {
    stateStore.updatePeers { peers ->
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
