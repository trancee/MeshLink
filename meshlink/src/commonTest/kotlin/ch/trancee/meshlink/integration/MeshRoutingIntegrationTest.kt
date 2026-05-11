package ch.trancee.meshlink.integration

import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.test.MeshTestHarness
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class MeshRoutingIntegrationTest {
    @Test
    fun `a sender can reach a destination through a single relay hop`() = runBlocking {
        // Arrange
        val harness = MeshTestHarness()
        val sender = harness.createNode("peer-a")
        val relay = harness.createNode("peer-b")
        val recipient = harness.createNode("peer-c")
        val payload = "hello through relay".encodeToByteArray()

        harness.linkPeers(sender, relay)
        harness.linkPeers(relay, recipient)

        sender.api.start()
        relay.api.start()
        recipient.api.start()
        val receivedMessageDeferred = async {
            withTimeout(1_000) { recipient.api.messages.first() }
        }

        // Act
        val sendResult = sender.api.send(recipient.peerId, payload)
        val receivedMessage = receivedMessageDeferred.await()

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertContentEquals(payload, receivedMessage.payload)
    }

    @Test
    fun `routing reconverges onto an alternate relay after a topology change`() = runBlocking {
        // Arrange
        val harness = MeshTestHarness()
        val sender = harness.createNode("peer-a")
        val firstRelay = harness.createNode("peer-b")
        val recipient = harness.createNode("peer-c")
        val alternateRelay = harness.createNode("peer-d")
        val firstPayload = "path-one".encodeToByteArray()
        val secondPayload = "path-two".encodeToByteArray()

        harness.linkPeers(sender, firstRelay)
        harness.linkPeers(firstRelay, recipient)

        sender.api.start()
        firstRelay.api.start()
        recipient.api.start()
        alternateRelay.api.start()

        val firstMessageDeferred = async {
            withTimeout(1_000) { recipient.api.messages.first() }
        }
        sender.api.send(recipient.peerId, firstPayload)
        firstMessageDeferred.await()

        harness.unlinkPeers(firstRelay, recipient)
        harness.linkPeers(sender, alternateRelay)
        harness.linkPeers(alternateRelay, recipient)
        val secondMessageDeferred = async {
            withTimeout(1_000) { recipient.api.messages.first() }
        }

        // Act
        val sendResult = sender.api.send(recipient.peerId, secondPayload)
        val receivedMessage = secondMessageDeferred.await()

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertContentEquals(secondPayload, receivedMessage.payload)
    }

    @Test
    fun `relay nodes do not surface end-to-end plaintext for forwarded traffic`() = runBlocking {
        // Arrange
        val harness = MeshTestHarness()
        val sender = harness.createNode("peer-a")
        val relay = harness.createNode("peer-b")
        val recipient = harness.createNode("peer-c")
        val plaintext = "private mesh payload"

        harness.linkPeers(sender, relay)
        harness.linkPeers(relay, recipient)

        sender.api.start()
        relay.api.start()
        recipient.api.start()
        val relayMessageDeferred = async {
            withTimeoutOrNull(500) { relay.api.messages.first() }
        }
        val recipientMessageDeferred = async {
            withTimeout(1_000) { recipient.api.messages.first() }
        }

        // Act
        val sendResult = sender.api.send(recipient.peerId, plaintext.encodeToByteArray())
        val relayMessage = relayMessageDeferred.await()
        val recipientMessage: InboundMessage = recipientMessageDeferred.await()
        val relayFramesContainPlaintext = harness.sentFrames(relay).any { frame ->
            frame.decodeToString().contains(plaintext)
        }

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertNull(relayMessage)
        assertFalse(relayFramesContainPlaintext)
        assertContentEquals(plaintext.encodeToByteArray(), recipientMessage.payload)
    }
}
