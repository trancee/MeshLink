package ch.trancee.meshlink.integration

import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.test.MeshTestHarness
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DirectMessagingIntegrationTest {
    @Test
    fun `two trusted peers can exchange a direct offline message`() = runBlocking {
        // Arrange
        val harness = MeshTestHarness()
        val sender = harness.createNode("peer-a")
        val receiver = harness.createNode("peer-b")
        val payload = "hello mesh".encodeToByteArray()

        sender.api.start()
        receiver.api.start()
        delay(250)
        val receivedMessageDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) { receiver.api.messages.first() }
            }

        // Act
        val sendResult = sender.api.send(receiver.peerId, payload)
        val receivedMessage = receivedMessageDeferred.await()

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertContentEquals(payload, receivedMessage.payload)
        assertEquals(MeshLinkState.Running, receiver.api.state.value)
    }

    @Test
    fun `persisted trust survives sdk restart`() = runBlocking {
        // Arrange
        val harness = MeshTestHarness()
        val sender = harness.createNode("peer-a")
        val receiver = harness.createNode("peer-b")
        sender.api.start()
        receiver.api.start()
        delay(250)
        val firstMessageDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) { receiver.api.messages.first() }
            }
        sender.api.send(receiver.peerId, "first".encodeToByteArray())
        firstMessageDeferred.await()
        receiver.api.stop()

        // Act
        val restartedReceiver = harness.restartNode(receiver)
        restartedReceiver.api.start()
        val receivedMessageDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) { restartedReceiver.api.messages.first() }
            }
        val sendResult = sender.api.send(restartedReceiver.peerId, "second".encodeToByteArray())
        val receivedMessage = receivedMessageDeferred.await()

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertEquals("second", receivedMessage.payload.decodeToString())
    }

    @Test
    fun `runtime secure storage keeps trust metadata without persisting plaintext or peer ids`() =
        runBlocking {
            // Arrange
            val harness = MeshTestHarness()
            val sender = harness.createNode("peer-a")
            val receiver = harness.createNode("peer-b")
            val plaintext = "secret message"
            val payload = plaintext.encodeToByteArray()

            sender.api.start()
            receiver.api.start()
            delay(250)
            val receivedMessageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000) { receiver.api.messages.first() }
                }

            // Act
            val sendResult = sender.api.send(receiver.peerId, payload)
            receivedMessageDeferred.await()
            val receiverSnapshot = receiver.storage.snapshot()

            // Assert
            assertIs<SendResult.Sent>(sendResult)
            assertEquals(1, receiverSnapshot.size)
            assertTrue(receiverSnapshot.keys.all { key -> key.startsWith("trust:") })
            assertFalse(receiverSnapshot.keys.any { key -> key.contains(sender.peerId.value) })
            assertFalse(receiverSnapshot.values.any { value -> value.containsSubsequence(payload) })
        }

    @Test
    fun `transport frames do not expose plaintext payloads`() = runBlocking {
        // Arrange
        val harness = MeshTestHarness()
        val sender = harness.createNode("peer-a")
        val receiver = harness.createNode("peer-b")
        val plaintext = "secret message"

        sender.api.start()
        receiver.api.start()
        delay(250)
        val receivedMessageDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) { receiver.api.messages.first() }
            }

        // Act
        val sendResult = sender.api.send(receiver.peerId, plaintext.encodeToByteArray())
        val lastFrame = harness.lastDeliveredFrame()

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertNotNull(lastFrame)
        assertFalse(lastFrame.decodeToString().contains(plaintext))
        val receivedMessage: InboundMessage = receivedMessageDeferred.await()
        assertEquals(plaintext, receivedMessage.payload.decodeToString())
    }

    private fun ByteArray.containsSubsequence(expected: ByteArray): Boolean {
        if (expected.isEmpty() || expected.size > size) {
            return false
        }
        return indices.any { startIndex ->
            val endExclusive = startIndex + expected.size
            endExclusive <= size && copyOfRange(startIndex, endExclusive).contentEquals(expected)
        }
    }
}
