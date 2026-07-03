package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.TransferChunkPlan
import ch.trancee.meshlink.transfer.TransferSessionRoute
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MeshEngineLargeTransferOutboundDeliveryAdapterTest {
    @Test
    fun `attemptOutboundDelivery returns await retry when no route is available`() =
        runBlocking<Unit> {
            // Arrange
            val retryDiagnostics = mutableListOf<Pair<PeerId, DeliveryPriority>>()
            val adapter =
                largeTransferOutboundDeliveryAdapter(
                    outboundTransferLifecycleSupport =
                        outboundTransferLifecycleSupport(
                            scheduleRetryDiagnostic = { peerId, priority ->
                                retryDiagnostics += peerId to priority
                            },
                            prepareOutboundTransferSession = { _, _, _ ->
                                OutboundTransferPreparation.PendingRoute
                            },
                        )
                )
            val context = largeTransferAttemptContext()
            val state = adapter.beginOutboundDelivery(context)

            // Act
            val outcome = adapter.attemptOutboundDelivery(state, context)

            // Assert
            val awaitRetry =
                assertIs<
                    MeshEngineOutboundDeliveryAttemptOutcome.AwaitRetry<
                        MeshEngineLargeTransferOutboundDeliveryState
                    >
                >(
                    outcome
                )
            assertEquals(listOf(context.peerId to context.priority), retryDiagnostics)
            assertEquals(false, awaitRetry.nextState.lastRouteAvailable)
            assertEquals(true, awaitRetry.retryPolicy.emitDiagnostics)
        }

    @Test
    fun `attemptOutboundDelivery returns retry immediately when acknowledgement progress is observed`() =
        runBlocking<Unit> {
            // Arrange
            var outboundSession: OutboundTransferSession? = null
            var transferStartFrames = 0
            val adapter =
                largeTransferOutboundDeliveryAdapter(
                    outboundTransferLifecycleSupport =
                        outboundTransferLifecycleSupport(
                            prepareOutboundTransferSession = { destinationPeerId, _, _ ->
                                if (outboundSession == null) {
                                    outboundSession = testOutboundTransferSession(destinationPeerId)
                                }
                                OutboundTransferPreparation.Ready(checkNotNull(outboundSession))
                            }
                        ),
                    sendTransferTowardsDestination = { _, frame, _, _ ->
                        when (frame) {
                            is WireFrame.TransferStart -> {
                                transferStartFrames += 1
                                true
                            }
                            is WireFrame.TransferChunk -> {
                                if (transferStartFrames == 1 && frame.chunkIndex == 0) {
                                    launch {
                                        delay(10)
                                        val activeSession = checkNotNull(outboundSession)
                                        activeSession.markAcknowledged(
                                            WireFrame.TransferAck(
                                                transferId = activeSession.transferId,
                                                highestContiguousAck = 0,
                                                selectiveRanges = byteArrayOf(),
                                            )
                                        )
                                    }
                                }
                                true
                            }
                            is WireFrame.TransferAck,
                            is WireFrame.TransferComplete,
                            is WireFrame.TransferAbort,
                            is WireFrame.Message,
                            is WireFrame.Hello,
                            is WireFrame.LinkIdentity,
                            is WireFrame.Ihu,
                            is WireFrame.RouteUpdate,
                            is WireFrame.RouteRetraction,
                            is WireFrame.SeqNoRequest,
                            is WireFrame.RouteDigest,
                            is WireFrame.EndToEndHandshakeMessage1,
                            is WireFrame.EndToEndHandshakeMessage2,
                            is WireFrame.EndToEndHandshakeMessage3 -> true
                        }
                    },
                )
            val context = largeTransferAttemptContext()
            val state = adapter.beginOutboundDelivery(context)

            // Act
            val outcome = adapter.attemptOutboundDelivery(state, context)

            // Assert
            val retryImmediately =
                assertIs<
                    MeshEngineOutboundDeliveryAttemptOutcome.RetryImmediately<
                        MeshEngineLargeTransferOutboundDeliveryState
                    >
                >(
                    outcome
                )
            assertEquals(true, retryImmediately.nextState.lastRouteAvailable)
            assertNotNull(retryImmediately.nextState.activeSession)
        }

    @Test
    fun `withDiscoveryPolicy skips discovery suspension when the route is not ready`() =
        runBlocking<Unit> {
            // Arrange
            val discoveryTransitions = mutableListOf<Boolean>()
            val adapter =
                largeTransferOutboundDeliveryAdapter(
                    discoveryTransitions = discoveryTransitions,
                    shouldSuspendDiscovery = { false },
                )
            val context = largeTransferAttemptContext()

            // Act
            adapter.withDiscoveryPolicy(context) { Unit }

            // Assert
            assertEquals(emptyList(), discoveryTransitions)
        }

    @Test
    fun `onDeadlineExpired returns transfer timed out when a route was previously available`() =
        runBlocking<Unit> {
            // Arrange
            val clearedOutboundFrames = mutableListOf<Pair<PeerId, String>>()
            val outboundTransfers = mutableMapOf<String, OutboundTransferSession>()
            val session = testOutboundTransferSession(PeerId("peer-abcdef"))
            outboundTransfers[session.transferId] = session
            val adapter =
                largeTransferOutboundDeliveryAdapter(
                    outboundTransferLifecycleSupport =
                        outboundTransferLifecycleSupport(outboundTransfers = outboundTransfers),
                    clearQueuedOutboundFrames = { peerId, action ->
                        clearedOutboundFrames += peerId to action
                    },
                )
            val context = largeTransferAttemptContext(peerId = session.destinationPeerId)
            val state =
                MeshEngineLargeTransferOutboundDeliveryState(
                    activeSession = session,
                    lastRouteAvailable = true,
                )

            // Act
            val result = adapter.onDeadlineExpired(state, context)

            // Assert
            val notSent = assertIs<SendResult.NotSent>(result)
            assertEquals(SendFailureReason.TRANSFER_TIMED_OUT, notSent.reason)
            assertEquals(
                listOf(session.destinationPeerId to "transfer.clearQueuedFramesOnFailure"),
                clearedOutboundFrames,
            )
            assertEquals(emptyMap(), outboundTransfers)
        }
}

