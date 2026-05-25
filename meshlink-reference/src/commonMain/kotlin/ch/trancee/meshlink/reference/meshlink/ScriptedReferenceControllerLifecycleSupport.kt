package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

internal fun startScriptedMesh(
    stateStore: ReferenceControllerStateStore,
    nowProvider: () -> Long,
    scriptedPeerId: String,
    scriptedPeerSuffix: String,
): Unit {
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

internal fun pauseScriptedMesh(stateStore: ReferenceControllerStateStore): Unit {
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

internal fun resumeScriptedMesh(
    stateStore: ReferenceControllerStateStore,
    nowProvider: () -> Long,
    scriptedPeerId: String,
    scriptedPeerSuffix: String,
): Unit {
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

internal fun stopScriptedMesh(stateStore: ReferenceControllerStateStore): Unit {
    stateStore.updateSession(
        meshStateLabel = MeshLinkState.Stopped.toString(),
        lastOutcomeSummary = "StopResult.Stopped",
    )
    stateStore.updatePeers { peers ->
        peers.map { peer -> peer.copy(connectionState = PeerConnectionSnapshotState.DISCONNECTED) }
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

internal fun forgetScriptedPeer(
    stateStore: ReferenceControllerStateStore,
    peerId: String,
    scriptedPeerId: String,
    scriptedPeerSuffix: String,
): Unit {
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
