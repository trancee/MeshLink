package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
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
        runOnSupportedLiveController { controller -> controller.start() }
    }

    override suspend fun pause(): Unit {
        runOnSupportedLiveController { controller -> controller.pause() }
    }

    override suspend fun resume(): Unit {
        runOnSupportedLiveController { controller -> controller.resume() }
    }

    override suspend fun stop(): Unit {
        runOnSupportedLiveController { controller -> controller.stop() }
    }

    override suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit {
        runOnSupportedLiveController { controller ->
            controller.sendSamplePayload(
                peerId = peerId,
                payloadText = payloadText,
                priority = priority,
            )
        }
    }

    override suspend fun forgetPeer(peerId: String): Unit {
        runOnSupportedLiveController { controller -> controller.forgetPeer(peerId) }
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
        return startAlternativeSession(
            kind = ReferenceSessionKind.SOLO,
            scenarioId = "solo-exploration",
            authorityMode = ReferenceAuthorityMode.SOLO,
            surfaceOfOrigin = "solo-exploration",
            title = "Solo exploration opened",
            detail = "Solo exploration is active on $platformName.",
        )
    }

    public suspend fun startLabSession(): ReferenceControllerSnapshot {
        return startAlternativeSession(
            kind = ReferenceSessionKind.LAB,
            scenarioId = "lab",
            authorityMode = ReferenceAuthorityMode.LIVE,
            surfaceOfOrigin = "lab",
            title = "Lab session opened",
            detail = "The non-normative lab surface is active on $platformName.",
        )
    }

    public suspend fun startNewSupportedSession(
        surfaceOfOrigin: String = "main-guided"
    ): ReferenceControllerSnapshot {
        supportedSnapshotJob?.cancel()
        closeSupportedControllerIfNeeded()
        currentKind = ReferenceSessionKind.SUPPORTED_LIVE
        currentSupportedSurfaceOfOrigin = surfaceOfOrigin
        supportedController = supportedControllerFactory(surfaceOfOrigin)
        val initialSnapshot =
            supportedController.snapshot.value.withSurfaceOfOrigin(surfaceOfOrigin)
        snapshotFlow.value = initialSnapshot
        bindSupportedSnapshot()
        return initialSnapshot
    }

    private suspend fun runOnSupportedLiveController(
        action: suspend (ReferenceMeshLinkController) -> Unit
    ): Unit {
        if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
            action(supportedController)
        }
    }

    private suspend fun startAlternativeSession(
        kind: ReferenceSessionKind,
        scenarioId: String,
        authorityMode: ReferenceAuthorityMode,
        surfaceOfOrigin: String,
        title: String,
        detail: String,
    ): ReferenceControllerSnapshot {
        closeSupportedSessionIfLive()
        currentKind = kind
        val snapshot =
            createStaticReferenceSessionSnapshot(
                platformName = platformName,
                nowProvider = nowProvider,
                currentSnapshot = snapshotFlow.value,
                scenarioId = scenarioId,
                authorityMode = authorityMode,
                surfaceOfOrigin = surfaceOfOrigin,
                title = title,
                detail = detail,
            )
        snapshotFlow.value = snapshot
        return snapshot
    }

    private suspend fun closeSupportedSessionIfLive(): Unit {
        if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
            supportedSnapshotJob?.cancel()
            supportedController.close()
        }
    }

    private suspend fun closeSupportedControllerIfNeeded(): Unit {
        if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
            supportedController.close()
        }
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
