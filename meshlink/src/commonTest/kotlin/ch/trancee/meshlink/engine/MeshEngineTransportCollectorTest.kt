package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MeshEngineTransportCollectorTest {
    @Test
    fun `ensureStarted forwards transport events to the handler`() = runBlocking {
        // Arrange
        val events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 1)
        val handledEvent = CompletableDeferred<TransportEvent>()
        val collector =
            MeshEngineTransportCollector(
                coroutineScope = this,
                transportEvents = { events },
                handleTransportEvent = { event -> handledEvent.complete(event) },
            )
        val event =
            TransportEvent.PeerDiscovered(
                peerId = PeerId("peer-abcdef"),
                transportMode = TransportMode.L2CAP,
            )

        // Act
        collector.ensureStarted()
        events.emit(event)
        val observedEvent = withTimeout(1_000) { handledEvent.await() }
        collector.stop()

        // Assert
        assertEquals(
            event.peerId.value,
            (observedEvent as TransportEvent.PeerDiscovered).peerId.value,
        )
        assertEquals(TransportMode.L2CAP, observedEvent.transportMode)
    }

    @Test
    fun `ensureStarted ignores duplicate start requests`() = runBlocking {
        // Arrange
        val events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 1)
        val handledPeerIds = mutableListOf<String>()
        val collector =
            MeshEngineTransportCollector(
                coroutineScope = this,
                transportEvents = { events },
                handleTransportEvent = { event ->
                    val peerId = (event as TransportEvent.PeerDiscovered).peerId.value
                    handledPeerIds += peerId
                },
            )
        val event =
            TransportEvent.PeerDiscovered(
                peerId = PeerId("peer-abcdef"),
                transportMode = TransportMode.L2CAP,
            )

        // Act
        collector.ensureStarted()
        collector.ensureStarted()
        events.emit(event)
        withTimeout(1_000) {
            while (handledPeerIds.isEmpty()) {
                kotlinx.coroutines.yield()
            }
        }
        collector.stop()

        // Assert
        assertEquals(listOf("peer-abcdef"), handledPeerIds)
    }

    @Test
    fun `stop cancels the collector and allows a later restart`() = runBlocking {
        // Arrange
        val events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 2)
        val handledPeerIds = mutableListOf<String>()
        val firstHandled = CompletableDeferred<Unit>()
        val secondHandled = CompletableDeferred<Unit>()
        val collector =
            MeshEngineTransportCollector(
                coroutineScope = this,
                transportEvents = { events },
                handleTransportEvent = { event ->
                    val peerId = (event as TransportEvent.PeerDiscovered).peerId.value
                    handledPeerIds += peerId
                    when (handledPeerIds.size) {
                        1 -> firstHandled.complete(Unit)
                        2 -> secondHandled.complete(Unit)
                    }
                },
            )
        val firstEvent =
            TransportEvent.PeerDiscovered(
                peerId = PeerId("peer-first"),
                transportMode = TransportMode.L2CAP,
            )
        val secondEvent =
            TransportEvent.PeerDiscovered(
                peerId = PeerId("peer-second"),
                transportMode = TransportMode.L2CAP,
            )

        // Act
        collector.ensureStarted()
        events.emit(firstEvent)
        withTimeout(1_000) { firstHandled.await() }
        collector.stop()
        collector.ensureStarted()
        events.emit(secondEvent)
        withTimeout(1_000) { secondHandled.await() }
        collector.stop()

        // Assert
        assertEquals(listOf("peer-first", "peer-second"), handledPeerIds)
        assertTrue(firstHandled.isCompleted)
        assertTrue(secondHandled.isCompleted)
    }

    @Test
    fun `ensureStarted does nothing when no transport events are available`() = runBlocking {
        // Arrange
        var handled: Boolean = false
        val collector =
            MeshEngineTransportCollector(
                coroutineScope = this,
                transportEvents = { null },
                handleTransportEvent = { handled = true },
            )

        // Act
        collector.ensureStarted()
        collector.stop()

        // Assert
        assertTrue(!handled)
    }
}
