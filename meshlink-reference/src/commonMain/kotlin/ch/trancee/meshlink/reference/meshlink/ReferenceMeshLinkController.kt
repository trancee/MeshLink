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

/**
 * Shared controller abstraction for the reference app.
 */
public interface ReferenceMeshLinkController {
    public val snapshot: StateFlow<ReferenceControllerSnapshot>

    public suspend fun start(): Unit

    public suspend fun pause(): Unit

    public suspend fun resume(): Unit

    public suspend fun stop(): Unit

    public suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority = DeliveryPriority.NORMAL,
    ): Unit

    public suspend fun forgetPeer(peerId: String): Unit
}

public data class ReferenceControllerSnapshot(
    public val session: ReferenceSession,
    public val peers: List<PeerSnapshot>,
    public val timeline: List<TimelineEntry>,
    public val activePowerModeLabel: String,
)

/**
 * Temporary in-memory controller that keeps the app shell functional until the live flows land.
 */
public class PreviewReferenceMeshLinkController(
    private val platformName: String,
    nowEpochMillis: Long,
) : ReferenceMeshLinkController {
    private val stateFlow: MutableStateFlow<ReferenceControllerSnapshot> =
        MutableStateFlow(
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "preview-$platformName",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.SOLO,
                        startedAtEpochMillis = nowEpochMillis,
                        meshStateLabel = MeshLinkState.Uninitialized.toString(),
                        configurationSnapshot =
                            mapOf(
                                "platform" to platformName,
                                "surface" to "main-guided",
                                "mode" to "solo",
                            ),
                        historyStatus = ReferenceHistoryStatus.LIVE,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = "preview-peer-$platformName",
                            peerSuffix = platformName.take(2).uppercase() + "0001",
                            trustState = PeerTrustState.UNKNOWN,
                            connectionState = PeerConnectionSnapshotState.DISCONNECTED,
                            capabilityNotes = listOf("Foundational preview data"),
                        )
                    ),
                timeline =
                    listOf(
                        timelineEntry(
                            sessionId = "preview-$platformName",
                            index = 1,
                            family = TimelineFamily.USER,
                            severity = TimelineSeverity.INFO,
                            title = "Reference app initialized",
                            detail = "Foundational app shell is ready on $platformName.",
                            occurredAtEpochMillis = nowEpochMillis,
                        )
                    ),
                activePowerModeLabel = "Automatic",
            )
        )

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = stateFlow.asStateFlow()

    override suspend fun start(): Unit {
        mutate(
            meshStateLabel = MeshLinkState.Running.toString(),
            eventTitle = "Mesh preview started",
            eventDetail = "Live MeshLink startup wiring is delivered in later story tasks.",
        )
    }

    override suspend fun pause(): Unit {
        mutate(
            meshStateLabel = MeshLinkState.Paused.toString(),
            eventTitle = "Mesh preview paused",
            eventDetail = "The foundational controller keeps lifecycle placeholders visible.",
        )
    }

    override suspend fun resume(): Unit {
        mutate(
            meshStateLabel = MeshLinkState.Running.toString(),
            eventTitle = "Mesh preview resumed",
            eventDetail = "Lifecycle actions are wired through the shared shell.",
        )
    }

    override suspend fun stop(): Unit {
        mutate(
            meshStateLabel = MeshLinkState.Stopped.toString(),
            eventTitle = "Mesh preview stopped",
            eventDetail = "The shared app shell stays available for guided navigation.",
        )
    }

    override suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit {
        mutate(
            eventTitle = "Sample payload prepared",
            eventDetail =
                "Prepared preview send to ${peerId.takeLast(6)} with ${payloadText.length} characters at $priority priority.",
            payloadPreview = payloadText,
        )
    }

    override suspend fun forgetPeer(peerId: String): Unit {
        mutate(
            peers =
                stateFlow.value.peers.map { peer ->
                    if (peer.peerId == peerId) {
                        peer.copy(trustState = PeerTrustState.FORGOTTEN)
                    } else {
                        peer
                    }
                },
            eventTitle = "Preview peer forgotten",
            eventDetail = "Marked ${peerId.takeLast(6)} as forgotten in the foundational preview.",
        )
    }

    private fun mutate(
        meshStateLabel: String = stateFlow.value.session.meshStateLabel,
        peers: List<PeerSnapshot> = stateFlow.value.peers,
        eventTitle: String,
        eventDetail: String,
        payloadPreview: String? = null,
    ): Unit {
        val current = stateFlow.value
        val nextTimeline =
            current.timeline +
                timelineEntry(
                    sessionId = current.session.sessionId,
                    index = current.timeline.size + 1,
                    family = TimelineFamily.DIAGNOSTIC,
                    severity = TimelineSeverity.INFO,
                    title = eventTitle,
                    detail = eventDetail,
                    occurredAtEpochMillis = current.session.startedAtEpochMillis + current.timeline.size + 1L,
                    payloadPreview = payloadPreview,
                )
        stateFlow.value =
            current.copy(
                session =
                    current.session.copy(
                        meshStateLabel = meshStateLabel,
                        lastOutcomeSummary = eventTitle,
                    ),
                peers = peers,
                timeline = nextTimeline,
            )
    }

    private fun timelineEntry(
        sessionId: String,
        index: Int,
        family: TimelineFamily,
        severity: TimelineSeverity,
        title: String,
        detail: String,
        occurredAtEpochMillis: Long,
        payloadPreview: String? = null,
    ): TimelineEntry {
        return TimelineEntry(
            entryId = "$sessionId-$index",
            sessionId = sessionId,
            occurredAtEpochMillis = occurredAtEpochMillis,
            family = family,
            severity = severity,
            title = title,
            detail = detail,
            searchText = listOf(title, detail, platformName).joinToString(" "),
            payloadPreview = payloadPreview,
        )
    }
}
