package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfig
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.navigation.SessionTransitionService
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceSessionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class TimelineStoreHarness(
    initialTimeline: List<TimelineEntry> = emptyList(),
    activeAutomationConfig: ReferenceAutomationConfig? = null,
    initialNowMillis: Long = 2_000L,
) {
    val documentStore: InMemoryReferenceDocumentStore = InMemoryReferenceDocumentStore()
    var nowMillis: Long = initialNowMillis
    private val controllerFlow: MutableStateFlow<ReferenceControllerSnapshot> =
        MutableStateFlow(timelineStoreSnapshot(timeline = initialTimeline))

    private fun rawController(): ReferenceMeshLinkController {
        return object : ReferenceMeshLinkController {
            override val snapshot: StateFlow<ReferenceControllerSnapshot> =
                controllerFlow.asStateFlow()

            override suspend fun start(): Unit = Unit

            override suspend fun pause(): Unit = Unit

            override suspend fun resume(): Unit = Unit

            override suspend fun stop(): Unit = Unit

            override suspend fun sendSamplePayload(
                peerId: String,
                payloadText: String,
                priority: DeliveryPriority,
            ): Unit = Unit

            override suspend fun forgetPeer(peerId: String): Unit = Unit
        }
    }

    private val sessionController: ReferenceSessionController =
        ReferenceSessionController(
            platformName = "Test",
            nowProvider = { nowMillis },
            supportedControllerFactory = { rawController() },
        )

    private val platformServices: PlatformServices =
        object : PlatformServices {
            override val platformName: String = "Test"
            override val defaultAuthorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE
            override val readinessGuidance: List<String> = emptyList()
            override val readinessBlockers: List<String> = emptyList()
            override val automationConfig: ReferenceAutomationConfig? = activeAutomationConfig
            override val documentStore: ReferenceDocumentStore =
                this@TimelineStoreHarness.documentStore
            override val meshLinkController: ReferenceMeshLinkController = sessionController

            override fun createSupportedMeshLinkController(
                surfaceOfOrigin: String
            ): ReferenceMeshLinkController {
                return rawController()
            }

            override fun currentTimeMillis(): Long = nowMillis

            override fun emitAutomationLog(message: String): Unit = Unit
        }

    fun createStore(scope: kotlinx.coroutines.CoroutineScope): TechnicalTimelineStore {
        return TechnicalTimelineStore(
            platformServices = platformServices,
            historyRepository = JsonSessionHistoryRepository(documentStore = documentStore),
            artifactSerializer = JsonSessionArtifactSerializer(documentStore = documentStore),
            sessionController = sessionController,
            scope = scope,
        )
    }

    fun createTransitionServiceHarness(
        scope: kotlinx.coroutines.CoroutineScope
    ): SessionTransitionServiceHarness {
        val timelineStore = createStore(scope)
        return SessionTransitionServiceHarness(
            timelineStore = timelineStore,
            transitionService = SessionTransitionService(timelineStore),
        )
    }

    fun emitLiveSnapshot(timeline: List<TimelineEntry>): Unit {
        controllerFlow.value = timelineStoreSnapshot(timeline = timeline)
    }
}

internal data class SessionTransitionServiceHarness(
    val timelineStore: TechnicalTimelineStore,
    val transitionService: SessionTransitionService,
)

internal fun timelineStoreSnapshot(timeline: List<TimelineEntry>): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = "timeline-session",
                scenarioId = "guided-first-exchange",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1_000L,
                historyStatus = ReferenceHistoryStatus.LIVE,
                configurationSnapshot = mapOf("surface" to "main-guided"),
            ),
        peers = emptyList(),
        timeline = timeline,
        activePowerModeLabel = "Automatic",
    )
}

internal fun timelineStoreEntry(entryId: String, title: String): TimelineEntry {
    return TimelineEntry(
        entryId = entryId,
        sessionId = "timeline-session",
        occurredAtEpochMillis = 1L,
        family = TimelineFamily.MESSAGE,
        severity = TimelineSeverity.INFO,
        title = title,
        detail = "$title detail",
        searchText = title,
    )
}
