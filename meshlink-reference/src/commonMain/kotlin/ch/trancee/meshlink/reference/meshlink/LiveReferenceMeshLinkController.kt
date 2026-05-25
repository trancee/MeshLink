package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/** Live shared controller that wraps the existing MeshLink SDK and emits app-facing state. */
public class LiveReferenceMeshLinkController(
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
    private val meshLinkApi: MeshLinkApi by lazy {
        createLiveReferenceMeshLinkApi(appId = appId, platformContext = platformContext)
    }
    private val sendRecorder: LiveReferenceSendRecorder = LiveReferenceSendRecorder(stateStore)
    private val sessionProjector: LiveReferenceSessionProjector =
        LiveReferenceSessionProjector(stateStore = stateStore, runtimeLogger = runtimeLogger)
    private var flowsBound: Boolean = false

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = stateStore.snapshot

    override suspend fun start(): Unit {
        ensureBindings()
        val outcome = runCatching { meshLinkApi.start() }
        sessionProjector.recordMeshCall(
            result = outcome,
            successTitle = "Mesh started",
            successDetail = { result -> "mesh.start() -> $result" },
            errorTitle = "Mesh start failed",
        )
    }

    override suspend fun pause(): Unit {
        val outcome = runCatching { meshLinkApi.pause() }
        sessionProjector.recordMeshCall(
            result = outcome,
            successTitle = "Mesh paused",
            successDetail = { result -> "mesh.pause() -> $result" },
            errorTitle = "Mesh pause failed",
        )
    }

    override suspend fun resume(): Unit {
        val outcome = runCatching { meshLinkApi.resume() }
        sessionProjector.recordMeshCall(
            result = outcome,
            successTitle = "Mesh resumed",
            successDetail = { result -> "mesh.resume() -> $result" },
            errorTitle = "Mesh resume failed",
        )
    }

    override suspend fun stop(): Unit {
        val outcome = runCatching { meshLinkApi.stop() }
        sessionProjector.recordMeshCall(
            result = outcome,
            successTitle = "Mesh stopped",
            successDetail = { result -> "mesh.stop() -> $result" },
            errorTitle = "Mesh stop failed",
        )
    }

    override suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit {
        val outcome = runCatching {
            meshLinkApi.send(
                peerId = PeerId(peerId),
                payload = payloadText.encodeToByteArray(),
                priority = priority,
            )
        }
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
        val outcome = runCatching { meshLinkApi.forgetPeer(PeerId(peerId)) }
        outcome
            .onSuccess { result -> sessionProjector.recordPeerTrustReset(peerId, result) }
            .onFailure { error -> sessionProjector.recordPeerTrustResetFailure(peerId, error) }
    }

    override suspend fun close(): Unit {
        runCatching { meshLinkApi.stop() }
        scope.cancel()
    }

    private fun ensureBindings(): Unit {
        if (flowsBound) {
            return
        }
        flowsBound = true
        bindLiveReferenceControllerFlows(
            scope = scope,
            meshLinkApi = meshLinkApi,
            stateStore = stateStore,
            nowProvider = nowProvider,
            sessionProjector = sessionProjector,
        )
    }
}
