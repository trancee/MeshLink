package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.BatterySnapshot
import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.reference.model.PeerTrustState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class LiveReferenceMeshCommandExecutorTest {
    @Test
    fun startRecordsLifecycleProjectionThroughTheSharedExecutionSeam() = runTest {
        // Arrange
        val meshLink = CommandExecutorRecordingMeshLink()
        val harness = commandExecutor(meshLink)

        // Act
        harness.commandExecutor.start()

        // Assert
        val snapshot = harness.stateStore.currentSnapshot
        assertEquals("Mesh started", snapshot.timeline.last().title)
        assertEquals("Started", snapshot.session.lastOutcomeSummary)
    }

    @Test
    fun sendPayloadRecordsSuccessfulOutcomeThroughTheSharedExecutionSeam() = runTest {
        // Arrange
        val meshLink = CommandExecutorRecordingMeshLink()
        val harness = commandExecutor(meshLink)

        // Act
        harness.commandExecutor.sendPayload(TEST_PEER_ID, "hello", DeliveryPriority.NORMAL)

        // Assert
        val snapshot = harness.stateStore.currentSnapshot
        assertEquals("Guided message sent", snapshot.timeline.last().title)
        assertEquals("SendResult.Sent", snapshot.session.lastOutcomeSummary)
    }

    @Test
    fun sendPayloadRecordsFailureThroughTheSharedExecutionSeam() = runTest {
        // Arrange
        val meshLink = CommandExecutorRecordingMeshLink(sendFailure = IllegalStateException("boom"))
        val harness = commandExecutor(meshLink)

        // Act
        harness.commandExecutor.sendPayload(TEST_PEER_ID, "hello", DeliveryPriority.NORMAL)

        // Assert
        val snapshot = harness.stateStore.currentSnapshot
        assertEquals("Guided message failed", snapshot.timeline.last().title)
        assertEquals("boom", snapshot.timeline.last().detail)
    }

    @Test
    fun forgetPeerRecordsTrustResetThroughTheSharedExecutionSeam() = runTest {
        // Arrange
        val meshLink = CommandExecutorRecordingMeshLink()
        val harness = commandExecutor(meshLink)

        // Act
        harness.commandExecutor.forgetPeer(TEST_PEER_ID)

        // Assert
        val snapshot = harness.stateStore.currentSnapshot
        assertEquals("Peer trust reset", snapshot.timeline.last().title)
        assertEquals(PeerTrustState.FORGOTTEN, snapshot.peers.single().trustState)
    }

    @Test
    fun forgetPeerRecordsFailureThroughTheSharedExecutionSeam() = runTest {
        // Arrange
        val meshLink =
            CommandExecutorRecordingMeshLink(forgetFailure = IllegalStateException("reset boom"))
        val harness = commandExecutor(meshLink)

        // Act
        harness.commandExecutor.forgetPeer(TEST_PEER_ID)

        // Assert
        val snapshot = harness.stateStore.currentSnapshot
        assertEquals("Peer trust reset failed", snapshot.timeline.last().title)
        assertEquals("reset boom", snapshot.timeline.last().detail)
    }
}

private data class CommandExecutorHarness(
    val commandExecutor: LiveReferenceMeshCommandExecutor,
    val stateStore: ReferenceControllerStateStore,
)

private fun commandExecutor(meshLink: CommandExecutorRecordingMeshLink): CommandExecutorHarness {
    val stateStore = referenceStateStore(trustState = PeerTrustState.TRUSTED)
    val sessionProjector = LiveReferenceSessionProjector(stateStore)
    val runtime =
        LiveReferenceMeshRuntime(
            appId = "demo.meshlink.reference",
            meshLinkBootstrap = null,
            scope = CoroutineScope(SupervisorJob()),
            meshLinkFactory = { _, _ -> meshLink },
        )
    return CommandExecutorHarness(
        commandExecutor =
            LiveReferenceMeshCommandExecutor(
                runtime = runtime,
                stateStore = stateStore,
                nowProvider = { 2_000L },
                sessionProjector = sessionProjector,
                sendRecorder = LiveReferenceSendRecorder(stateStore),
            ),
        stateStore = stateStore,
    )
}

private class CommandExecutorRecordingMeshLink(
    private val sendFailure: Throwable? = null,
    private val forgetFailure: Throwable? = null,
) : MeshLink {
    override val state = MutableStateFlow<MeshLinkState>(MeshLinkState.Uninitialized)
    override val peerEvents: Flow<PeerEvent> = MutableSharedFlow()
    override val diagnosticEvents: Flow<DiagnosticEvent> = MutableSharedFlow()
    override val messages: Flow<InboundMessage> = MutableSharedFlow()

    override suspend fun start(): StartResult = StartResult.Started

    override suspend fun pause(): PauseResult = PauseResult.Paused

    override suspend fun resume(): ResumeResult = ResumeResult.Resumed

    override suspend fun stop(): StopResult = StopResult.Stopped

    override suspend fun send(
        peerId: ch.trancee.meshlink.api.PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): SendResult {
        sendFailure?.let { throw it }
        return SendResult.Sent
    }

    override suspend fun forgetPeer(peerId: ch.trancee.meshlink.api.PeerId): ForgetPeerResult {
        forgetFailure?.let { throw it }
        return ForgetPeerResult.Forgotten
    }

    override fun updateBattery(snapshot: BatterySnapshot): Unit = Unit
}
