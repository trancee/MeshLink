package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.redactedSuffix
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

internal fun automationTestSnapshot(
    sessionId: String = "session-1",
    meshStateLabel: String = "Uninitialized",
    lastOutcomeSummary: String? = null,
    peers: List<PeerSnapshot> = emptyList(),
    timeline: List<TimelineEntry> = emptyList(),
): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = sessionId,
                scenarioId = "guided-first-exchange",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1L,
                meshStateLabel = meshStateLabel,
                lastOutcomeSummary = lastOutcomeSummary,
            ),
        peers = peers,
        timeline = timeline,
        activePowerModeLabel = "Automatic",
    )
}

internal fun automationTestPeer(
    peerId: String,
    peerSuffix: String = redactedSuffix(peerId),
    trustState: PeerTrustState = PeerTrustState.UNKNOWN,
    connectionState: PeerConnectionSnapshotState = PeerConnectionSnapshotState.CONNECTED,
): PeerSnapshot {
    return PeerSnapshot(
        peerId = peerId,
        peerSuffix = peerSuffix,
        trustState = trustState,
        connectionState = connectionState,
    )
}

internal fun automationTestEntry(
    title: String,
    detail: String,
    family: TimelineFamily,
    entryId: String = "session-1-1",
    sessionId: String = "session-1",
    occurredAtEpochMillis: Long = 2L,
    severity: TimelineSeverity = TimelineSeverity.INFO,
    peerSuffix: String? = null,
    payloadSizeBytes: Int? = null,
): TimelineEntry {
    return TimelineEntry(
        entryId = entryId,
        sessionId = sessionId,
        occurredAtEpochMillis = occurredAtEpochMillis,
        family = family,
        severity = severity,
        title = title,
        detail = detail,
        peerSuffix = peerSuffix,
        payloadSizeBytes = payloadSizeBytes,
    )
}
