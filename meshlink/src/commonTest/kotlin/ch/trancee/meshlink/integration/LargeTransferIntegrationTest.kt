package ch.trancee.meshlink.integration

import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.test.MeshTestHarness
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class LargeTransferIntegrationTest {
    @Test
    fun `a 64 KiB payload can cross a relay hop when the network requires chunking`() =
        runBlocking {
            // Arrange
            val harness = MeshTestHarness()
            val sender = harness.createNode("peer-a")
            val relay = harness.createNode("peer-b")
            val recipient = harness.createNode("peer-c")
            val payload = ByteArray(64 * 1024) { index -> (index % 251).toByte() }

            harness.linkPeers(sender, relay)
            harness.linkPeers(relay, recipient)
            harness.setMaximumPayloadBytesPerDelivery(512)

            sender.api.start()
            relay.api.start()
            recipient.api.start()
            delay(250)
            val receivedMessageDeferred = async {
                withTimeout(3_000) { recipient.api.messages.first() }
            }

            // Act
            val sendResult = sender.api.send(recipient.peerId, payload)
            val receivedMessage = receivedMessageDeferred.await()

            // Assert
            assertIs<SendResult.Sent>(sendResult)
            assertContentEquals(payload, receivedMessage.payload)
        }

    @Test
    fun `a large transfer resumes after the active route changes before the retry deadline expires`() =
        runBlocking {
            // Arrange
            val harness = MeshTestHarness()
            val sender = harness.createNode("peer-a")
            val firstRelay = harness.createNode("peer-b")
            val recipient = harness.createNode("peer-c")
            val alternateRelay = harness.createNode("peer-d")
            val payload = ByteArray(64 * 1024) { index -> ((index * 7) % 251).toByte() }

            harness.linkPeers(sender, firstRelay)
            harness.linkPeers(firstRelay, recipient)
            harness.setMaximumPayloadBytesPerDelivery(512)

            sender.api.start()
            firstRelay.api.start()
            recipient.api.start()
            alternateRelay.api.start()
            delay(250)
            val sendResultDeferred = async { sender.api.send(recipient.peerId, payload) }
            val receivedMessageDeferred = async {
                withTimeout(4_000) { recipient.api.messages.first() }
            }

            // Act
            delay(250)
            harness.unlinkPeers(firstRelay, recipient)
            harness.linkPeers(sender, alternateRelay)
            harness.linkPeers(alternateRelay, recipient)
            val sendResult = sendResultDeferred.await()
            val receivedMessage = receivedMessageDeferred.await()

            // Assert
            assertIs<SendResult.Sent>(sendResult)
            assertContentEquals(payload, receivedMessage.payload)
        }

    @Test
    fun `large transfers return unreachable when no route appears before the configured deadline`() =
        runBlocking {
            // Arrange
            val harness = MeshTestHarness()
            val sender =
                harness.createNode(
                    peerIdValue = "peer-a",
                    configOverride =
                        meshLinkConfig {
                            appId = "peer-a-default"
                            deliveryRetryDeadline = 500.milliseconds
                        },
                )
            val relay = harness.createNode("peer-b")
            val recipient = harness.createNode("peer-c")
            val payload = ByteArray(2 * 1024) { index -> ((index * 5) % 251).toByte() }

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
            assertNull(withTimeoutOrNull(500) { recipient.api.messages.first() })
        }

    @Test
    fun `duplicate and out-of-order chunk delivery does not corrupt or redeliver the payload`() =
        runBlocking {
            // Arrange
            val harness = MeshTestHarness()
            val sender = harness.createNode("peer-a")
            val relay = harness.createNode("peer-b")
            val recipient = harness.createNode("peer-c")
            val payload = ByteArray(64 * 1024) { index -> ((index * 11) % 251).toByte() }

            harness.linkPeers(sender, relay)
            harness.linkPeers(relay, recipient)
            harness.setMaximumPayloadBytesPerDelivery(512)

            sender.api.start()
            relay.api.start()
            recipient.api.start()
            delay(250)
            val sendResultDeferred = async { sender.api.send(recipient.peerId, payload) }
            val receivedMessageDeferred = async {
                withTimeout(6_000) { recipient.api.messages.first() }
            }

            // Act
            delay(250)
            harness.holdNextDeliveries(relay, recipient, count = 1)
            harness.duplicateNextDeliveries(relay, recipient, count = 1)
            delay(250)
            harness.releaseHeldDeliveries(relay, recipient)
            val sendResult = sendResultDeferred.await()
            val receivedMessage = receivedMessageDeferred.await()
            val unexpectedSecondMessage = withTimeoutOrNull(500) { recipient.api.messages.first() }

            // Assert
            assertIs<SendResult.Sent>(sendResult)
            assertContentEquals(payload, receivedMessage.payload)
            assertNull(unexpectedSecondMessage)
        }

    @Test
    fun `partial acknowledgements still allow the sender to complete the transfer`() = runBlocking {
        // Arrange
        val harness = MeshTestHarness()
        val sender = harness.createNode("peer-a")
        val relay = harness.createNode("peer-b")
        val recipient = harness.createNode("peer-c")
        val payload = ByteArray(64 * 1024) { index -> ((index * 13) % 251).toByte() }

        harness.linkPeers(sender, relay)
        harness.linkPeers(relay, recipient)
        harness.setMaximumPayloadBytesPerDelivery(512)

        sender.api.start()
        relay.api.start()
        recipient.api.start()
        delay(250)
        val receivedMessageDeferred = async {
            withTimeout(10_000) { recipient.api.messages.first() }
        }
        val sendResultDeferred = async { sender.api.send(recipient.peerId, payload) }

        // Act
        delay(250)
        harness.dropNextDeliveries(relay, sender, count = 2)
        harness.dropNextDeliveries(recipient, relay, count = 2)
        val receivedMessage = receivedMessageDeferred.await()
        val sendResult = withTimeout(10_000) { sendResultDeferred.await() }

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertContentEquals(payload, receivedMessage.payload)
    }

    @Test
    fun `payloads larger than 64 KiB are rejected before any transfer starts`() = runBlocking {
        // Arrange
        val harness = MeshTestHarness()
        val sender = harness.createNode("peer-a")
        val relay = harness.createNode("peer-b")
        val recipient = harness.createNode("peer-c")
        val oversizedPayload = ByteArray((64 * 1024) + 1) { 0x55 }

        harness.linkPeers(sender, relay)
        harness.linkPeers(relay, recipient)
        harness.setMaximumPayloadBytesPerDelivery(512)

        sender.api.start()
        relay.api.start()
        recipient.api.start()
        delay(250)
        val frameCountBeforeSend = harness.sentFrames(sender).size

        // Act
        val sendResult = sender.api.send(recipient.peerId, oversizedPayload)

        // Assert
        val notSent = assertIs<SendResult.NotSent>(sendResult)
        assertEquals(SendFailureReason.PAYLOAD_TOO_LARGE, notSent.reason)
        assertFalse(harness.sentFrames(sender).size > frameCountBeforeSend)
        assertNull(withTimeoutOrNull(500) { recipient.api.messages.first() })
    }
}
