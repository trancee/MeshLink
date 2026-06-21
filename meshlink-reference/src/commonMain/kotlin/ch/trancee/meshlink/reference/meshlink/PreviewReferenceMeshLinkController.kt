package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.REFERENCE_AUTHORITY_MODE_SOLO
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Preview fallback used when the live controller cannot be created. */
internal class PreviewReferenceMeshLinkController(
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
                        authorityMode = REFERENCE_AUTHORITY_MODE_SOLO,
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
                            peerSuffix =
                                platformName.take(PREVIEW_PEER_PREFIX_LENGTH).uppercase() + "0001",
                            trustState = PeerTrustState.UNKNOWN,
                            connectionState = PeerConnectionSnapshotState.DISCONNECTED,
                            capabilityNotes = listOf("Fallback preview data"),
                        )
                    ),
                timeline =
                    listOf(
                        TimelineEntry(
                            entryId = "preview-$platformName-1",
                            sessionId = "preview-$platformName",
                            occurredAtEpochMillis = nowEpochMillis,
                            family = TimelineFamily.USER,
                            severity = TimelineSeverity.INFO,
                            title = "Reference app initialized",
                            detail = "Preview fallback is active on $platformName.",
                        )
                    ),
                activePowerModeLabel = "Automatic",
            )
        )

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = stateFlow.asStateFlow()

    override suspend fun start(): Unit = Unit

    override suspend fun pause(): Unit = Unit

    override suspend fun resume(): Unit = Unit

    override suspend fun stop(): Unit = Unit

    override suspend fun sendPayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit = Unit

    override suspend fun forgetPeer(peerId: String): Unit = Unit

    override suspend fun close(): Unit = Unit
}

private const val PREVIEW_PEER_PREFIX_LENGTH: Int = 2
