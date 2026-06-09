package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.BatterySnapshot
import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class LiveReferenceMeshRuntimeTest {
    @Test
    fun executeDelegatesPeerIdPayloadAndPriorityToMeshLink() = runTest {
        // Arrange
        val meshLink = RecordingMeshLink()
        val runtime =
            LiveReferenceMeshRuntime(
                appId = "demo.meshlink.reference",
                meshLinkBootstrap = null,
                scope = CoroutineScope(SupervisorJob()),
                meshLinkFactory = { _, _ -> meshLink },
            )
        val stateStore = referenceStateStore()
        val sessionProjector = LiveReferenceSessionProjector(stateStore)

        // Act
        val result =
            runtime.execute(stateStore, nowProvider = { 2_000L }, sessionProjector) {
                send(
                    peerId = PeerId("peer-123456"),
                    payload = "payload".encodeToByteArray(),
                    priority = DeliveryPriority.HIGH,
                )
            }

        // Assert
        assertEquals(SendResult.Sent, result.getOrThrow())
        assertEquals("peer-123456", meshLink.lastSendPeerId)
        assertContentEquals("payload".encodeToByteArray(), meshLink.lastSendPayload)
        assertEquals(DeliveryPriority.HIGH, meshLink.lastSendPriority)
    }

    @Test
    fun executeDelegatesTheRuntimePeerIdForForgetPeer() = runTest {
        // Arrange
        val meshLink = RecordingMeshLink()
        val runtime =
            LiveReferenceMeshRuntime(
                appId = "demo.meshlink.reference",
                meshLinkBootstrap = null,
                scope = CoroutineScope(SupervisorJob()),
                meshLinkFactory = { _, _ -> meshLink },
            )
        val stateStore = referenceStateStore()
        val sessionProjector = LiveReferenceSessionProjector(stateStore)

        // Act
        val result =
            runtime.execute(stateStore, nowProvider = { 2_000L }, sessionProjector) {
                forgetPeer(PeerId("peer-abcdef"))
            }

        // Assert
        assertEquals(ForgetPeerResult.Forgotten, result.getOrThrow())
        assertEquals("peer-abcdef", meshLink.lastForgottenPeerId)
    }

    @Test
    fun firstPeerEventEmittedDuringTheInitialExecuteCallIsRetained() = runBlocking {
        // Arrange
        val peerEvents = MutableSharedFlow<PeerEvent>()
        val meshLink = RecordingMeshLink(peerEventsFlow = peerEvents)
        val runtime =
            LiveReferenceMeshRuntime(
                appId = "demo.meshlink.reference",
                meshLinkBootstrap = null,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                meshLinkFactory = { _, _ -> meshLink },
            )
        val stateStore = referenceStateStore()
        val sessionProjector = LiveReferenceSessionProjector(stateStore)
        val peerId = PeerId("peer-123456")

        // Act
        val result =
            runtime.execute(stateStore, nowProvider = { 2_000L }, sessionProjector) {
                meshLink.publishPeerEvent(
                    PeerEvent.Found(peerId, ch.trancee.meshlink.api.PeerConnectionState.CONNECTED)
                )
                StartResult.Started
            }

        // Assert
        assertEquals(StartResult.Started, result.getOrThrow())
        val snapshot = stateStore.currentSnapshot
        assertEquals(
            peerId.value,
            snapshot.peers.first { peer -> peer.peerId == peerId.value }.peerId,
        )
        assertEquals("Peer found", snapshot.session.lastOutcomeSummary)
        assertEquals(peerId.value, snapshot.session.selectedPeerId)
    }

    @Test
    fun bindLiveReferenceControllerFlowsReplaysPeerEventsQueuedBeforeTheCollectorStarts() =
        runBlocking {
            // Arrange
            val peerEvents = MutableSharedFlow<PeerEvent>(replay = 1)
            val meshLink = RecordingMeshLink(peerEventsFlow = peerEvents)
            val stateStore = referenceStateStore()
            val sessionProjector = LiveReferenceSessionProjector(stateStore)
            val peerId = PeerId("peer-654321")
            peerEvents.emit(
                PeerEvent.Found(peerId, ch.trancee.meshlink.api.PeerConnectionState.CONNECTED)
            )

            // Act
            bindLiveReferenceControllerFlows(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                meshLink = meshLink,
                peerEvents = peerEvents,
                stateStore = stateStore,
                nowProvider = { 3_000L },
                sessionProjector = sessionProjector,
            )

            // Assert
            val snapshot = stateStore.currentSnapshot
            assertEquals(
                peerId.value,
                snapshot.peers.first { peer -> peer.peerId == peerId.value }.peerId,
            )
            assertEquals("Peer found", snapshot.session.lastOutcomeSummary)
            assertEquals(peerId.value, snapshot.session.selectedPeerId)
        }
}

private class RecordingMeshLink(
    private val peerEventsFlow: MutableSharedFlow<PeerEvent> = MutableSharedFlow()
) : MeshLink {
    override val state = MutableStateFlow<MeshLinkState>(MeshLinkState.Uninitialized)
    override val peerEvents: Flow<PeerEvent> = peerEventsFlow

    suspend fun publishPeerEvent(event: PeerEvent): Unit {
        peerEventsFlow.emit(event)
    }

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
