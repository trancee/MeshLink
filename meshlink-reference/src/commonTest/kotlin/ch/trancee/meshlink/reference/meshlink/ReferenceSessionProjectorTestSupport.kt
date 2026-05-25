package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

internal fun referenceStateStore(
    lastOutcomeSummary: String? = null,
    trustState: PeerTrustState = PeerTrustState.UNKNOWN,
): ReferenceControllerStateStore {
    return ReferenceControllerStateStore(
        initialSnapshot =
            referenceSnapshot(lastOutcomeSummary = lastOutcomeSummary, trustState = trustState),
        sessionId = "reference-session",
        nowProvider = { 2_000L },
    )
}

private fun referenceSnapshot(
    lastOutcomeSummary: String? = null,
    trustState: PeerTrustState,
): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = "reference-session",
                scenarioId = "guided-first-exchange",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1_000L,
                meshStateLabel = "Running",
                lastOutcomeSummary = lastOutcomeSummary,
                historyStatus = ReferenceHistoryStatus.LIVE,
            ),
        peers =
            listOf(
                PeerSnapshot(
                    peerId = TEST_PEER_ID,
                    peerSuffix = TEST_PEER_SUFFIX,
                    trustState = trustState,
                    connectionState = PeerConnectionSnapshotState.CONNECTED,
                )
            ),
        timeline =
            listOf(
                TimelineEntry(
                    entryId = "reference-session-1",
                    sessionId = "reference-session",
                    occurredAtEpochMillis = 1_000L,
                    family = TimelineFamily.USER,
                    severity = TimelineSeverity.INFO,
                    title = "Initial entry",
                    detail = "Reference state initialized",
                )
            ),
        activePowerModeLabel = "Automatic",
    )
}

internal const val TEST_PEER_ID: String = "peer-abcdef"
internal const val TEST_PEER_SUFFIX: String = "abcdef"
