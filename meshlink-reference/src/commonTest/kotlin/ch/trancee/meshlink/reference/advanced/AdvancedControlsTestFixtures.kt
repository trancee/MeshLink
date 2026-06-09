package ch.trancee.meshlink.reference.advanced

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfig
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun advancedPlatformServices(
    controller: ReferenceMeshLinkController = TestReferenceMeshLinkController()
): PlatformServices {
    return object : PlatformServices {
        override val platformName: String = "Test"
        override val defaultAuthorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE
        override val readinessGuidance: List<String> = listOf("Step 1")
        override val readinessBlockers: List<String> = emptyList()
        override val automationConfig: ReferenceAutomationConfig? = null
        override val powerMitigationStatus: String? = null
        override val documentStore: ReferenceDocumentStore = InMemoryReferenceDocumentStore()
        override val meshLinkController: ReferenceMeshLinkController = controller

        override fun currentTimeMillis(): Long = 1_000L

        override fun emitAutomationLog(message: String): Unit = Unit

        override fun stopPowerMitigation(): Unit = Unit
    }
}

internal class TestReferenceMeshLinkController(
    initialSnapshot: ReferenceControllerSnapshot = advancedSnapshot()
) : ReferenceMeshLinkController {
    private val flow: MutableStateFlow<ReferenceControllerSnapshot> =
        MutableStateFlow(initialSnapshot)

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = flow.asStateFlow()

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

    fun updateMeshState(meshStateLabel: String): Unit {
        flow.value =
            flow.value.copy(session = flow.value.session.copy(meshStateLabel = meshStateLabel))
    }
}

internal fun advancedSnapshot(lastOutcomeSummary: String? = null): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = "advanced-session",
                scenarioId = "advanced-controls",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1_000L,
                meshStateLabel = MeshLinkState.Running.toString(),
                configurationSnapshot =
                    mapOf(
                        "appId" to "demo.meshlink.reference",
                        "regulatoryRegion" to "DEFAULT",
                        "powerMode" to "Automatic",
                        "deliveryRetryDeadline" to "15s",
                    ),
                historyStatus = ReferenceHistoryStatus.LIVE,
                lastOutcomeSummary = lastOutcomeSummary,
            ),
        peers =
            listOf(
                PeerSnapshot(
                    peerId = "peer-abc123",
                    peerSuffix = "abc123",
                    trustState = PeerTrustState.TRUSTED,
                    connectionState = PeerConnectionSnapshotState.CONNECTED,
                    lastDeliveryOutcome = "Sent",
                )
            ),
        timeline =
            listOf(
                TimelineEntry(
                    entryId = "advanced-session-1",
                    sessionId = "advanced-session",
                    occurredAtEpochMillis = 1_000L,
                    family = TimelineFamily.DIAGNOSTIC,
                    severity = TimelineSeverity.INFO,
                    title = "Mesh started",
                    detail = "mesh.start() -> Started",
                )
            ),
        activePowerModeLabel = "Automatic",
    )
}
