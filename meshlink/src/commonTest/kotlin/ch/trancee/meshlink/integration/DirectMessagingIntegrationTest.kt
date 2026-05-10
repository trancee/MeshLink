package ch.trancee.meshlink.integration

import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.test.MeshTestHarness
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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

        // Act
        val sendResult = sender.api.send(receiver.peerId, payload)
        val receivedMessage = withTimeout(1_000) { receiver.api.messages.first() }

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
        sender.api.send(receiver.peerId, "first".encodeToByteArray())
        receiver.api.stop()

        // Act
        val restartedReceiver = harness.restartNode(receiver)
        restartedReceiver.api.start()
        val sendResult = sender.api.send(restartedReceiver.peerId, "second".encodeToByteArray())
        val receivedMessage = withTimeout(1_000) { restartedReceiver.api.messages.first() }

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertEquals("second", receivedMessage.payload.decodeToString())
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

        // Act
        val sendResult = sender.api.send(receiver.peerId, plaintext.encodeToByteArray())
        val lastFrame = harness.lastDeliveredFrame()

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertNotNull(lastFrame)
        assertFalse(lastFrame.decodeToString().contains(plaintext))
        val receivedMessage: InboundMessage = withTimeout(1_000) { receiver.api.messages.first() }
        assertEquals(plaintext, receivedMessage.payload.decodeToString())
    }
}
