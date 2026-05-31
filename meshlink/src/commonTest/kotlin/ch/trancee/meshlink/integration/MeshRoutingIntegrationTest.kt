package ch.trancee.meshlink.integration

import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.test.MeshTestHarness
import ch.trancee.meshlink.test.NodeHandle
import ch.trancee.meshlink.transport.TransportMode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class MeshRoutingIntegrationTest {
    private companion object {
        private const val TEST_TIMING_SLACK_MULTIPLIER: Long = 5
    }

    @Test
    fun `a sender can reach a destination through a single relay hop`() = runBlocking {
        // Arrange
        val harness = harness()
        val sender = harness.createNode("peer-a")
        val relay = harness.createNode("peer-b")
        val recipient = harness.createNode("peer-c")
        val payload = "hello through relay".encodeToByteArray()

        harness.linkPeers(sender, relay)
        harness.linkPeers(relay, recipient)

        sender.meshLink.start()
        relay.meshLink.start()
        recipient.meshLink.start()
        awaitDiagnosticForPeer(
            diagnostics = sender.diagnosticSink::events,
            code = DiagnosticCode.ROUTE_DISCOVERED,
            peerIdValue = recipient.peerId.value,
            routeAvailable = true,
        )
        val receivedMessageDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                testWithTimeout(1_000) { recipient.meshLink.messages.first() }
            }

        // Act
        val sendResult = sender.meshLink.send(recipient.peerId, payload)
        val receivedMessage = receivedMessageDeferred.await()

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertContentEquals(payload, receivedMessage.payload)
    }

    @Test
    fun `relay forwarding emits diagnostics and recipient delivery is observable`() = runBlocking {
        // Arrange
        val harness = harness()
        val sender = harness.createNode("peer-a")
        val relay = harness.createNode("peer-b")
        val recipient = harness.createNode("peer-c")
        val payload = "relay diagnostics".encodeToByteArray()

        harness.linkPeers(sender, relay)
        harness.linkPeers(relay, recipient)

        sender.meshLink.start()
        relay.meshLink.start()
        recipient.meshLink.start()
        awaitDiagnosticForPeer(
            diagnostics = sender.diagnosticSink::events,
            code = DiagnosticCode.ROUTE_DISCOVERED,
            peerIdValue = recipient.peerId.value,
            routeAvailable = true,
        )
        val receivedMessageDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                testWithTimeout(1_000) { recipient.meshLink.messages.first() }
            }

        // Act
        val sendResult = sender.meshLink.send(recipient.peerId, payload)
        val receivedMessage = receivedMessageDeferred.await()

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertContentEquals(payload, receivedMessage.payload)
        val relayDiagnostics = relay.diagnosticSink.events()
        val relayQueued = relayDiagnostics.firstOrNull { event ->
            event.code == DiagnosticCode.DELIVERY_QUEUED &&
                event.stage == "forward.message.queued" &&
                event.metadata["peerId"] == recipient.peerId.value &&
                event.metadata["originPeerId"] == sender.peerId.value &&
                event.metadata["routeAvailable"] == "true"
        }
        val relayDelivered = relayDiagnostics.firstOrNull { event ->
            event.code == DiagnosticCode.DELIVERY_SUCCEEDED &&
                event.stage == "forward.message.delivered" &&
                event.metadata["peerId"] == recipient.peerId.value &&
                event.metadata["originPeerId"] == sender.peerId.value &&
                event.metadata["routeAvailable"] == "true"
        }
        val recipientDelivered =
            recipient.diagnosticSink.events().firstOrNull { event ->
                event.code == DiagnosticCode.DELIVERY_SUCCEEDED &&
                    event.stage == "transport.data.deliver" &&
                    event.metadata["peerId"] == sender.peerId.value &&
                    event.metadata["originPeerId"] == sender.peerId.value &&
                    event.metadata["immediatePeerId"] == relay.peerId.value &&
                    event.metadata["payloadBytes"] == payload.size.toString()
            }
        assertNotNull(relayQueued, "Expected relay to log queued forwarding diagnostics")
        assertNotNull(relayDelivered, "Expected relay to log successful forwarding diagnostics")
        assertNotNull(
            recipientDelivered,
            "Expected recipient to log delivery diagnostics after inbound message emission",
        )
        Unit
    }

    @Test
    fun `routing reconverges onto an alternate relay after a topology change`() = runBlocking {
        // Arrange
        val harness = harness()
        val sender = harness.createNode("peer-a")
        val firstRelay = harness.createNode("peer-b")
        val recipient = harness.createNode("peer-c")
        val alternateRelay = harness.createNode("peer-d")
        val firstPayload = "path-one".encodeToByteArray()
        val secondPayload = "path-two".encodeToByteArray()

        harness.linkPeers(sender, firstRelay)
        harness.linkPeers(firstRelay, recipient)

        sender.meshLink.start()
        firstRelay.meshLink.start()
        recipient.meshLink.start()
        alternateRelay.meshLink.start()
        testDelay(250)

        val firstMessageDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                testWithTimeout(1_000) { recipient.meshLink.messages.first() }
            }
        sender.meshLink.send(recipient.peerId, firstPayload)
        firstMessageDeferred.await()

        harness.unlinkPeers(firstRelay, recipient)
        harness.linkPeers(sender, alternateRelay)
        harness.linkPeers(alternateRelay, recipient)
        testDelay(250)
        val secondMessageDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                testWithTimeout(1_000) { recipient.meshLink.messages.first() }
            }

        // Act
        val sendResult = sender.meshLink.send(recipient.peerId, secondPayload)
        val receivedMessage = secondMessageDeferred.await()

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertContentEquals(secondPayload, receivedMessage.payload)
    }

    @Test
    fun `send retries immediately when a route appears before the delivery deadline expires`() =
        runBlocking {
            // Arrange
            val harness = harness()
            val sender = harness.createNode("peer-a")
            val relay = harness.createNode("peer-b")
            val recipient = harness.createNode("peer-c")
            val payload = "late route".encodeToByteArray()

            harness.linkPeers(sender, relay)

            sender.meshLink.start()
            relay.meshLink.start()
            recipient.meshLink.start()
            val sendResultDeferred = async { sender.meshLink.send(recipient.peerId, payload) }
            val receivedMessageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    testWithTimeout(2_000) { recipient.meshLink.messages.first() }
                }

            // Act
            testDelay(250)
            harness.linkPeers(relay, recipient)
            val sendResult = sendResultDeferred.await()
            val receivedMessage = receivedMessageDeferred.await()

            // Assert
            assertIs<SendResult.Sent>(sendResult)
            assertContentEquals(payload, receivedMessage.payload)
        }

    @Test
    fun `send returns unreachable when no route appears before the configured deadline`() =
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
            val payload = "no route".encodeToByteArray()

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
        }

    @Test
    fun `pending no route retries do not survive runtime restart until the host resubmits`() =
        runBlocking {
            // Arrange
            val harness = harness()
            val senderConfig = meshLinkConfig {
                appId = "peer-a-restart-loss"
                deliveryRetryDeadline = 1.seconds
            }
            val sender = harness.createNode(peerIdValue = "peer-a", configOverride = senderConfig)
            val relay = harness.createNode("peer-b")
            val recipient = harness.createNode("peer-c")
            val payload = "resubmit after restart".encodeToByteArray()

            harness.linkPeers(sender, relay)

            sender.meshLink.start()
            relay.meshLink.start()
            recipient.meshLink.start()
            val originalSendDeferred = async { sender.meshLink.send(recipient.peerId, payload) }
            awaitDiagnosticForPeer(
                diagnostics = sender.diagnosticSink::events,
                code = DiagnosticCode.DELIVERY_RETRY_SCHEDULED,
                peerIdValue = recipient.peerId.value,
                timeoutMillis = 5_000,
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
                            event is ch.trancee.meshlink.api.PeerEvent.Found &&
                                event.peerId == relay.peerId
                        }
                    }
                }
            harness.linkPeers(relay, recipient)
            restartedSender.meshLink.start()
            val unexpectedMessageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    testWithTimeoutOrNull(1_500) { recipient.meshLink.messages.first() }
                }
            restartedSenderFoundRelayDeferred.await()
            awaitDiagnosticForPeer(
                diagnostics = restartedSender.diagnosticSink::events,
                code = DiagnosticCode.ROUTE_DISCOVERED,
                peerIdValue = recipient.peerId.value,
                routeAvailable = true,
                timeoutMillis = 5_000,
            )

            // Act
            val unexpectedMessage = unexpectedMessageDeferred.await()
            val resubmittedMessageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    testWithTimeout(5_000) { recipient.meshLink.messages.first() }
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
    fun `gatt-only peers are rejected and remain unreachable`() = runBlocking {
        // Arrange
        val harness = harness()
        val sender =
            harness.createNode(
                peerIdValue = "peer-a",
                configOverride =
                    meshLinkConfig {
                        appId = "peer-a-gatt-reject"
                        deliveryRetryDeadline = 500.milliseconds
                    },
            )
        val recipient = harness.createNode("peer-b")
        val payload = "gatt-only".encodeToByteArray()

        harness.linkPeers(sender, recipient, mode = TransportMode.GATT)

        sender.meshLink.start()
        recipient.meshLink.start()
        testDelay(250)

        // Act
        val sendResult = sender.meshLink.send(recipient.peerId, payload)
        val receivedMessage = testWithTimeoutOrNull(250) { recipient.meshLink.messages.first() }

        // Assert
        val notSent = assertIs<SendResult.NotSent>(sendResult)
        assertEquals(SendFailureReason.UNREACHABLE, notSent.reason)
        assertNull(receivedMessage)
        assertTrue(
            sender.diagnosticSink.events().any { diagnostic ->
                diagnostic.code == DiagnosticCode.TRANSPORT_MODE_CHANGED &&
                    diagnostic.stage == "transport.peerDiscovered.rejected"
            },
            "Expected GATT-only discovery to emit an explicit transport rejection diagnostic",
        )
    }

    @Test
    fun `direct route diagnostics record peer rediscovery before send succeeds`() = runBlocking {
        // Arrange
        val harness = harness()
        val sender =
            harness.createNode(
                peerIdValue = "peer-a",
                configOverride =
                    meshLinkConfig {
                        appId = "peer-a-reconnect"
                        deliveryRetryDeadline = 3.seconds
                    },
            )
        val recipient = harness.createNode("peer-b")
        val payload = "peer rediscovery".encodeToByteArray()

        harness.linkPeers(sender, recipient)

        sender.meshLink.start()
        recipient.meshLink.start()
        prewarmRoute(sender = sender, recipient = recipient)
        harness.unlinkPeers(sender, recipient)
        testDelay(100)
        val sendResultDeferred = async { sender.meshLink.send(recipient.peerId, payload) }
        val receivedMessageDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                testWithTimeout(2_000) { recipient.meshLink.messages.first() }
            }

        // Act
        testDelay(250)
        harness.linkPeers(sender, recipient)
        val sendResult = sendResultDeferred.await()
        val receivedMessage = receivedMessageDeferred.await()

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertContentEquals(payload, receivedMessage.payload)
        val diagnostics = sender.diagnosticSink.events()
        val routeExpiredIndex =
            diagnostics.indexOfFirstForPeer(
                code = DiagnosticCode.ROUTE_EXPIRED,
                peerIdValue = recipient.peerId.value,
            )
        val routeRediscoveredIndex =
            diagnostics.indexOfFirstForPeerAfter(
                startExclusive = routeExpiredIndex,
                code = DiagnosticCode.ROUTE_DISCOVERED,
                peerIdValue = recipient.peerId.value,
                routeAvailable = true,
            )
        val deliverySucceededIndex =
            diagnostics.indexOfFirstForPeerAfter(
                startExclusive = routeRediscoveredIndex,
                code = DiagnosticCode.DELIVERY_SUCCEEDED,
                peerIdValue = recipient.peerId.value,
                routeAvailable = true,
            )
        assertTrue(
            routeExpiredIndex >= 0,
            "Expected a ROUTE_EXPIRED diagnostic for the rediscovered peer",
        )
        assertTrue(
            routeRediscoveredIndex > routeExpiredIndex,
            "Expected ROUTE_DISCOVERED after ROUTE_EXPIRED when the peer reappears",
        )
        assertTrue(
            deliverySucceededIndex > routeRediscoveredIndex,
            "Expected DELIVERY_SUCCEEDED after the route reappears",
        )
    }

    @Test
    fun `direct route diagnostics record expiry before send becomes unreachable`() = runBlocking {
        // Arrange
        val harness = harness()
        val sender =
            harness.createNode(
                peerIdValue = "peer-a",
                configOverride =
                    meshLinkConfig {
                        appId = "peer-a-expiry"
                        deliveryRetryDeadline = 500.milliseconds
                    },
            )
        val recipient = harness.createNode("peer-b")
        val payload = "route expired".encodeToByteArray()

        harness.linkPeers(sender, recipient)

        sender.meshLink.start()
        recipient.meshLink.start()
        prewarmRoute(sender = sender, recipient = recipient)
        harness.unlinkPeers(sender, recipient)
        testDelay(100)

        // Act
        val sendResult = sender.meshLink.send(recipient.peerId, payload)

        // Assert
        val notSent = assertIs<SendResult.NotSent>(sendResult)
        assertEquals(SendFailureReason.UNREACHABLE, notSent.reason)
        val diagnostics = sender.diagnosticSink.events()
        val routeExpiredIndex =
            diagnostics.indexOfFirstForPeer(
                code = DiagnosticCode.ROUTE_EXPIRED,
                peerIdValue = recipient.peerId.value,
            )
        val deliveryUnreachableIndex =
            diagnostics.indexOfFirstForPeerAfter(
                startExclusive = routeExpiredIndex,
                code = DiagnosticCode.DELIVERY_UNREACHABLE,
                peerIdValue = recipient.peerId.value,
                routeAvailable = false,
            )
        assertTrue(
            routeExpiredIndex >= 0,
            "Expected a ROUTE_EXPIRED diagnostic after unlinking the peer",
        )
        assertTrue(
            deliveryUnreachableIndex > routeExpiredIndex,
            "Expected DELIVERY_UNREACHABLE with routeAvailable=false after the route expires",
        )
    }

    @Test
    fun `relay nodes do not surface end-to-end plaintext for forwarded traffic`() = runBlocking {
        // Arrange
        val harness = harness()
        val sender = harness.createNode("peer-a")
        val relay = harness.createNode("peer-b")
        val recipient = harness.createNode("peer-c")
        val plaintext = "private mesh payload"

        harness.linkPeers(sender, relay)
        harness.linkPeers(relay, recipient)

        sender.meshLink.start()
        relay.meshLink.start()
        recipient.meshLink.start()
        awaitDiagnosticForPeer(
            diagnostics = sender.diagnosticSink::events,
            code = DiagnosticCode.ROUTE_DISCOVERED,
            peerIdValue = recipient.peerId.value,
            routeAvailable = true,
        )
        val relayMessageDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                testWithTimeoutOrNull(500) { relay.meshLink.messages.first() }
            }
        val recipientMessageDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                testWithTimeout(1_000) { recipient.meshLink.messages.first() }
            }

        // Act
        val sendResult = sender.meshLink.send(recipient.peerId, plaintext.encodeToByteArray())
        val relayMessage = relayMessageDeferred.await()
        val recipientMessage: InboundMessage = recipientMessageDeferred.await()
        val relayFramesContainPlaintext =
            harness.sentFrames(relay).any { frame -> frame.decodeToString().contains(plaintext) }

        // Assert
        assertIs<SendResult.Sent>(sendResult)
        assertNull(relayMessage)
        assertFalse(relayFramesContainPlaintext)
        assertContentEquals(plaintext.encodeToByteArray(), recipientMessage.payload)
    }

    private val harnesses: MutableList<MeshTestHarness> = mutableListOf()

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        harnesses.asReversed().forEach { harness -> runCatching { harness.stopAll() } }
        harnesses.clear()
    }

    private fun harness(): MeshTestHarness = MeshTestHarness().also(harnesses::add)

    private suspend fun prewarmRoute(
        sender: NodeHandle,
        recipient: NodeHandle,
        payload: ByteArray = "route warmup".encodeToByteArray(),
    ): Unit = coroutineScope {
        val warmupMessageDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                testWithTimeout(2_000) { recipient.meshLink.messages.first() }
            }
        val warmupSendResult = sender.meshLink.send(recipient.peerId, payload)
        val warmupMessage = warmupMessageDeferred.await()

        assertIs<SendResult.Sent>(warmupSendResult)
        assertContentEquals(payload, warmupMessage.payload)
    }

    private suspend fun testDelay(milliseconds: Int): Unit = delay(milliseconds.toLong())

    private suspend fun testDelay(milliseconds: Long): Unit = delay(milliseconds)

    private suspend fun <T> testWithTimeout(milliseconds: Int, block: suspend () -> T): T =
        withTimeout(milliseconds.toLong() * TEST_TIMING_SLACK_MULTIPLIER) { block() }

    private suspend fun <T> testWithTimeout(milliseconds: Long, block: suspend () -> T): T =
        withTimeout(milliseconds * TEST_TIMING_SLACK_MULTIPLIER) { block() }

    private suspend fun <T> testWithTimeoutOrNull(milliseconds: Int, block: suspend () -> T): T? =
        withTimeoutOrNull(milliseconds.toLong() * TEST_TIMING_SLACK_MULTIPLIER) { block() }

    private suspend fun <T> testWithTimeoutOrNull(milliseconds: Long, block: suspend () -> T): T? =
        withTimeoutOrNull(milliseconds * TEST_TIMING_SLACK_MULTIPLIER) { block() }

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

    private fun List<DiagnosticEvent>.indexOfFirstForPeerAfter(
        startExclusive: Int,
        code: DiagnosticCode,
        peerIdValue: String,
        routeAvailable: Boolean? = null,
    ): Int {
        if (startExclusive < -1) {
            return -1
        }
        return subList((startExclusive + 1).coerceAtMost(size), size)
            .indexOfFirst { event ->
                event.code == code &&
                    event.metadata["peerId"] == peerIdValue &&
                    (routeAvailable == null ||
                        event.metadata["routeAvailable"] == routeAvailable.toString())
            }
            .let { relativeIndex ->
                if (relativeIndex < 0) {
                    -1
                } else {
                    startExclusive + 1 + relativeIndex
                }
            }
    }
}
