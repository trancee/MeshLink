package ch.trancee.meshlink.integration

import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.test.MeshTestHarness
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class LargeTransferIntegrationTest {
    private companion object {
        private const val TEST_TIMING_SLACK_MULTIPLIER: Long = 4
        private const val ROUTE_DISCOVERY_TIMEOUT_MILLIS: Long = 8_000
    }

    @Test
    fun `a 64 KiB payload can cross a relay hop when the network requires chunking`() =
        runBlocking {
            // Arrange
            val harness = harness()
            val sender = harness.createNode("peer-a")
            val relay = harness.createNode("peer-b")
            val recipient = harness.createNode("peer-c")
            val payload = ByteArray(64 * 1024) { index -> (index % 251).toByte() }

            harness.linkPeers(sender, relay)
            harness.linkPeers(relay, recipient)
            harness.setMaximumPayloadBytesPerDelivery(512)

            sender.meshLink.start()
            relay.meshLink.start()
            recipient.meshLink.start()
            testDelay(250)
            awaitDiagnosticForPeer(
                diagnostics = sender.diagnosticSink::events,
                code = DiagnosticCode.ROUTE_DISCOVERED,
                peerIdValue = recipient.peerId.value,
                routeAvailable = true,
                timeoutMillis = ROUTE_DISCOVERY_TIMEOUT_MILLIS,
            )
            val receivedMessageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    testWithTimeout(6_000) { recipient.meshLink.messages.first() }
                }

            // Act
            val sendResult = sender.meshLink.send(recipient.peerId, payload)
            val receivedMessage = receivedMessageDeferred.await()

            // Assert
            assertIs<SendResult.Sent>(sendResult)
            assertContentEquals(payload, receivedMessage.payload)
        }

    @Test
    fun `a 64 KiB payload stays on the inline direct path when the transport budget allows it`() =
        runBlocking {
            // Arrange
            val harness = harness()
            val sender = harness.createNode("peer-a")
            val recipient = harness.createNode("peer-b")
            val payload = ByteArray(64 * 1024) { index -> ((index * 3) % 251).toByte() }

            harness.linkPeers(sender, recipient)
            harness.setMaximumPayloadBytesPerDelivery(128 * 1024)

            sender.meshLink.start()
            recipient.meshLink.start()
            testDelay(500)
            awaitDiagnosticForPeer(
                diagnostics = sender.diagnosticSink::events,
                code = DiagnosticCode.ROUTE_DISCOVERED,
                peerIdValue = recipient.peerId.value,
                routeAvailable = true,
                timeoutMillis = ROUTE_DISCOVERY_TIMEOUT_MILLIS,
            )
            val frameCountBeforeSend = harness.sentFrames(sender).size
            val receivedMessageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    testWithTimeout(6_000) { recipient.meshLink.messages.first() }
                }

            // Act
            val sendResult = sender.meshLink.send(recipient.peerId, payload)
            val receivedMessage = receivedMessageDeferred.await()
            val senderFrameDelta = harness.sentFrames(sender).size - frameCountBeforeSend

            // Assert
            assertIs<SendResult.Sent>(sendResult)
            assertContentEquals(payload, receivedMessage.payload)
            assertFalse(
                sender.diagnosticSink.events().any { event ->
                    event.code == DiagnosticCode.TRANSFER_STARTED
                }
            )
            assertTrue(
                senderFrameDelta < 16,
                "Expected inline direct delivery to avoid chunk fan-out, but sender emitted $senderFrameDelta frames",
            )
        }

    @Test
    fun `a large transfer resumes after the active route changes before the retry deadline expires`() =
        runBlocking {
            // Arrange
            val harness = harness()
            val sender = harness.createNode("peer-a")
            val firstRelay = harness.createNode("peer-b")
            val recipient = harness.createNode("peer-c")
            val alternateRelay = harness.createNode("peer-d")
            val payload = ByteArray(64 * 1024) { index -> ((index * 7) % 251).toByte() }

            harness.linkPeers(sender, firstRelay)
            harness.linkPeers(firstRelay, recipient)
            harness.setMaximumPayloadBytesPerDelivery(512)

            sender.meshLink.start()
            firstRelay.meshLink.start()
            recipient.meshLink.start()
            alternateRelay.meshLink.start()
            testDelay(250)
            awaitDiagnosticForPeer(
                diagnostics = sender.diagnosticSink::events,
                code = DiagnosticCode.ROUTE_DISCOVERED,
                peerIdValue = recipient.peerId.value,
                routeAvailable = true,
                timeoutMillis = ROUTE_DISCOVERY_TIMEOUT_MILLIS,
            )
            val sendResultDeferred = async { sender.meshLink.send(recipient.peerId, payload) }
            val receivedMessageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    testWithTimeout(4_000) { recipient.meshLink.messages.first() }
                }

            // Act
            testDelay(250)
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
            val harness = harness()
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
            sender.meshLink.start()
            relay.meshLink.start()
            recipient.meshLink.start()
            testDelay(250)
            val startedAt = TimeSource.Monotonic.markNow()

            // Act
            val sendResult = sender.meshLink.send(recipient.peerId, payload)

            // Assert
            val notSent = assertIs<SendResult.NotSent>(sendResult)
            assertEquals(SendFailureReason.UNREACHABLE, notSent.reason)
            assertTrue(startedAt.elapsedNow() >= 500.milliseconds)
            assertNull(testWithTimeoutOrNull(500) { recipient.meshLink.messages.first() })
        }

    @Test
    fun `pending large-transfer retries do not survive runtime restart until the host resubmits`() =
        runBlocking {
            // Arrange
            val harness = harness()
            val senderConfig = meshLinkConfig {
                appId = "peer-a-large-restart-loss"
                deliveryRetryDeadline = 1.seconds
            }
            val sender = harness.createNode(peerIdValue = "peer-a", configOverride = senderConfig)
            val relay = harness.createNode("peer-b")
            val recipient = harness.createNode("peer-c")
            val payload = ByteArray(64 * 1024) { index -> ((index * 23) % 251).toByte() }

            harness.linkPeers(sender, relay)
            harness.setMaximumPayloadBytesPerDelivery(512)

            sender.meshLink.start()
            relay.meshLink.start()
            recipient.meshLink.start()
            val originalSendDeferred = async { sender.meshLink.send(recipient.peerId, payload) }
            awaitDiagnostic(
                diagnostics = sender.diagnosticSink::events,
                code = DiagnosticCode.DELIVERY_RETRY_SCHEDULED,
            )
            sender.meshLink.stop()
            val restartedSender =
                harness.createNode(
                    peerIdValue = sender.peerId.value,
                    storage = sender.storage,
                    configOverride = senderConfig,
                )
            val restartedSenderFoundRelayDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    testWithTimeout(5_000) {
                        restartedSender.meshLink.peerEvents.first { event ->
                            event is PeerEvent.Found && event.peerId == relay.peerId
                        }
                    }
                }
            restartedSender.meshLink.start()
            harness.linkPeers(relay, recipient)
            restartedSenderFoundRelayDeferred.await()
            awaitDiagnostic(
                diagnostics = restartedSender.diagnosticSink::events,
                code = DiagnosticCode.ROUTE_DISCOVERED,
                timeoutMillis = ROUTE_DISCOVERY_TIMEOUT_MILLIS,
            )

            // Act
            val unexpectedMessage =
                testWithTimeoutOrNull(500) { recipient.meshLink.messages.first() }
            val resubmittedMessageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    testWithTimeout(10_000) { recipient.meshLink.messages.first() }
                }
            val resubmittedSendResult = restartedSender.meshLink.send(recipient.peerId, payload)
            val resubmittedMessage = resubmittedMessageDeferred.await()
            val originalSendResult = originalSendDeferred.await()

            // Assert
            val originalNotSent = assertIs<SendResult.NotSent>(originalSendResult)
            assertEquals(SendFailureReason.TRANSFER_ABORTED, originalNotSent.reason)
            assertNull(unexpectedMessage)
            assertIs<SendResult.Sent>(resubmittedSendResult)
            assertContentEquals(payload, resubmittedMessage.payload)
        }

    @Test
    fun `duplicate and out-of-order chunk delivery does not corrupt or redeliver the payload`() =
        runBlocking {
            if (!supportsSyntheticOutOfOrderChunkDelivery()) {
                return@runBlocking
            }

            // Arrange
            val harness = harness()
            val sender = harness.createNode("peer-a")
            val relay = harness.createNode("peer-b")
            val recipient = harness.createNode("peer-c")
            val payload = ByteArray(64 * 1024) { index -> ((index * 11) % 251).toByte() }

            harness.linkPeers(sender, relay)
            harness.linkPeers(relay, recipient)
            harness.setMaximumPayloadBytesPerDelivery(512)

            sender.meshLink.start()
            relay.meshLink.start()
            recipient.meshLink.start()
            testDelay(250)
            awaitDiagnosticForPeer(
                diagnostics = sender.diagnosticSink::events,
                code = DiagnosticCode.ROUTE_DISCOVERED,
                peerIdValue = recipient.peerId.value,
                routeAvailable = true,
                timeoutMillis = ROUTE_DISCOVERY_TIMEOUT_MILLIS,
            )
            val sendResultDeferred = async { sender.meshLink.send(recipient.peerId, payload) }
            val receivedMessageDeferred = async {
                testWithTimeout(10_000) { recipient.meshLink.messages.first() }
            }

            // Act
            testWithTimeout(5_000) {
                while (harness.sentFrames(relay).size < 4) {
                    testDelay(10)
                }
            }
            val relayFrameCountBeforeInterference = harness.sentFrames(relay).size
            harness.holdNextDeliveries(relay, recipient, count = 1)
            harness.duplicateNextDeliveries(relay, recipient, count = 1)
            testWithTimeout(5_000) {
                while (harness.sentFrames(relay).size < relayFrameCountBeforeInterference + 2) {
                    testDelay(10)
                }
            }
            harness.releaseHeldDeliveries(relay, recipient)
            val sendResult = sendResultDeferred.await()
            val receivedMessage = receivedMessageDeferred.await()
            val unexpectedSecondMessage =
                testWithTimeoutOrNull(500) { recipient.meshLink.messages.first() }

            // Assert
            assertIs<SendResult.Sent>(sendResult)
            assertContentEquals(payload, receivedMessage.payload)
            assertNull(unexpectedSecondMessage)
        }

    @Test
    fun `partial acknowledgements still allow the sender to complete the transfer`() = runBlocking {
        // Arrange
        val harness = harness()
        val sender = harness.createNode("peer-a")
        val relay = harness.createNode("peer-b")
        val recipient = harness.createNode("peer-c")
        val payload = ByteArray(64 * 1024) { index -> ((index * 13) % 251).toByte() }

        harness.linkPeers(sender, relay)
        harness.linkPeers(relay, recipient)
        harness.setMaximumPayloadBytesPerDelivery(512)

        sender.meshLink.start()
        relay.meshLink.start()
        recipient.meshLink.start()
        testDelay(250)
        awaitDiagnosticForPeer(
            diagnostics = sender.diagnosticSink::events,
            code = DiagnosticCode.ROUTE_DISCOVERED,
            peerIdValue = recipient.peerId.value,
            routeAvailable = true,
            timeoutMillis = ROUTE_DISCOVERY_TIMEOUT_MILLIS,
        )
        val receivedMessageDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                testWithTimeout(10_000) { recipient.meshLink.messages.first() }
            }
        val sendResultDeferred = async { sender.meshLink.send(recipient.peerId, payload) }

        // Act
        testWithTimeout(5_000) {
            while (harness.sentFrames(recipient).size < 3) {
                testDelay(10)
            }
        }
        harness.dropNextDeliveries(relay, sender, count = 2)
        harness.dropNextDeliveries(recipient, relay, count = 2)
        val receivedMessage = receivedMessageDeferred.await()
        val sendResult = testWithTimeout(10_000) { sendResultDeferred.await() }

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertContentEquals(payload, receivedMessage.payload)
    }

    @Test
    fun `timed out large transfers clear queued outbound frames before returning`() = runBlocking {
        // Arrange
        val harness = harness()
        val sender =
            harness.createNode(
                peerIdValue = "peer-a",
                configOverride =
                    meshLinkConfig {
                        appId = "peer-a-transfer-timeout"
                        deliveryRetryDeadline = 750.milliseconds
                    },
            )
        val recipient = harness.createNode("peer-b")
        val payload = ByteArray(64 * 1024) { index -> ((index * 19) % 251).toByte() }

        harness.linkPeers(sender, recipient)
        harness.setMaximumPayloadBytesPerDelivery(512)

        sender.meshLink.start()
        recipient.meshLink.start()
        testDelay(250)
        awaitDiagnosticForPeer(
            diagnostics = sender.diagnosticSink::events,
            code = DiagnosticCode.ROUTE_DISCOVERED,
            peerIdValue = recipient.peerId.value,
            routeAvailable = true,
            timeoutMillis = ROUTE_DISCOVERY_TIMEOUT_MILLIS,
        )
        harness.dropNextDeliveries(recipient, sender, count = 256)

        // Act
        val sendResult = testWithTimeout(10_000) { sender.meshLink.send(recipient.peerId, payload) }
        val clearedPeers = harness.clearedQueuedOutboundPeers(sender)

        // Assert
        val notSent = assertIs<SendResult.NotSent>(sendResult)
        assertEquals(SendFailureReason.TRANSFER_TIMED_OUT, notSent.reason)
        assertEquals(listOf(recipient.peerId.value), clearedPeers.map(PeerId::value))
    }

    @Test
    fun `chunked transfers emit recipient acknowledgement bursts before completion`() =
        runBlocking {
            // Arrange
            val harness = harness()
            val sender = harness.createNode("peer-a")
            val relay = harness.createNode("peer-b")
            val recipient = harness.createNode("peer-c")
            val payload = ByteArray(64 * 1024) { index -> ((index * 17) % 251).toByte() }

            harness.linkPeers(sender, relay)
            harness.linkPeers(relay, recipient)
            harness.setMaximumPayloadBytesPerDelivery(512)

            sender.meshLink.start()
            relay.meshLink.start()
            recipient.meshLink.start()
            testDelay(250)
            awaitDiagnosticForPeer(
                diagnostics = sender.diagnosticSink::events,
                code = DiagnosticCode.ROUTE_DISCOVERED,
                peerIdValue = recipient.peerId.value,
                routeAvailable = true,
                timeoutMillis = ROUTE_DISCOVERY_TIMEOUT_MILLIS,
            )
            val recipientFrameCountBeforeSend = harness.sentFrames(recipient).size
            val receivedMessageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    testWithTimeout(10_000) { recipient.meshLink.messages.first() }
                }

            // Act
            val sendResult = sender.meshLink.send(recipient.peerId, payload)
            val receivedMessage = receivedMessageDeferred.await()
            val recipientFrameDelta =
                harness.sentFrames(recipient).size - recipientFrameCountBeforeSend

            // Assert
            assertIs<SendResult.Sent>(sendResult)
            assertContentEquals(payload, receivedMessage.payload)
            assertTrue(
                recipientFrameDelta >= 4,
                "Expected the recipient to emit the start acknowledgement plus multiple chunk-settlement acknowledgements, but observed $recipientFrameDelta transfer frames",
            )
            assertTrue(
                sender.diagnosticSink.events().any { event ->
                    event.code == DiagnosticCode.TRANSFER_COMPLETED &&
                        event.stage == "transfer.send.complete"
                }
            )
        }

    @Test
    fun `payloads larger than 64 KiB are rejected before any transfer starts`() = runBlocking {
        // Arrange
        val harness = harness()
        val sender = harness.createNode("peer-a")
        val relay = harness.createNode("peer-b")
        val recipient = harness.createNode("peer-c")
        val oversizedPayload = ByteArray((64 * 1024) + 1) { 0x55 }

        harness.linkPeers(sender, relay)
        harness.linkPeers(relay, recipient)
        harness.setMaximumPayloadBytesPerDelivery(512)

        sender.meshLink.start()
        relay.meshLink.start()
        recipient.meshLink.start()
        testDelay(250)
        val frameCountBeforeSend = harness.sentFrames(sender).size

        // Act
        val sendResult = sender.meshLink.send(recipient.peerId, oversizedPayload)

        // Assert
        val notSent = assertIs<SendResult.NotSent>(sendResult)
        assertEquals(SendFailureReason.PAYLOAD_TOO_LARGE, notSent.reason)
        assertFalse(harness.sentFrames(sender).size > frameCountBeforeSend)
        assertNull(testWithTimeoutOrNull(500) { recipient.meshLink.messages.first() })
    }

    private val harnesses: MutableList<MeshTestHarness> = mutableListOf()

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        harnesses.asReversed().forEach { harness -> runCatching { harness.stopAll() } }
        harnesses.clear()
    }

    private fun harness(): MeshTestHarness = MeshTestHarness().also(harnesses::add)

    private suspend fun testDelay(milliseconds: Int): Unit =
        delay(milliseconds.toLong() * TEST_TIMING_SLACK_MULTIPLIER)

    private suspend fun testDelay(milliseconds: Long): Unit =
        delay(milliseconds * TEST_TIMING_SLACK_MULTIPLIER)

    private suspend fun <T> testWithTimeout(milliseconds: Int, block: suspend () -> T): T =
        withTimeout(milliseconds.toLong() * TEST_TIMING_SLACK_MULTIPLIER) { block() }

    private suspend fun <T> testWithTimeout(milliseconds: Long, block: suspend () -> T): T =
        withTimeout(milliseconds * TEST_TIMING_SLACK_MULTIPLIER) { block() }

    private suspend fun <T> testWithTimeoutOrNull(milliseconds: Int, block: suspend () -> T): T? =
        withTimeoutOrNull(milliseconds.toLong() * TEST_TIMING_SLACK_MULTIPLIER) { block() }

    private suspend fun <T> testWithTimeoutOrNull(milliseconds: Long, block: suspend () -> T): T? =
        withTimeoutOrNull(milliseconds * TEST_TIMING_SLACK_MULTIPLIER) { block() }

    private suspend fun awaitDiagnostic(
        diagnostics: () -> List<DiagnosticEvent>,
        code: DiagnosticCode,
        timeoutMillis: Long = 2_000,
    ): Unit {
        testWithTimeout(timeoutMillis) {
            while (diagnostics().none { event -> event.code == code }) {
                testDelay(10)
            }
        }
    }

    private suspend fun awaitDiagnosticForPeer(
        diagnostics: () -> List<DiagnosticEvent>,
        code: DiagnosticCode,
        peerIdValue: String,
        routeAvailable: Boolean? = null,
        timeoutMillis: Long = 2_000,
    ): Unit {
        testWithTimeout(timeoutMillis) {
            while (
                diagnostics()
                    .indexOfFirstForPeer(
                        code = code,
                        peerIdValue = peerIdValue,
                        routeAvailable = routeAvailable,
                    ) < 0
            ) {
                testDelay(10)
            }
        }
    }

    private fun List<DiagnosticEvent>.indexOfFirstForPeer(
        code: DiagnosticCode,
        peerIdValue: String,
        routeAvailable: Boolean? = null,
    ): Int {
        return indexOfFirst { event ->
            event.code == code &&
                event.metadata["peerId"] == peerIdValue &&
                (routeAvailable == null ||
                    event.metadata["routeAvailable"] == routeAvailable.toString())
        }
    }
}
