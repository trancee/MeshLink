package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Live controller runtime that binds MeshLink state into the reference-app snapshot.
 *
 * The runtime intentionally relays peer events and inbound messages through replaying app-local
 * flows so the first discovery event or inbound message cannot be lost if it arrives before the
 * snapshot/projector collectors finish attaching.
 */
internal class LiveReferenceMeshRuntime(
    private val appId: String,
    private val meshLinkBootstrap: MeshLinkBootstrap?,
    private val scope: CoroutineScope,
    private val meshLinkFactory: (String, MeshLinkBootstrap?) -> MeshLink =
        ::createLiveReferenceMeshLink,
) {
    private var meshLink: MeshLink? = null
    private var flowsBound: Boolean = false
    private val peerEventRelay: MutableSharedFlow<ch.trancee.meshlink.api.PeerEvent> =
        MutableSharedFlow(replay = 1, extraBufferCapacity = 16)
    private val messageRelay: MutableSharedFlow<InboundMessage> =
        MutableSharedFlow(replay = MESSAGE_RELAY_REPLAY_CAPACITY, extraBufferCapacity = 16)

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
        val meshLink = requireMeshLink()
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            meshLink.peerEvents.collect { event -> peerEventRelay.emit(event) }
        }
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            meshLink.messages.collect { message -> messageRelay.emit(message) }
        }
        bindLiveReferenceControllerFlows(
            scope = scope,
            meshLink = meshLink,
            peerEvents = peerEventRelay,
            messages = messageRelay,
            stateStore = stateStore,
            nowProvider = nowProvider,
            sessionProjector = sessionProjector,
        )
    }

    private fun requireMeshLink(): MeshLink {
        return meshLink ?: meshLinkFactory(appId, meshLinkBootstrap).also { api -> meshLink = api }
    }
}

private const val MESSAGE_RELAY_REPLAY_CAPACITY: Int = 8
