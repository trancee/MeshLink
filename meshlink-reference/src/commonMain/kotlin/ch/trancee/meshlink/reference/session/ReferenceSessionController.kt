package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

public enum class ReferenceSessionKind {
    SUPPORTED_LIVE,
    SUPPORTED_ENDED,
    SOLO,
    LAB,
}

internal fun ReferenceControllerSnapshot.referenceSessionKind(): ReferenceSessionKind {
    val surfaceOfOrigin = session.configurationSnapshot["surface"]
    return when {
        session.authorityMode == ReferenceAuthorityMode.SOLO -> ReferenceSessionKind.SOLO
        surfaceOfOrigin == "lab" -> ReferenceSessionKind.LAB
        session.endedAtEpochMillis != null -> ReferenceSessionKind.SUPPORTED_ENDED
        else -> ReferenceSessionKind.SUPPORTED_LIVE
    }
}

internal fun ReferenceControllerSnapshot.allowsFullPayloadExport(): Boolean {
    return referenceSessionKind() == ReferenceSessionKind.SUPPORTED_LIVE
}

public class ReferenceSessionController(
    private val platformName: String,
    private val nowProvider: () -> Long,
    private val supportedControllerFactory: (String) -> ReferenceMeshLinkController,
    initialSupportedSurface: String = "main-guided",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ReferenceMeshLinkController {
    private val snapshotFlow: MutableStateFlow<ReferenceControllerSnapshot>
    private var supportedController: ReferenceMeshLinkController
    private var supportedSnapshotJob: Job? = null
    private var currentSupportedSurfaceOfOrigin: String = initialSupportedSurface
    private var currentKind: ReferenceSessionKind = ReferenceSessionKind.SUPPORTED_LIVE

    override val snapshot: StateFlow<ReferenceControllerSnapshot>
        get() = snapshotFlow.asStateFlow()

    init {
        supportedController = supportedControllerFactory(initialSupportedSurface)
        snapshotFlow =
            MutableStateFlow(
                supportedController.snapshot.value.withSurfaceOfOrigin(initialSupportedSurface)
            )
        bindSupportedSnapshot()
    }

    override suspend fun start(): Unit {
        if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
            supportedController.start()
        }
    }

    override suspend fun pause(): Unit {
        if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
            supportedController.pause()
        }
    }

    override suspend fun resume(): Unit {
        if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
            supportedController.resume()
        }
    }

    override suspend fun stop(): Unit {
        if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
            supportedController.stop()
        }
    }

    override suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit {
        if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
            supportedController.sendSamplePayload(
                peerId = peerId,
                payloadText = payloadText,
                priority = priority,
            )
        }
    }

    override suspend fun forgetPeer(peerId: String): Unit {
        if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
            supportedController.forgetPeer(peerId)
        }
    }

    override suspend fun close(): Unit {
        supportedSnapshotJob?.cancel()
        supportedController.close()
        scope.cancel()
    }

    public suspend fun endSupportedSession(): ReferenceControllerSnapshot {
        val currentSnapshot = snapshotFlow.value
        if (currentKind != ReferenceSessionKind.SUPPORTED_LIVE) {
            return currentSnapshot
        }

        val endedSnapshot =
            currentSnapshot.copy(
                session = currentSnapshot.session.copy(endedAtEpochMillis = nowProvider())
            )
        supportedSnapshotJob?.cancel()
        supportedController.close()
        currentKind = ReferenceSessionKind.SUPPORTED_ENDED
        snapshotFlow.value = endedSnapshot
        return endedSnapshot
    }

    public suspend fun startSoloSession(): ReferenceControllerSnapshot {
        if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
            supportedSnapshotJob?.cancel()
            supportedController.close()
        }
        currentKind = ReferenceSessionKind.SOLO
        val soloSnapshot =
            staticSessionSnapshot(
                scenarioId = "solo-exploration",
                authorityMode = ReferenceAuthorityMode.SOLO,
                surfaceOfOrigin = "main-guided",
                title = "Solo exploration opened",
                detail = "Solo exploration is active on $platformName.",
            )
        snapshotFlow.value = soloSnapshot
        return soloSnapshot
    }

    public suspend fun startLabSession(): ReferenceControllerSnapshot {
        if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
            supportedSnapshotJob?.cancel()
            supportedController.close()
        }
        currentKind = ReferenceSessionKind.LAB
        val labSnapshot =
            staticSessionSnapshot(
                scenarioId = "lab",
                authorityMode = ReferenceAuthorityMode.LIVE,
                surfaceOfOrigin = "lab",
                title = "Lab session opened",
                detail = "The non-normative lab surface is active on $platformName.",
            )
        snapshotFlow.value = labSnapshot
        return labSnapshot
    }

    public suspend fun startNewSupportedSession(
        surfaceOfOrigin: String = "main-guided"
    ): ReferenceControllerSnapshot {
        supportedSnapshotJob?.cancel()
        if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
            supportedController.close()
        }
        currentKind = ReferenceSessionKind.SUPPORTED_LIVE
        currentSupportedSurfaceOfOrigin = surfaceOfOrigin
        supportedController = supportedControllerFactory(surfaceOfOrigin)
        val initialSnapshot =
            supportedController.snapshot.value.withSurfaceOfOrigin(surfaceOfOrigin)
        snapshotFlow.value = initialSnapshot
        bindSupportedSnapshot()
        return initialSnapshot
    }

    private fun staticSessionSnapshot(
        scenarioId: String,
        authorityMode: ReferenceAuthorityMode,
        surfaceOfOrigin: String,
        title: String,
        detail: String,
    ): ReferenceControllerSnapshot {
        val now = nowProvider()
        val sessionId = "${scenarioId}-${platformName.lowercase()}-$now"
        val baseConfiguration = snapshotFlow.value.session.configurationSnapshot
        val configurationSnapshot =
            baseConfiguration + mapOf("platform" to platformName, "surface" to surfaceOfOrigin)
        return ReferenceControllerSnapshot(
            session =
                ReferenceSession(
                    sessionId = sessionId,
                    scenarioId = scenarioId,
                    authorityMode = authorityMode,
                    startedAtEpochMillis = now,
                    meshStateLabel = snapshotFlow.value.session.meshStateLabel,
                    configurationSnapshot = configurationSnapshot,
                    historyStatus = ReferenceHistoryStatus.LIVE,
                ),
            peers = emptyList(),
            timeline =
                listOf(
                    TimelineEntry(
                        entryId = "$sessionId-1",
                        sessionId = sessionId,
                        occurredAtEpochMillis = now,
                        family = TimelineFamily.USER,
                        severity = TimelineSeverity.INFO,
                        title = title,
                        detail = detail,
                    )
                ),
            activePowerModeLabel = snapshotFlow.value.activePowerModeLabel,
        )
    }

    private fun bindSupportedSnapshot(): Unit {
        supportedSnapshotJob?.cancel()
        supportedSnapshotJob = scope.launch {
            supportedController.snapshot.collect { nextSnapshot ->
                if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
                    snapshotFlow.value =
                        nextSnapshot.withSurfaceOfOrigin(currentSupportedSurfaceOfOrigin)
                }
            }
        }
    }
}

private fun ReferenceControllerSnapshot.withSurfaceOfOrigin(
    surfaceOfOrigin: String
): ReferenceControllerSnapshot {
    return copy(
        session =
            session.copy(
                configurationSnapshot =
                    session.configurationSnapshot + mapOf("surface" to surfaceOfOrigin)
            )
    )
}
