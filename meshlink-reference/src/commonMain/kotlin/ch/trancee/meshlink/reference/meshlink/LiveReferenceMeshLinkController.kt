package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/** Live shared controller that wraps the existing MeshLink SDK and emits app-facing state. */
internal class LiveReferenceMeshLinkController(
    private val platformName: String,
    private val authorityMode: String,
    private val appId: String,
    private val nowProvider: () -> Long,
    private val surfaceOfOrigin: String = "main-guided",
    private val meshLinkBootstrap: MeshLinkBootstrap? = null,
    private val runtimeLogger: (String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ReferenceMeshLinkController {
    private val startedAtEpochMillis: Long = nowProvider()
    private val sessionId: String = "${platformName.lowercase()}-$startedAtEpochMillis"
    private val stateStore: ReferenceControllerStateStore =
        ReferenceControllerStateStore(
            initialSnapshot =
                createLiveReferenceInitialSnapshot(
                    platformName = platformName,
                    authorityMode = authorityMode,
                    nowProvider = nowProvider,
                    appId = appId,
                    surfaceOfOrigin = surfaceOfOrigin,
                    sessionId = sessionId,
                ),
            sessionId = sessionId,
            nowProvider = nowProvider,
        )
    private val runtime: LiveReferenceMeshRuntime =
        LiveReferenceMeshRuntime(
            appId = appId,
            meshLinkBootstrap = meshLinkBootstrap,
            scope = scope,
        )
    private val sendRecorder: LiveReferenceSendRecorder = LiveReferenceSendRecorder(stateStore)
    private val sessionProjector: LiveReferenceSessionProjector =
        LiveReferenceSessionProjector(stateStore = stateStore, runtimeLogger = runtimeLogger)
    private val commandExecutor: LiveReferenceMeshCommandExecutor =
        LiveReferenceMeshCommandExecutor(
            runtime = runtime,
            stateStore = stateStore,
            nowProvider = nowProvider,
            sessionProjector = sessionProjector,
            sendRecorder = sendRecorder,
        )

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = stateStore.snapshot

    override suspend fun start(): Unit {
        runtimeLogger(
            "REFERENCE_AUTOMATION live.controller.start surface=$surfaceOfOrigin platform=$platformName appId=$appId"
        )
        commandExecutor.start()
    }

    override suspend fun pause(): Unit {
        commandExecutor.pause()
    }

    override suspend fun resume(): Unit {
        commandExecutor.resume()
    }

    override suspend fun stop(): Unit {
        commandExecutor.stop()
    }

    override suspend fun sendPayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit {
        commandExecutor.sendPayload(peerId = peerId, payloadText = payloadText, priority = priority)
    }

    override suspend fun forgetPeer(peerId: String): Unit {
        commandExecutor.forgetPeer(peerId)
    }

    override suspend fun close(): Unit {
        runtime.close()
        scope.cancel()
    }
}
