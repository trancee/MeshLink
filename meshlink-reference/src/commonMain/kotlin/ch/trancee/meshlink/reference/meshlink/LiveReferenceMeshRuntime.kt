package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import kotlinx.coroutines.CoroutineScope

internal class LiveReferenceMeshRuntime(
    private val appId: String,
    private val meshLinkBootstrap: MeshLinkBootstrap?,
    private val scope: CoroutineScope,
    private val meshLinkApiFactory: (String, MeshLinkBootstrap?) -> MeshLinkApi =
        ::createLiveReferenceMeshLinkApi,
) {
    private var meshLinkApi: MeshLinkApi? = null
    private var flowsBound: Boolean = false

    suspend fun start(
        stateStore: ReferenceControllerStateStore,
        nowProvider: () -> Long,
        sessionProjector: LiveReferenceSessionProjector,
    ): Result<StartResult> {
        ensureBindings(stateStore, nowProvider, sessionProjector)
        return runCatching { requireMeshLinkApi().start() }
    }

    suspend fun pause(
        stateStore: ReferenceControllerStateStore,
        nowProvider: () -> Long,
        sessionProjector: LiveReferenceSessionProjector,
    ): Result<PauseResult> {
        ensureBindings(stateStore, nowProvider, sessionProjector)
        return runCatching { requireMeshLinkApi().pause() }
    }

    suspend fun resume(
        stateStore: ReferenceControllerStateStore,
        nowProvider: () -> Long,
        sessionProjector: LiveReferenceSessionProjector,
    ): Result<ResumeResult> {
        ensureBindings(stateStore, nowProvider, sessionProjector)
        return runCatching { requireMeshLinkApi().resume() }
    }

    suspend fun stop(
        stateStore: ReferenceControllerStateStore,
        nowProvider: () -> Long,
        sessionProjector: LiveReferenceSessionProjector,
    ): Result<StopResult> {
        ensureBindings(stateStore, nowProvider, sessionProjector)
        return runCatching { requireMeshLinkApi().stop() }
    }

    suspend fun send(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
        stateStore: ReferenceControllerStateStore,
        nowProvider: () -> Long,
        sessionProjector: LiveReferenceSessionProjector,
    ): Result<SendResult> {
        ensureBindings(stateStore, nowProvider, sessionProjector)
        return runCatching {
            requireMeshLinkApi()
                .send(
                    peerId = PeerId(peerId),
                    payload = payloadText.encodeToByteArray(),
                    priority = priority,
                )
        }
    }

    suspend fun forgetPeer(
        peerId: String,
        stateStore: ReferenceControllerStateStore,
        nowProvider: () -> Long,
        sessionProjector: LiveReferenceSessionProjector,
    ): Result<ForgetPeerResult> {
        ensureBindings(stateStore, nowProvider, sessionProjector)
        return runCatching { requireMeshLinkApi().forgetPeer(PeerId(peerId)) }
    }

    suspend fun close(): Unit {
        meshLinkApi?.let { api -> runCatching { api.stop() } }
    }

    private fun ensureBindings(
        stateStore: ReferenceControllerStateStore,
        nowProvider: () -> Long,
        sessionProjector: LiveReferenceSessionProjector,
    ): Unit {
        if (flowsBound) {
            return
        }
        flowsBound = true
        bindLiveReferenceControllerFlows(
            scope = scope,
            meshLinkApi = requireMeshLinkApi(),
            stateStore = stateStore,
            nowProvider = nowProvider,
            sessionProjector = sessionProjector,
        )
    }

    private fun requireMeshLinkApi(): MeshLinkApi {
        return meshLinkApi
            ?: meshLinkApiFactory(appId, meshLinkBootstrap).also { api -> meshLinkApi = api }
    }
}
