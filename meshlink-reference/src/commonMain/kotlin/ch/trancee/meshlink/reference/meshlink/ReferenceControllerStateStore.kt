package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private data object UnchangedSessionField

internal class ReferenceControllerStateStore(
    initialSnapshot: ReferenceControllerSnapshot,
    private val sessionId: String,
    private val nowProvider: () -> Long,
) {
    private val snapshotFlow: MutableStateFlow<ReferenceControllerSnapshot> =
        MutableStateFlow(initialSnapshot)

    internal val snapshot: StateFlow<ReferenceControllerSnapshot> = snapshotFlow.asStateFlow()

    internal val currentSnapshot: ReferenceControllerSnapshot
        get() = snapshotFlow.value

    internal fun updateSession(
        meshStateLabel: Any? = UnchangedSessionField,
        lastOutcomeSummary: Any? = UnchangedSessionField,
        selectedPeerId: Any? = UnchangedSessionField,
    ): Unit {
        updateSnapshot { snapshot ->
            snapshot.copy(
                session =
                    snapshot.session.copy(
                        meshStateLabel =
                            if (meshStateLabel === UnchangedSessionField) {
                                snapshot.session.meshStateLabel
                            } else {
                                meshStateLabel as String
                            },
                        lastOutcomeSummary =
                            if (lastOutcomeSummary === UnchangedSessionField) {
                                snapshot.session.lastOutcomeSummary
                            } else {
                                lastOutcomeSummary as String?
                            },
                        selectedPeerId =
                            if (selectedPeerId === UnchangedSessionField) {
                                snapshot.session.selectedPeerId
                            } else {
                                selectedPeerId as String?
                            },
                    )
            )
        }
    }

    internal fun updatePeers(transform: (List<PeerSnapshot>) -> List<PeerSnapshot>): Unit {
        updateSnapshot { snapshot -> snapshot.copy(peers = transform(snapshot.peers)) }
    }

    internal fun updateActivePowerModeLabel(label: String): Unit {
        updateSnapshot { snapshot -> snapshot.copy(activePowerModeLabel = label) }
    }

    internal fun appendEvent(event: ReferenceTimelineEvent): Unit {
        updateSnapshot { snapshot ->
            val entry =
                event.toTimelineEntry(
                    sessionId = sessionId,
                    entryIndex = snapshot.timeline.size + 1,
                    occurredAtEpochMillis = nowProvider(),
                )
            snapshot.copy(timeline = snapshot.timeline + entry)
        }
    }

    private fun updateSnapshot(
        transform: (ReferenceControllerSnapshot) -> ReferenceControllerSnapshot
    ): Unit {
        snapshotFlow.update(transform)
    }
}

internal data class ReferenceTimelineEvent(
    val family: TimelineFamily,
    val severity: TimelineSeverity,
    val title: String,
    val detail: String,
    val peerSuffix: String? = null,
    val payloadPreview: String? = null,
    val payloadSizeBytes: Int? = null,
    val fullPayload: String? = null,
)

internal fun ReferenceTimelineEvent.toTimelineEntry(
    sessionId: String,
    entryIndex: Int,
    occurredAtEpochMillis: Long,
): TimelineEntry {
    return TimelineEntry(
        entryId = "$sessionId-$entryIndex",
        sessionId = sessionId,
        occurredAtEpochMillis = occurredAtEpochMillis,
        family = family,
        severity = severity,
        title = title,
        detail = detail,
        peerSuffix = peerSuffix,
        searchText = listOf(title, detail, peerSuffix.orEmpty()).joinToString(" "),
        payloadPreview = payloadPreview,
        payloadSizeBytes = payloadSizeBytes,
        fullPayload = fullPayload,
        fullPayloadIncluded = fullPayload != null,
    )
}