private fun largeTransferOutboundDeliveryAdapter(
    outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport =
        outboundTransferLifecycleSupport(),
    sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean =
        { _, _, _, _ ->
            true
        },
    clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit = { _, _ -> },
    discoveryTransitions: MutableList<Boolean> = mutableListOf(),
    shouldSuspendDiscovery: (PeerId) -> Boolean = { true },
): MeshEngineLargeTransferOutboundDeliveryAdapter {
    val routingSupport = largeTransferRoutingSupport()
    val progressSupport =
        MeshEngineLargeTransferProgressSupport(
            config =
                MeshEngineLargeTransferProgressConfig(
                    ackSettlementTimeout = 100.milliseconds,
                    ackIdleWindow = 25.milliseconds,
                ),
            dependencies =
                MeshEngineLargeTransferProgressDependencies(
                    runtimeGate = LargeTransferAdapterRuntimeGate(),
                    scheduleRetryDiagnostic = { _, _ -> },
                    sendTransferTowardsDestination = sendTransferTowardsDestination,
                ),
            callbacks =
                MeshEngineLargeTransferProgressCallbacks(
                    emitDiagnostic = { _, _, _, _, _, _ -> },
                    routeMetadata = { peerId, metadata ->
                        routingSupport.peerRouteMetadata(peerId = peerId, metadata = metadata)
                    },
                ),
        )
    val terminalSupport =
        MeshEngineLargeTransferTerminalSupport(
            outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
            dependencies =
                MeshEngineLargeTransferTerminalDependencies(
                    clearQueuedOutboundFrames = clearQueuedOutboundFrames,
                    sendTransferTowardsDestination = sendTransferTowardsDestination,
                ),
            callbacks =
                MeshEngineLargeTransferTerminalCallbacks(
                    routeMetadata = { peerId -> routingSupport.peerRouteMetadata(peerId = peerId) },
                    emitDiagnostic = { _, _, _, _, _, _ -> },
                ),
        )
    return MeshEngineLargeTransferOutboundDeliveryAdapter(
        outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
        dependencies =
            MeshEngineLargeTransferOutboundDeliveryAdapterDependencies(
                currentTopologyVersion = { 3L },
                discoverySuspensionSupport =
                    MeshEngineDiscoverySuspensionSupport { suspended ->
                        discoveryTransitions += suspended
                    },
                shouldSuspendDiscovery = shouldSuspendDiscovery,
                progressSupport = progressSupport,
                terminalSupport = terminalSupport,
            ),
    )
}

