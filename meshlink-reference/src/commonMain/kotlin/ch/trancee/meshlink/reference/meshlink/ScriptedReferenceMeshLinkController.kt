package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import kotlinx.coroutines.flow.StateFlow

/** Deterministic reference-app controller used by host-platform UI automation. */
public class ScriptedReferenceMeshLinkController(
    platformName: String,
    authorityMode: ReferenceAuthorityMode,
    nowProvider: () -> Long,
    appId: String = "demo.meshlink.reference.automation",
    surfaceOfOrigin: String = "main-guided",
) : ReferenceMeshLinkController {
    private val runtime: ScriptedReferenceMeshRuntime =
        ScriptedReferenceMeshRuntime(
            platformName = platformName,
            authorityMode = authorityMode,
            nowProvider = nowProvider,
            appId = appId,
            surfaceOfOrigin = surfaceOfOrigin,
        )

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = runtime.snapshot

    override suspend fun start(): Unit {
        runtime.start()
    }

    override suspend fun pause(): Unit {
        runtime.pause()
    }

    override suspend fun resume(): Unit {
        runtime.resume()
    }

    override suspend fun stop(): Unit {
        runtime.stop()
    }

    override suspend fun sendPayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit {
        runtime.sendPayload(peerId = peerId, payloadText = payloadText, priority = priority)
    }

    override suspend fun forgetPeer(peerId: String): Unit {
        runtime.forgetPeer(peerId)
    }

    override suspend fun close(): Unit = Unit
}
