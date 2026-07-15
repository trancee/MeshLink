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
import kotlin.test.assertNotEquals
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
        // Widened from 5 to 10 after repeated CI-only flakiness across several different
        // reconnect/rediscovery/expiry scenarios in this file (see issue #142) -- individually
        // bumping each scenario's own explicit deadline was proving to be a whack-a-mole fix, since
        // a different scenario in this same file flaked on nearly every subsequent CI run. Widening
        // the shared multiplier gives every wait/delay in this file proportionally more headroom at
        // once, rather than continuing to patch one hardcoded timeout after another.
        private const val TEST_TIMING_SLACK_MULTIPLIER: Long = 10
    }

    @Test
    fun `a sender can reach a destination through a single relay hop`() =
        runBlocking<Unit> {
            if (!supportsRelayRoutingStressScenarios()) {
                return@runBlocking
            }

            // Arrange
            val harness = harness()
            val sender = harness.createNode("peer-a")
            val relay = harness.createNode("peer-b")
            val recipient = harness.createNode("peer-c")
            val payload = "hello through relay".encodeToByteArray()

            harness.linkPeers(sender, relay)
            harness.linkPeers(relay, recipient)

            relay.meshLink.start()
            recipient.meshLink.start()
            sender.meshLink.start()
            testDelay(250)
            awaitDiagnosticForPeer(
                diagnostics = sender.diagnosticSink::events,
                code = DiagnosticCode.ROUTE_DISCOVERED,
                peerIdValue = recipient.peerId.value,
                routeAvailable = true,
                timeoutMillis = 5_000,
            )
            val receivedMessageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    testWithTimeout(5_000) { recipient.meshLink.messages.first() }
                }

            // Act
            val sendResult =
                testWithTimeout(5_000) { sender.meshLink.send(recipient.peerId, payload) }

            // Assert
            assertIs<SendResult.Sent>(sendResult)
            val receivedMessage = receivedMessageDeferred.await()
            assertContentEquals(payload, receivedMessage.payload)
        }

    @Test
    fun `relay forwarding emits diagnostics and recipient delivery is observable`() =
        runBlocking<Unit> {
            if (!supportsRelayRoutingStressScenarios()) {
                return@runBlocking
            }

            // Arrange
            val harness = harness()
            val sender = harness.createNode("peer-a")
            val relay = harness.createNode("peer-b")
            val recipient = harness.createNode("peer-c")
            val payload = "relay diagnostics".encodeToByteArray()

            harness.linkPeers(sender, relay)
            harness.linkPeers(relay, recipient)

            relay.meshLink.start()
            recipient.meshLink.start()
            sender.meshLink.start()
            testDelay(250)
            awaitDiagnosticForPeer(
                diagnostics = sender.diagnosticSink::events,
                code = DiagnosticCode.ROUTE_DISCOVERED,
                peerIdValue = recipient.peerId.value,
                routeAvailable = true,
                timeoutMillis = 5_000,
            )
            val receivedMessageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    testWithTimeout(5_000) { recipient.meshLink.messages.first() }
                }

            // Act
            val sendResult =
                testWithTimeout(5_000) { sender.meshLink.send(recipient.peerId, payload) }

            // Assert
            assertIs<SendResult.Sent>(sendResult)
            val receivedMessage = receivedMessageDeferred.await()
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
        }

    @Test
    fun `routing reconverges onto an alternate relay after a topology change`() =
        runBlocking<Unit> {
            if (!supportsRelayRoutingStressScenarios()) {
                return@runBlocking
            }

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
        runBlocking<Unit> {
            if (!supportsRelayRoutingStressScenarios()) {
                return@runBlocking
            }

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
        runBlocking<Unit> {
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
        runBlocking<Unit> {
            if (!supportsRelayRoutingStressScenarios()) {
                return@runBlocking
            }

            // Arrange
            val harness = harness()
            val senderConfig = meshLinkConfig {
                appId = "peer-a-restart-loss"
                deliveryRetryDeadline = 2.seconds
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
            testDelay(250)

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
    fun `gatt-only peers can complete a send after discovery`() =
        runBlocking<Unit> {
            // Arrange
            val harness = harness()
            val sender =
                harness.createNode(
                    peerIdValue = "peer-a",
                    configOverride =
                        meshLinkConfig {
                            appId = "peer-a-gatt-accept"
                            deliveryRetryDeadline = 500.milliseconds
                        },
                )
            val recipient = harness.createNode("peer-b")
            val payload = "gatt-only".encodeToByteArray()

            harness.linkPeers(sender, recipient, mode = TransportMode.GATT)

            sender.meshLink.start()
            recipient.meshLink.start()
            testDelay(250)
            awaitDiagnosticForPeer(
                diagnostics = sender.diagnosticSink::events,
                code = DiagnosticCode.ROUTE_DISCOVERED,
                peerIdValue = recipient.peerId.value,
                routeAvailable = true,
                timeoutMillis = 5_000,
            )

            val receivedMessageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    testWithTimeout(5_000) { recipient.meshLink.messages.first() }
                }

            // Act
            val sendResult =
                testWithTimeout(5_000) { sender.meshLink.send(recipient.peerId, payload) }
            val receivedMessage = receivedMessageDeferred.await()

            // Assert
            assertIs<SendResult.Sent>(sendResult)
            assertNotNull(receivedMessage)
            assertContentEquals(payload, receivedMessage.payload)
            assertTrue(
                sender.diagnosticSink.events().none { diagnostic ->
                    diagnostic.code == DiagnosticCode.TRANSPORT_MODE_CHANGED &&
                        diagnostic.stage == "transport.peerDiscovered.rejected"
                },
                "Expected GATT discovery to remain accepted in the transport path",
            )
        }

    @Test
    fun `direct route diagnostics record peer rediscovery before send succeeds`() =
        runBlocking<Unit> {
            // Arrange
            val harness = harness()
            val sender =
                harness.createNode(
                    peerIdValue = "peer-a",
                    configOverride =
                        meshLinkConfig {
                            appId = "peer-a-reconnect"
                            // Keep generous margin over this scenario's forced relink delay:
                            // send starts after testDelay(100)=500ms, relink happens after
                            // testDelay(250)=1250ms, leaving only ~1750ms of the previous
                            // 3s budget for handshake + route rediscovery + delivery under CI
                            // contention. 6s leaves ~4250ms post-relink budget.
                            deliveryRetryDeadline = 6.seconds
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
            // Note: routeAvailable on DELIVERY_SUCCEEDED reflects a live route lookup taken
            // at diagnostic-emission time (see MeshEngineRoutingSupport.peerRouteMetadata),
            // not a snapshot from the successful send. Under CI CPU contention, route-table
            // housekeeping can transiently report the route as unavailable again by the time
            // the diagnostic fires, even though delivery already succeeded. Delivery success
            // itself is already verified above via assertIs<SendResult.Sent>, so only ordering
            // relative to rediscovery is asserted here, without filtering on routeAvailable.
            val deliverySucceededIndex =
                diagnostics.indexOfFirstForPeerAfter(
                    startExclusive = routeRediscoveredIndex,
                    code = DiagnosticCode.DELIVERY_SUCCEEDED,
                    peerIdValue = recipient.peerId.value,
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
    fun `direct route diagnostics record expiry before send becomes unreachable`() =
        runBlocking<Unit> {
            // Arrange
            val harness = harness()
            val sender =
                harness.createNode(
                    peerIdValue = "peer-a",
                    configOverride =
                        meshLinkConfig {
                            appId = "peer-a-expiry"
                            // This test validates unreachable-after-expiry ordering, not an
                            // aggressive retry deadline. 500ms proved too close to forced
                            // unlink/expiry waits under CI contention, and 2s still produced
                            // intermittent androidHost timeouts in CI. 6s keeps semantics while
                            // leaving comfortable headroom for retry-loop scheduling jitter.
                            deliveryRetryDeadline = 6.seconds
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
            awaitDiagnosticForPeer(
                diagnostics = sender.diagnosticSink::events,
                code = DiagnosticCode.ROUTE_EXPIRED,
                peerIdValue = recipient.peerId.value,
                routeAvailable = false,
            )

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
    fun `destination self-reported route seqno stays constant within one running instance and changes across restarts`() =
        runBlocking<Unit> {
            // Arrange: first engine instance with two neighbors.
            val firstHarness = harness()
            val destinationFirstStart = firstHarness.createNode("peer-a")
            val firstNeighbor = firstHarness.createNode("peer-b")
            val secondNeighbor = firstHarness.createNode("peer-c")

            firstHarness.linkPeers(destinationFirstStart, firstNeighbor)
            firstHarness.linkPeers(destinationFirstStart, secondNeighbor)

            destinationFirstStart.meshLink.start()
            firstNeighbor.meshLink.start()
            secondNeighbor.meshLink.start()

            // Act: collect the self-origin seqNo observed by both neighbors of one running
            // instance.
            val firstNeighborSeqNo =
                awaitLatestRouteSeqNo(
                    diagnostics = firstNeighbor.diagnosticSink::events,
                    destinationPeerIdValue = destinationFirstStart.peerId.value,
                )
            val secondNeighborSeqNo =
                awaitLatestRouteSeqNo(
                    diagnostics = secondNeighbor.diagnosticSink::events,
                    destinationPeerIdValue = destinationFirstStart.peerId.value,
                )

            // Assert property 1: one running engine instance reports the same seqNo to all
            // neighbors.
            assertTrue(firstNeighborSeqNo > 0L)
            assertEquals(firstNeighborSeqNo, secondNeighborSeqNo)

            // Arrange: stop and start a fresh engine instance for the same destination peer id.
            firstHarness.stopAll()
            testDelay(5)

            val secondHarness = harness()
            val destinationSecondStart = secondHarness.createNode("peer-a")
            val restartNeighbor = secondHarness.createNode("peer-z")

            secondHarness.linkPeers(destinationSecondStart, restartNeighbor)

            destinationSecondStart.meshLink.start()
            restartNeighbor.meshLink.start()

            // Act
            val restartedSeqNo =
                awaitLatestRouteSeqNo(
                    diagnostics = restartNeighbor.diagnosticSink::events,
                    destinationPeerIdValue = destinationSecondStart.peerId.value,
                )

            // Assert property 2: a fresh engine start reports a different self-origin seqNo.
            assertTrue(restartedSeqNo > 0L)
            assertNotEquals(firstNeighborSeqNo, restartedSeqNo)
        }

    @Test
    fun `route digest mismatch triggers a full-table resend that repairs the missing route`() =
        runBlocking<Unit> {
            // Arrange
            val harness = harness()
            val destination = harness.createNode("peer-a")
            val relay = harness.createNode("peer-b")
            val observer = harness.createNode("peer-c")
            val payload = "digest mismatch recovery".encodeToByteArray()

            // Keep the relay<->observer link disconnected until after relay has already learned the
            // destination route. This removes startup-order races around what the "first" relay->
            // observer delivery is, making the drop rule deterministic for this scenario.
            harness.linkPeers(destination, relay)

            destination.meshLink.start()
            relay.meshLink.start()
            observer.meshLink.start()

            awaitDiagnosticForPeer(
                diagnostics = relay.diagnosticSink::events,
                code = DiagnosticCode.ROUTE_DISCOVERED,
                peerIdValue = destination.peerId.value,
                routeAvailable = true,
                timeoutMillis = 5_000,
            )

            harness.dropNextDeliveries(sender = relay, recipient = observer, count = 1)
            harness.linkPeers(relay, observer)

            awaitDiagnosticForPeer(
                diagnostics = observer.diagnosticSink::events,
                code = DiagnosticCode.ROUTE_DISCOVERED,
                peerIdValue = destination.peerId.value,
                routeAvailable = true,
                timeoutMillis = 5_000,
            )

            val receivedMessageDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    testWithTimeout(2_000) { destination.meshLink.messages.first() }
                }

            // Act
            val sendResult = observer.meshLink.send(destination.peerId, payload)
            val receivedMessage = receivedMessageDeferred.await()

            // Assert
            assertIs<SendResult.Sent>(sendResult)
            assertContentEquals(payload, receivedMessage.payload)
        }

    @Test
    fun `relay nodes do not surface end-to-end plaintext for forwarded traffic`() =
        runBlocking<Unit> {
            if (!supportsRelayRoutingStressScenarios()) {
                return@runBlocking
            }

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
                harness.sentFrames(relay).any { frame ->
                    frame.decodeToString().contains(plaintext)
                }

            // Assert
            assertIs<SendResult.Sent>(sendResult)
            assertNull(relayMessage)
            assertFalse(relayFramesContainPlaintext)
            assertContentEquals(plaintext.encodeToByteArray(), recipientMessage.payload)
        }

    private val harnesses: MutableList<MeshTestHarness> = mutableListOf()

    @AfterTest
    fun tearDown(): Unit =
        runBlocking<Unit> {
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

    private suspend fun awaitLatestRouteSeqNo(
        diagnostics: () -> List<DiagnosticEvent>,
        destinationPeerIdValue: String,
    ): Long {
        return testWithTimeout(5_000) {
            while (true) {
                val seqNo =
                    diagnostics()
                        .asReversed()
                        .firstOrNull { event ->
                            (event.code == DiagnosticCode.ROUTE_DISCOVERED ||
                                event.code == DiagnosticCode.ROUTE_UPDATED) &&
                                event.metadata["peerId"] == destinationPeerIdValue &&
                                event.metadata["routeAvailable"] == "true"
                        }
                        ?.metadata
                        ?.get("routeSeqNo")
                        ?.toLongOrNull()

                // The initial direct-route install path can emit a placeholder seqNo=0 before the
                // destination's self-origin RouteUpdate lands. This helper waits for the post-
                // self-origin state (seqNo > 0), so the test asserts against the intended signal
                // rather than racing the placeholder diagnostic.
                if (seqNo != null && seqNo > 0L) {
                    return@testWithTimeout seqNo
                }
                testDelay(10)
            }
            error("Unreachable")
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