private fun outboundTransferLifecycleSupport(
    outboundTransfers: MutableMap<String, OutboundTransferSession> = mutableMapOf(),
    scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit = { _, _ -> },
    prepareOutboundTransferSession:
        suspend (PeerId, ByteArray, MeshEngineHardRunToken) -> OutboundTransferPreparation =
        { _, _, _ ->
            OutboundTransferPreparation.Failed(SendResult.NotSent(SendFailureReason.UNREACHABLE))
        },
): MeshEngineOutboundTransferLifecycleSupport {
    return MeshEngineOutboundTransferLifecycleSupport(
        state = MeshEngineOutboundTransferLifecycleState(outboundTransfers = outboundTransfers),
        dependencies =
            MeshEngineOutboundTransferLifecycleDependencies(
                prepareOutboundTransferSession = prepareOutboundTransferSession,
                scheduleRetryDiagnostic = scheduleRetryDiagnostic,
            ),
    )
}

private fun largeTransferAttemptContext(
    peerId: PeerId = PeerId("peer-abcdef")
): MeshEngineOutboundDeliveryAttemptContext {
    return MeshEngineOutboundDeliveryAttemptContext(
        peerId = peerId,
        payload = byteArrayOf(0x01, 0x02),
        priority = DeliveryPriority.HIGH,
        hardRunToken = MeshEngineHardRunToken(epoch = 7L),
        remainingBudget = 250.milliseconds,
    )
}

private fun largeTransferRoutingSupport(): MeshEngineRoutingSupport {
    return MeshEngineRoutingSupport(
        routeCoordinator = RouteCoordinator(PeerId("local-routing-large")),
        runtimeGate = LargeTransferAdapterRuntimeGate(),
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        emitDiagnostic = { _, _, _, _, _, _ -> },
        sendEncryptedWireFrame = { _, _, _, _ -> true },
    )
}

private fun testOutboundTransferSession(destinationPeerId: PeerId): OutboundTransferSession {
    return OutboundTransferSession.fromOwnedPlan(
        route =
            TransferSessionRoute(
                transferId = "transfer-1",
                messageId = "message-1",
                originPeerId = PeerId("origin-abcdef"),
                destinationPeerId = destinationPeerId,
            ),
        chunkPlan =
            TransferChunkPlan(
                chunks = listOf(byteArrayOf(0x01), byteArrayOf(0x02)),
                totalBytes = 2,
                maxChunkPayloadBytes = 1,
            ),
    )
}

private class LargeTransferAdapterRuntimeGate : MeshEngineRuntimeGate {
    private val interruption = CompletableDeferred<MeshEngineRuntimeInterruption>()

    override fun currentState(): MeshLinkState = MeshLinkState.Running

    override fun currentHardRunEpoch(): Long = 7L

    override fun captureHardRunToken(): MeshEngineHardRunToken = MeshEngineHardRunToken(7L)

    override fun isAcceptingNewSends(): Boolean = true

    override fun isHardRunActive(token: MeshEngineHardRunToken): Boolean = token.epoch == 7L

    override suspend fun awaitActive(
        token: MeshEngineHardRunToken
    ): MeshEngineRuntimeAwaitActiveResult {
        return if (isHardRunActive(token)) {
            MeshEngineRuntimeAwaitActiveResult.Active
        } else {
            MeshEngineRuntimeAwaitActiveResult.HardRunEnded
        }
    }

    override suspend fun awaitInterruption(
        token: MeshEngineHardRunToken
    ): MeshEngineRuntimeInterruption {
        check(isHardRunActive(token)) { "awaitInterruption called with inactive hard run token" }
        return interruption.await()
    }
}
