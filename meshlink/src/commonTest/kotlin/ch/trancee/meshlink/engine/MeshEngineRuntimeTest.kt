package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineCompatibilityRuntimeSurface
import ch.trancee.meshlink.engine.assembly.MeshEnginePublishedRuntimeSurface
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntime
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeFacadeOperations
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeSurface
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MeshEngineRuntimeTest {
    @Test
    fun `runtime lifecycle methods delegate through facade operations`() =
        runBlocking<Unit> {
            // Arrange
            val facadeOperations = RecordingRuntimeFacadeOperations()
            val runtime =
                MeshEngineRuntime(
                    publishedSurface = MeshEngineRuntimeSurface(),
                    facadeOperations = facadeOperations,
                )

            // Act
            val startResult = runtime.start()
            val pauseResult = runtime.pause()
            val resumeResult = runtime.resume()
            val stopResult = runtime.stop()

            // Assert
            assertEquals(StartResult.Started, startResult)
            assertEquals(PauseResult.Paused, pauseResult)
            assertEquals(ResumeResult.Resumed, resumeResult)
            assertEquals(StopResult.Stopped, stopResult)
            assertEquals(listOf("start", "pause", "resume", "stop"), facadeOperations.calls)
        }

    @Test
    fun `runtime send delegates through facade operations`() =
        runBlocking<Unit> {
            // Arrange
            val facadeOperations = RecordingRuntimeFacadeOperations()
            val runtime =
                MeshEngineRuntime(
                    publishedSurface = MeshEngineRuntimeSurface(),
                    facadeOperations = facadeOperations,
                )
            val peerId = PeerId("peer-abcdef")
            val payload = "hello".encodeToByteArray()

            // Act
            val result =
                runtime.send(peerId = peerId, payload = payload, priority = DeliveryPriority.HIGH)

            // Assert
            assertEquals(SendResult.Sent, result)
            assertEquals(peerId, facadeOperations.sentPeerId)
            assertContentEquals(payload, facadeOperations.sentPayload ?: error("Expected payload"))
            assertEquals(DeliveryPriority.HIGH, facadeOperations.sentPriority)
        }

    @Test
    fun `runtime forgetPeer delegates through facade operations`() =
        runBlocking<Unit> {
            // Arrange
            val facadeOperations = RecordingRuntimeFacadeOperations()
            val runtime =
                MeshEngineRuntime(
                    publishedSurface = MeshEngineRuntimeSurface(),
                    facadeOperations = facadeOperations,
                )
            val peerId = PeerId("peer-abcdef")

            // Act
            val result = runtime.forgetPeer(peerId)

            // Assert
            assertEquals(ForgetPeerResult.Forgotten, result)
            assertEquals(peerId, facadeOperations.forgottenPeerId)
        }

    @Test
    fun `runtime exposes the published surface state and flows`() =
        runBlocking<Unit> {
            // Arrange
            val surfaceOwner = MeshEngineRuntimeSurface()
            val publishedSurface: MeshEnginePublishedRuntimeSurface = surfaceOwner
            val compatibilitySurface: MeshEngineCompatibilityRuntimeSurface = surfaceOwner
            val runtime =
                MeshEngineRuntime(
                    publishedSurface = publishedSurface,
                    facadeOperations = RecordingRuntimeFacadeOperations(),
                )
            val peerEventDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) { runtime.peerEvents.first() }
                }
            val messageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) { runtime.messages.first() }
                }
            val diagnosticDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) { runtime.diagnosticEvents.first() }
                }
            val message =
                InboundMessage(
                    originPeerId = PeerId("peer-fedcba"),
                    payload = "hello".encodeToByteArray(),
                    receivedAtEpochMillis = 123L,
                    priority = DeliveryPriority.NORMAL,
                )

            // Act
            compatibilitySurface.beginHardRun()
            compatibilitySurface.mutablePeerEvents.emit(PeerEvent.Lost(PeerId("peer-abcdef")))
            compatibilitySurface.mutableMessages.emit(message)
            compatibilitySurface.emitDiagnostic(
                code = DiagnosticCode.DELIVERY_SUCCEEDED,
                severity = DiagnosticSeverity.INFO,
                stage = "delivery.completed",
                peerSuffix = "abcdef",
                reason = DiagnosticReason.DELIVERY_FAILURE,
                metadata = mapOf("routeAvailable" to "true"),
            )
            val peerEvent = peerEventDeferred.await()
            val observedMessage = messageDeferred.await()
            val diagnostic = diagnosticDeferred.await()

            // Assert
            assertEquals(MeshLinkState.Running, runtime.state.value)
            assertEquals("peer-abcdef", (peerEvent as PeerEvent.Lost).peerId.value)
            assertEquals("peer-fedcba", observedMessage.originPeerId.value)
            assertEquals("hello", observedMessage.payload.decodeToString())
            assertEquals("delivery.completed", diagnostic.stage)
            assertEquals("true", diagnostic.metadata["routeAvailable"])
        }
}

private class RecordingRuntimeFacadeOperations : MeshEngineRuntimeFacadeOperations {
    val calls = mutableListOf<String>()
    var sentPeerId: PeerId? = null
    var sentPayload: ByteArray? = null
    var sentPriority: DeliveryPriority? = null
    var forgottenPeerId: PeerId? = null

    override suspend fun start(): StartResult {
        calls += "start"
        return StartResult.Started
    }

    override suspend fun pause(): PauseResult {
        calls += "pause"
        return PauseResult.Paused
    }

    override suspend fun resume(): ResumeResult {
        calls += "resume"
        return ResumeResult.Resumed
    }

    override suspend fun stop(): StopResult {
        calls += "stop"
        return StopResult.Stopped
    }

    override suspend fun send(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): SendResult {
        sentPeerId = peerId
        sentPayload = payload
        sentPriority = priority
        return SendResult.Sent
    }

    override suspend fun forgetPeer(peerId: PeerId): ForgetPeerResult {
        forgottenPeerId = peerId
        return ForgetPeerResult.Forgotten
    }
}
