package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.BatterySnapshot
import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class LiveReferenceMeshRuntimeTest {
    @Test
    fun sendDelegatesPeerIdPayloadAndPriorityToMeshLinkApi() = runTest {
        // Arrange
        val api = RecordingMeshLinkApi()
        val runtime =
            LiveReferenceMeshRuntime(
                appId = "demo.meshlink.reference",
                meshLinkBootstrap = null,
                scope = CoroutineScope(SupervisorJob()),
                meshLinkApiFactory = { _, _ -> api },
            )
        val stateStore = referenceStateStore()
        val sessionProjector = LiveReferenceSessionProjector(stateStore)

        // Act
        val result =
            runtime.send(
                peerId = "peer-123456",
                payloadText = "payload",
                priority = DeliveryPriority.HIGH,
                stateStore = stateStore,
                nowProvider = { 2_000L },
                sessionProjector = sessionProjector,
            )

        // Assert
        assertEquals(SendResult.Sent, result.getOrThrow())
        assertEquals("peer-123456", api.lastSendPeerId)
        assertContentEquals("payload".encodeToByteArray(), api.lastSendPayload)
        assertEquals(DeliveryPriority.HIGH, api.lastSendPriority)
    }

    @Test
    fun forgetPeerDelegatesTheRuntimePeerId() = runTest {
        // Arrange
        val api = RecordingMeshLinkApi()
        val runtime =
            LiveReferenceMeshRuntime(
                appId = "demo.meshlink.reference",
                meshLinkBootstrap = null,
                scope = CoroutineScope(SupervisorJob()),
                meshLinkApiFactory = { _, _ -> api },
            )
        val stateStore = referenceStateStore()
        val sessionProjector = LiveReferenceSessionProjector(stateStore)

        // Act
        val result =
            runtime.forgetPeer(
                peerId = "peer-abcdef",
                stateStore = stateStore,
                nowProvider = { 2_000L },
                sessionProjector = sessionProjector,
            )

        // Assert
        assertEquals(ForgetPeerResult.Forgotten, result.getOrThrow())
        assertEquals("peer-abcdef", api.lastForgottenPeerId)
    }
}

private class RecordingMeshLinkApi : MeshLinkApi {
    override val state = MutableStateFlow<MeshLinkState>(MeshLinkState.Uninitialized)
    override val peerEvents: Flow<PeerEvent> = MutableSharedFlow()
    override val diagnosticEvents: Flow<DiagnosticEvent> = MutableSharedFlow()
    override val messages: Flow<InboundMessage> = MutableSharedFlow()

    var lastSendPeerId: String? = null
    var lastSendPayload: ByteArray? = null
    var lastSendPriority: DeliveryPriority? = null
    var lastForgottenPeerId: String? = null

    override suspend fun start(): StartResult = StartResult.Started

    override suspend fun pause(): PauseResult = PauseResult.Paused

    override suspend fun resume(): ResumeResult = ResumeResult.Resumed

    override suspend fun stop(): StopResult = StopResult.Stopped

    override suspend fun send(
        peerId: ch.trancee.meshlink.api.PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): SendResult {
        lastSendPeerId = peerId.value
        lastSendPayload = payload
        lastSendPriority = priority
        return SendResult.Sent
    }

    override suspend fun forgetPeer(peerId: ch.trancee.meshlink.api.PeerId): ForgetPeerResult {
        lastForgottenPeerId = peerId.value
        return ForgetPeerResult.Forgotten
    }

    override fun updateBattery(snapshot: BatterySnapshot): Unit = Unit
}
