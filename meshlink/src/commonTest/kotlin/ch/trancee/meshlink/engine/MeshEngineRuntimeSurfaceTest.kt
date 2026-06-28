package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.test.RecordingDiagnosticSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MeshEngineRuntimeSurfaceTest {
    @Test
    fun `published diagnostic flow reflects compatibility diagnostic emission`() =
        runBlocking<Unit> {
            // Arrange
            val diagnosticSink = RecordingDiagnosticSink()
            val owner = MeshEngineRuntimeSurface(diagnosticSink = diagnosticSink)
            val published: MeshEnginePublishedRuntimeSurface = owner
            val compatibility: MeshEngineCompatibilityRuntimeSurface = owner
            val eventDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) { published.diagnosticEvents.first() }
                }

            // Act
            compatibility.emitDiagnostic(
                code = DiagnosticCode.DELIVERY_SUCCEEDED,
                severity = DiagnosticSeverity.INFO,
                stage = "delivery.completed",
                peerSuffix = "abcdef",
                reason = DiagnosticReason.DELIVERY_FAILURE,
                metadata = mapOf("routeAvailable" to "true"),
            )
            val event = eventDeferred.await()

            // Assert
            assertEquals(DiagnosticCode.DELIVERY_SUCCEEDED, event.code)
            assertEquals(DiagnosticSeverity.INFO, event.severity)
            assertEquals("delivery.completed", event.stage)
            assertEquals("abcdef", event.peerSuffix)
            assertEquals(DiagnosticReason.DELIVERY_FAILURE, event.reason)
            assertEquals(mapOf("routeAvailable" to "true"), event.metadata)
            assertEquals(listOf(event), diagnosticSink.events())
        }

    @Test
    fun `published lifecycle state reflects compatibility lifecycle transitions`() {
        // Arrange
        val owner = MeshEngineRuntimeSurface()
        val published: MeshEnginePublishedRuntimeSurface = owner
        val compatibility: MeshEngineCompatibilityRuntimeSurface = owner

        // Act
        val hardRunToken = compatibility.beginHardRun()
        compatibility.setLifecycleState(MeshLinkState.Paused)

        // Assert
        assertEquals(MeshLinkState.Paused, published.state.value)
        assertEquals(1L, hardRunToken.epoch)
    }

    @Test
    fun `runtime gate reports pause interruptions and later reactivation in the same hard run`() =
        runBlocking<Unit> {
            // Arrange
            val owner = MeshEngineRuntimeSurface()
            val compatibility: MeshEngineCompatibilityRuntimeSurface = owner
            val runtimeGate = compatibility.runtimeGate
            val hardRunToken = compatibility.beginHardRun()
            val interruptionDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) { runtimeGate.awaitInterruption(hardRunToken) }
                }
            val activeDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) { runtimeGate.awaitActive(hardRunToken) }
                }

            // Act
            compatibility.setLifecycleState(MeshLinkState.Paused)
            val interruption = interruptionDeferred.await()
            compatibility.setLifecycleState(MeshLinkState.Running)
            val activation = activeDeferred.await()

            // Assert
            assertEquals(MeshEngineRuntimeInterruption.Paused, interruption)
            assertEquals(MeshEngineRuntimeAwaitActiveResult.Active, activation)
        }

    @Test
    fun `runtime gate invalidates old hard run tokens after stop and restart`() =
        runBlocking<Unit> {
            // Arrange
            val owner = MeshEngineRuntimeSurface()
            val compatibility: MeshEngineCompatibilityRuntimeSurface = owner
            val runtimeGate = compatibility.runtimeGate
            val firstHardRunToken = compatibility.beginHardRun()

            // Act
            compatibility.setLifecycleState(MeshLinkState.Stopped)
            val stoppedActivation = runtimeGate.awaitActive(firstHardRunToken)
            val stoppedInterruption = runtimeGate.awaitInterruption(firstHardRunToken)
            val restartedHardRunToken = compatibility.beginHardRun()

            // Assert
            assertEquals(MeshEngineRuntimeAwaitActiveResult.HardRunEnded, stoppedActivation)
            assertEquals(MeshEngineRuntimeInterruption.HardRunEnded, stoppedInterruption)
            assertTrue(restartedHardRunToken.epoch > firstHardRunToken.epoch)
        }

    @Test
    fun `published peer events reflect compatibility peer emissions`() =
        runBlocking<Unit> {
            // Arrange
            val owner = MeshEngineRuntimeSurface()
            val published: MeshEnginePublishedRuntimeSurface = owner
            val compatibility: MeshEngineCompatibilityRuntimeSurface = owner
            val eventDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) { published.peerEvents.first() }
                }
            val event = PeerEvent.Lost(PeerId("peer-abcdef"))

            // Act
            compatibility.mutablePeerEvents.emit(event)
            val observedEvent = eventDeferred.await()

            // Assert
            assertEquals(event.peerId.value, (observedEvent as PeerEvent.Lost).peerId.value)
        }

    @Test
    fun `published messages reflect compatibility message emissions`() =
        runBlocking<Unit> {
            // Arrange
            val owner = MeshEngineRuntimeSurface()
            val published: MeshEnginePublishedRuntimeSurface = owner
            val compatibility: MeshEngineCompatibilityRuntimeSurface = owner
            val messageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) { published.messages.first() }
                }
            val message =
                InboundMessage(
                    originPeerId = PeerId("peer-abcdef"),
                    payload = "hello".encodeToByteArray(),
                    receivedAtEpochMillis = 123L,
                    priority = DeliveryPriority.NORMAL,
                )

            // Act
            compatibility.mutableMessages.emit(message)
            val observedMessage = messageDeferred.await()

            // Assert
            assertEquals(message.originPeerId.value, observedMessage.originPeerId.value)
            assertEquals(message.receivedAtEpochMillis, observedMessage.receivedAtEpochMillis)
            assertEquals(message.priority, observedMessage.priority)
            assertEquals("hello", observedMessage.payload.decodeToString())
        }
}
