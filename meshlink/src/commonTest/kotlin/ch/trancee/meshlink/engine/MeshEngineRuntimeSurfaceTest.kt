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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MeshEngineRuntimeSurfaceTest {
    @Test
    fun `published diagnostic flow reflects compatibility diagnostic emission`() = runBlocking {
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
    fun `published lifecycle state reflects compatibility state updates`() {
        // Arrange
        val owner = MeshEngineRuntimeSurface()
        val published: MeshEnginePublishedRuntimeSurface = owner
        val compatibility: MeshEngineCompatibilityRuntimeSurface = owner

        // Act
        compatibility.mutableState.value = MeshLinkState.Running

        // Assert
        assertEquals(MeshLinkState.Running, published.state.value)
    }

    @Test
    fun `published peer events reflect compatibility peer emissions`() = runBlocking {
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
    fun `published messages reflect compatibility message emissions`() = runBlocking {
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
