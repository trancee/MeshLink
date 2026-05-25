package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import kotlinx.coroutines.flow.StateFlow

/** Deterministic reference-app controller used by host-platform UI automation. */
public class ScriptedReferenceMeshLinkController(
    private val platformName: String,
    private val authorityMode: ReferenceAuthorityMode,
    private val nowProvider: () -> Long,
    private val appId: String = "demo.meshlink.reference.automation",
    private val surfaceOfOrigin: String = "main-guided",
) : ReferenceMeshLinkController {
    private val startedAtEpochMillis: Long = nowProvider()
    private val sessionId: String = "automation-${platformName.lowercase()}-$startedAtEpochMillis"
    private val scriptedPeerId: String = "automation-peer-654321"
    private val scriptedPeerSuffix: String = redactedSuffix(scriptedPeerId)
    private val stateStore: ReferenceControllerStateStore =
        ReferenceControllerStateStore(
            initialSnapshot =
                createScriptedReferenceInitialSnapshot(
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
    private val sendRecorder: ScriptedReferenceSendRecorder =
        ScriptedReferenceSendRecorder(
            stateStore = stateStore,
            scriptedPeerId = scriptedPeerId,
            scriptedPeerSuffix = scriptedPeerSuffix,
            updatePeerOutcome = { outcome ->
                updateScriptedPeerOutcome(
                    stateStore = stateStore,
                    nowProvider = nowProvider,
                    scriptedPeerId = scriptedPeerId,
                    lastDeliveryOutcome = outcome,
                )
            },
        )

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = stateStore.snapshot

    override suspend fun start(): Unit {
        startScriptedMesh(
            stateStore = stateStore,
            nowProvider = nowProvider,
            scriptedPeerId = scriptedPeerId,
            scriptedPeerSuffix = scriptedPeerSuffix,
        )
    }

    override suspend fun pause(): Unit {
        pauseScriptedMesh(stateStore)
    }

    override suspend fun resume(): Unit {
        resumeScriptedMesh(
            stateStore = stateStore,
            nowProvider = nowProvider,
            scriptedPeerId = scriptedPeerId,
            scriptedPeerSuffix = scriptedPeerSuffix,
        )
    }

    override suspend fun stop(): Unit {
        stopScriptedMesh(stateStore)
    }

    override suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit {
        val blocker =
            sendRecorder.blockerFor(
                peerId = peerId,
                meshStateLabel = stateStore.currentSnapshot.session.meshStateLabel,
            )
        if (blocker != null) {
            sendRecorder.recordBlockedSend(peerId = peerId, blocker = blocker)
            return
        }

        ensureScriptedPeerAvailable(
            stateStore = stateStore,
            nowProvider = nowProvider,
            scriptedPeerId = scriptedPeerId,
            scriptedPeerSuffix = scriptedPeerSuffix,
        )
        promoteScriptedPeerTrust(
            stateStore = stateStore,
            scriptedPeerId = scriptedPeerId,
            scriptedPeerSuffix = scriptedPeerSuffix,
        )
        sendRecorder.recordCompletion(
            payloadText = payloadText,
            priority = priority,
            largeTransferThresholdBytes = LARGE_TRANSFER_THRESHOLD_BYTES,
            payloadPreviewCharacters = PAYLOAD_PREVIEW_CHARACTERS,
        )
    }

    override suspend fun forgetPeer(peerId: String): Unit {
        forgetScriptedPeer(
            stateStore = stateStore,
            peerId = peerId,
            scriptedPeerId = scriptedPeerId,
            scriptedPeerSuffix = scriptedPeerSuffix,
        )
    }

    override suspend fun close(): Unit = Unit

    private companion object {
        private const val LARGE_TRANSFER_THRESHOLD_BYTES: Int = 4_096
        private const val PAYLOAD_PREVIEW_CHARACTERS: Int = 96
    }
}
