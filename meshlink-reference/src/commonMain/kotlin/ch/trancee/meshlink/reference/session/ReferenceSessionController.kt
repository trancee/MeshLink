package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public class ReferenceSessionController(
    private val platformName: String,
    private val nowProvider: () -> Long,
    supportedControllerFactory: (String) -> ReferenceMeshLinkController,
    initialSupportedSurface: String = "main-guided",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ReferenceMeshLinkController {
    private val supportedControllerRuntime: SupportedControllerRuntime =
        SupportedControllerRuntime(
            initialSurfaceOfOrigin = initialSupportedSurface,
            supportedControllerFactory = supportedControllerFactory,
            scope = scope,
        )
    private val snapshotFlow: MutableStateFlow<ReferenceControllerSnapshot> =
        MutableStateFlow(supportedControllerRuntime.currentSnapshot())
    private var currentKind: ReferenceSessionKind = ReferenceSessionKind.SUPPORTED_LIVE

    override val snapshot: StateFlow<ReferenceControllerSnapshot>
        get() = snapshotFlow.asStateFlow()

    init {
        supportedControllerRuntime.bind(::publishSupportedSnapshot)
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

    override suspend fun sendPayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit {
        runOnSupportedLiveController { controller ->
            controller.sendPayload(peerId = peerId, payloadText = payloadText, priority = priority)
        }
    }

    override suspend fun forgetPeer(peerId: String): Unit {
        runOnSupportedLiveController { controller -> controller.forgetPeer(peerId) }
    }

    override suspend fun close(): Unit {
        supportedControllerRuntime.closeCurrent()
        scope.cancel()
    }

    public suspend fun endSupportedSession(): ReferenceControllerSnapshot {
        val currentSnapshot = snapshotFlow.value
        if (currentKind != ReferenceSessionKind.SUPPORTED_LIVE) {
            return currentSnapshot
        }

        val endedSnapshot = supportedControllerRuntime.end(nowProvider)
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
        currentKind = ReferenceSessionKind.SUPPORTED_LIVE
        return supportedControllerRuntime.restart(
            surfaceOfOrigin = surfaceOfOrigin,
            onSnapshotChanged = ::publishSupportedSnapshot,
        )
    }

    private suspend fun runOnSupportedLiveController(
        action: suspend (ReferenceMeshLinkController) -> Unit
    ): Unit {
        if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
            supportedControllerRuntime.run(action)
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
            supportedControllerRuntime.closeCurrent()
        }
    }

    private fun publishSupportedSnapshot(snapshot: ReferenceControllerSnapshot): Unit {
        if (currentKind == ReferenceSessionKind.SUPPORTED_LIVE) {
            snapshotFlow.value = snapshot
        }
    }
}
