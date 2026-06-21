package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import kotlinx.coroutines.flow.StateFlow

internal class DeferredReferenceMeshLinkController(
    private val factory: () -> ReferenceMeshLinkController
) : ReferenceMeshLinkController {
    private val delegate: ReferenceMeshLinkController by lazy(factory)

    override val snapshot: StateFlow<ReferenceControllerSnapshot>
        get() = delegate.snapshot

    override suspend fun start(): Unit = delegate.start()

    override suspend fun pause(): Unit = delegate.pause()

    override suspend fun resume(): Unit = delegate.resume()

    override suspend fun stop(): Unit = delegate.stop()

    override suspend fun sendPayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit = delegate.sendPayload(peerId = peerId, payloadText = payloadText, priority = priority)

    override suspend fun forgetPeer(peerId: String): Unit = delegate.forgetPeer(peerId)

    override suspend fun close(): Unit = delegate.close()
}
