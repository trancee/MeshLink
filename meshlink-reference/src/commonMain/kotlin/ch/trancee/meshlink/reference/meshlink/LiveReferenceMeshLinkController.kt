package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/** Live shared controller that wraps the existing MeshLink SDK and emits app-facing state. */
internal class LiveReferenceMeshLinkController(
    private val platformName: String,
    private val authorityMode: ReferenceAuthorityMode,
    private val appId: String,
    private val nowProvider: () -> Long,
    private val surfaceOfOrigin: String = "main-guided",
    private val platformContext: Any? = null,
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
        LiveReferenceMeshRuntime(appId = appId, platformContext = platformContext, scope = scope)
    private val sendRecorder: LiveReferenceSendRecorder = LiveReferenceSendRecorder(stateStore)
    private val sessionProjector: LiveReferenceSessionProjector =
        LiveReferenceSessionProjector(stateStore = stateStore, runtimeLogger = runtimeLogger)

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = stateStore.snapshot

    override suspend fun start(): Unit {
        val outcome =
            runtime.start(
                stateStore = stateStore,
                nowProvider = nowProvider,
                sessionProjector = sessionProjector,
            )
        sessionProjector.recordMeshCall(
            result = outcome,
            successTitle = "Mesh started",
            successDetail = { result -> "mesh.start() -> $result" },
            errorTitle = "Mesh start failed",
        )
    }

    override suspend fun pause(): Unit {
        val outcome =
            runtime.pause(
                stateStore = stateStore,
                nowProvider = nowProvider,
                sessionProjector = sessionProjector,
            )
        sessionProjector.recordMeshCall(
            result = outcome,
            successTitle = "Mesh paused",
            successDetail = { result -> "mesh.pause() -> $result" },
            errorTitle = "Mesh pause failed",
        )
    }

    override suspend fun resume(): Unit {
        val outcome =
            runtime.resume(
                stateStore = stateStore,
                nowProvider = nowProvider,
                sessionProjector = sessionProjector,
            )
        sessionProjector.recordMeshCall(
            result = outcome,
            successTitle = "Mesh resumed",
            successDetail = { result -> "mesh.resume() -> $result" },
            errorTitle = "Mesh resume failed",
        )
    }

    override suspend fun stop(): Unit {
        val outcome =
            runtime.stop(
                stateStore = stateStore,
                nowProvider = nowProvider,
                sessionProjector = sessionProjector,
            )
        sessionProjector.recordMeshCall(
            result = outcome,
            successTitle = "Mesh stopped",
            successDetail = { result -> "mesh.stop() -> $result" },
            errorTitle = "Mesh stop failed",
        )
    }

    override suspend fun sendPayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit {
        val outcome =
            runtime.send(
                peerId = peerId,
                payloadText = payloadText,
                priority = priority,
                stateStore = stateStore,
                nowProvider = nowProvider,
                sessionProjector = sessionProjector,
            )
        outcome
            .onSuccess { result ->
                sendRecorder.recordOutcome(
                    peerId = peerId,
                    payloadText = payloadText,
                    priority = priority,
                    result = result,
                )
            }
            .onFailure { error ->
                sendRecorder.recordFailure(
                    peerId = peerId,
                    payloadText = payloadText,
                    error = error,
                )
            }
    }

    override suspend fun forgetPeer(peerId: String): Unit {
        val outcome =
            runtime.forgetPeer(
                peerId = peerId,
                stateStore = stateStore,
                nowProvider = nowProvider,
                sessionProjector = sessionProjector,
            )
        outcome
            .onSuccess { result -> sessionProjector.recordPeerTrustReset(peerId, result) }
            .onFailure { error -> sessionProjector.recordPeerTrustResetFailure(peerId, error) }
    }

    override suspend fun close(): Unit {
        runtime.close()
        scope.cancel()
    }
}
