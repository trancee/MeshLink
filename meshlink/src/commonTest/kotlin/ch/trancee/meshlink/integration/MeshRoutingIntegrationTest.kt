package ch.trancee.meshlink.integration

import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.test.MeshTestHarness
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

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
        delay(250)
        val receivedMessageDeferred = async(start = CoroutineStart.UNDISPATCHED) {
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
        delay(250)

        val firstMessageDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { recipient.api.messages.first() }
        }
        sender.api.send(recipient.peerId, firstPayload)
        firstMessageDeferred.await()

        harness.unlinkPeers(firstRelay, recipient)
        harness.linkPeers(sender, alternateRelay)
        harness.linkPeers(alternateRelay, recipient)
        delay(250)
        val secondMessageDeferred = async(start = CoroutineStart.UNDISPATCHED) {
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
    fun `send retries immediately when a route appears before the delivery deadline expires`() = runBlocking {
        // Arrange
        val harness = MeshTestHarness()
        val sender = harness.createNode("peer-a")
        val relay = harness.createNode("peer-b")
        val recipient = harness.createNode("peer-c")
        val payload = "late route".encodeToByteArray()

        harness.linkPeers(sender, relay)

        sender.api.start()
        relay.api.start()
        recipient.api.start()
        val sendResultDeferred = async {
            sender.api.send(recipient.peerId, payload)
        }
        val receivedMessageDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { recipient.api.messages.first() }
        }

        // Act
        delay(250)
        harness.linkPeers(relay, recipient)
        val sendResult = sendResultDeferred.await()
        val receivedMessage = receivedMessageDeferred.await()

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertContentEquals(payload, receivedMessage.payload)
    }

    @Test
    fun `send returns unreachable when no route appears before the configured deadline`() = runBlocking {
        // Arrange
        val harness = MeshTestHarness()
        val sender = harness.createNode(
            peerIdValue = "peer-a",
            configOverride = meshLinkConfig {
                appId = "peer-a-default"
                deliveryRetryDeadline = 500.milliseconds
            },
        )
        val relay = harness.createNode("peer-b")
        val recipient = harness.createNode("peer-c")
        val payload = "no route".encodeToByteArray()

        harness.linkPeers(sender, relay)

        sender.api.start()
        relay.api.start()
        recipient.api.start()
        delay(250)
        val startedAt = TimeSource.Monotonic.markNow()

        // Act
        val sendResult = sender.api.send(recipient.peerId, payload)

        // Assert
        val notSent = assertIs<SendResult.NotSent>(sendResult)
        assertEquals(SendFailureReason.UNREACHABLE, notSent.reason)
        assertTrue(startedAt.elapsedNow() >= 500.milliseconds)
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
        delay(250)
        val relayMessageDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(500) { relay.api.messages.first() }
        }
        val recipientMessageDeferred = async(start = CoroutineStart.UNDISPATCHED) {
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
