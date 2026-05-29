package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkBootstrap
import kotlinx.coroutines.CoroutineScope

internal class LiveReferenceMeshRuntime(
    private val appId: String,
    private val meshLinkBootstrap: MeshLinkBootstrap?,
    private val scope: CoroutineScope,
    private val meshLinkFactory: (String, MeshLinkBootstrap?) -> MeshLink =
        ::createLiveReferenceMeshLink,
) {
    private var meshLink: MeshLink? = null
    private var flowsBound: Boolean = false

    suspend fun <T> execute(
        stateStore: ReferenceControllerStateStore,
        nowProvider: () -> Long,
        sessionProjector: LiveReferenceSessionProjector,
        operation: suspend MeshLink.() -> T,
    ): Result<T> {
        ensureBindings(stateStore, nowProvider, sessionProjector)
        return runCatching { requireMeshLink().operation() }
    }

    suspend fun close(): Unit {
        meshLink?.let { api -> runCatching { api.stop() } }
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
            meshLink = requireMeshLink(),
            stateStore = stateStore,
            nowProvider = nowProvider,
            sessionProjector = sessionProjector,
        )
    }

    private fun requireMeshLink(): MeshLink {
        return meshLink ?: meshLinkFactory(appId, meshLinkBootstrap).also { api -> meshLink = api }
    }
}
