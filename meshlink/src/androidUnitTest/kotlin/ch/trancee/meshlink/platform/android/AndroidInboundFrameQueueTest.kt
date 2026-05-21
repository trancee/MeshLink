package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.TransportEvent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class AndroidInboundFrameQueueTest {
    @Test
    fun `enqueue delivers frames in order`() = runBlocking {
        // Arrange
        val peerId = PeerId("peer-1")
        val deliveredFrames = mutableListOf<TransportEvent.FrameReceived>()
        val queue = AndroidInboundFrameQueue(scope = this) { event -> deliveredFrames += event }

        // Act
        val firstEnqueued = queue.enqueue(peerId = peerId, payload = "first".encodeToByteArray())
        val secondEnqueued = queue.enqueue(peerId = peerId, payload = "second".encodeToByteArray())
        withTimeout(1_000L) {
            while (deliveredFrames.size < 2) {
                yield()
            }
        }

        // Assert
        assertTrue(firstEnqueued)
        assertTrue(secondEnqueued)
        assertEquals(2, deliveredFrames.size)
        assertContentEquals("first".encodeToByteArray(), deliveredFrames[0].payload)
        assertContentEquals("second".encodeToByteArray(), deliveredFrames[1].payload)

        queue.close()
    }

    @Test
    fun `enqueue returns false when the bounded queue is full`() = runBlocking {
        // Arrange
        val peerId = PeerId("peer-1")
        val firstDeliveryStarted = CompletableDeferred<Unit>()
        val unblockConsumer = CompletableDeferred<Unit>()
        val deliveredFrames = mutableListOf<TransportEvent.FrameReceived>()
        val queue =
            AndroidInboundFrameQueue(scope = this, capacity = 1) { event ->
                deliveredFrames += event
                if (!firstDeliveryStarted.isCompleted) {
                    firstDeliveryStarted.complete(Unit)
                    unblockConsumer.await()
                }
            }

        // Act
        val firstEnqueued = queue.enqueue(peerId = peerId, payload = "first".encodeToByteArray())
        firstDeliveryStarted.await()
        val secondEnqueued = queue.enqueue(peerId = peerId, payload = "second".encodeToByteArray())
        val thirdEnqueued = queue.enqueue(peerId = peerId, payload = "third".encodeToByteArray())
        unblockConsumer.complete(Unit)
        withTimeout(1_000L) {
            while (deliveredFrames.size < 2) {
                yield()
            }
        }

        // Assert
        assertTrue(firstEnqueued)
        assertTrue(secondEnqueued)
        assertFalse(thirdEnqueued)
        assertEquals(2, deliveredFrames.size)
        assertContentEquals("first".encodeToByteArray(), deliveredFrames[0].payload)
        assertContentEquals("second".encodeToByteArray(), deliveredFrames[1].payload)

        queue.close()
    }
}
