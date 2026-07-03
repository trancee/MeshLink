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
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MeshEngineOutboundDeliverySupportTest {
    @Test
    fun `sendPayload returns the inline deadline result when the inline leaf requests retry`() =
        runBlocking<Unit> {
            // Arrange
            val peerId = PeerId("peer-abcdef")
            val runtimeGate = TestRuntimeGate(activeHardRunEpoch = 7L)
            val retryCallbacks = OutboundDeliveryRetryCallbacksRecorder { _, _ ->
                RetryWakeup.DeadlineExpired(topologyVersion = 4L)
            }
            val inlineRetryDiagnostics = mutableListOf<Pair<PeerId, DeliveryPriority>>()
            val inlineDiscoveryTransitions = mutableListOf<Boolean>()
            val support =
                outboundDeliverySupport(
                    deliveryRetryCallbacks = retryCallbacks,
                    inlineOutboundDeliveryAdapter =
                        inlineOutboundDeliveryAdapter(
                            runtimeGate = runtimeGate,
                            discoveryTransitions = inlineDiscoveryTransitions,
                            scheduleRetryDiagnostic = { retryPeerId, retryPriority ->
                                inlineRetryDiagnostics += retryPeerId to retryPriority
                            },
                            ensureHopSession = { _, _ -> SessionEstablishmentOutcome.Unreachable },
                        ),
                    largeTransferOutboundDeliveryAdapter =
                        dummyLargeTransferOutboundDeliveryAdapter(runtimeGate),
                    deliveryRetryDeadline = 250.milliseconds,
                )

            // Act
            val result =
                support.sendPayload(
                    mode = MeshEngineOutboundDeliveryMode.INLINE,
                    peerId = peerId,
                    payload = ByteArray(32) { 0x01 },
                    priority = DeliveryPriority.NORMAL,
                    hardRunToken = MeshEngineHardRunToken(epoch = 7L),
                )

            // Assert
            val notSent = assertIs<SendResult.NotSent>(result)
            assertEquals(SendFailureReason.UNREACHABLE, notSent.reason)
            assertEquals(
                listOf(OutboundDeliveryRetryCall(attempt = 0, hardRunEpoch = 7L)),
                retryCallbacks.calls,
            )
            assertEquals(listOf(peerId to DeliveryPriority.NORMAL), inlineRetryDiagnostics)
            assertTrue(inlineDiscoveryTransitions.isEmpty())
        }

    @Test
    fun `sendPayload does not suspend discovery while a large transfer is still waiting for its first route`() =
        runBlocking<Unit> {
            // Arrange
            val peerId = PeerId("peer-route-wait")
            val runtimeGate = TestRuntimeGate(activeHardRunEpoch = 11L)
            val retryCallbacks = OutboundDeliveryRetryCallbacksRecorder { _, _ ->
                RetryWakeup.DeadlineExpired(topologyVersion = 0L)
            }
            val largeDiscoveryTransitions = mutableListOf<Boolean>()
            val support =
                outboundDeliverySupport(
                    deliveryRetryCallbacks = retryCallbacks,
                    inlineOutboundDeliveryAdapter = dummyInlineOutboundDeliveryAdapter(runtimeGate),
                    largeTransferOutboundDeliveryAdapter =
                        largeTransferOutboundDeliveryAdapter(
                            runtimeGate = runtimeGate,
                            outboundTransferLifecycleSupport =
                                outboundTransferLifecycleSupport(
                                    scheduleRetryDiagnostic = { _, _ -> },
                                    prepareOutboundTransferSession = { _, _, _ ->
                                        OutboundTransferPreparation.PendingRoute
                                    },
                                ),
                            discoveryTransitions = largeDiscoveryTransitions,
                            shouldSuspendDiscovery = { false },
                            scheduleRetryDiagnostic = { _, _ -> },
                            sendTransferTowardsDestination = { _, _, _, _ -> true },
                            clearQueuedOutboundFrames = { _, _ -> },
                        ),
                    deliveryRetryDeadline = 100.milliseconds,
                )

            // Act
            val result =
                support.sendPayload(
                    mode = MeshEngineOutboundDeliveryMode.LARGE_TRANSFER,
                    peerId = peerId,
                    payload = byteArrayOf(0x01, 0x02),
                    priority = DeliveryPriority.HIGH,
                    hardRunToken = MeshEngineHardRunToken(epoch = 11L),
                )

            // Assert
            val notSent = assertIs<SendResult.NotSent>(result)
            assertEquals(SendFailureReason.UNREACHABLE, notSent.reason)
            assertEquals(emptyList(), largeDiscoveryTransitions)
        }

    @Test
    fun `sendPayload resets retry attempts after large transfer progress`() =
        runBlocking<Unit> {
            // Arrange
            val peerId = PeerId("peer-abcdef")
            val runtimeGate = TestRuntimeGate(activeHardRunEpoch = 9L)
            val retrySequenceState = RetrySequenceState(firstWakeupPending = true)
            val retryCallbacks = OutboundDeliveryRetryCallbacksRecorder { attempt, _ ->
                if (attempt == 0 && retrySequenceState.firstWakeupPending) {
                    retrySequenceState.firstWakeupPending = false
                    RetryWakeup.TimerElapsed(topologyVersion = 5L)
                } else {
                    RetryWakeup.DeadlineExpired(topologyVersion = 5L)
                }
            }
            val largeDiscoveryTransitions = mutableListOf<Boolean>()
            val largeRetryDiagnostics = mutableListOf<Pair<PeerId, DeliveryPriority>>()
            val clearedOutboundFrames = mutableListOf<Pair<PeerId, String>>()
            val outboundTransfers = mutableMapOf<String, OutboundTransferSession>()
            var outboundSession: OutboundTransferSession? = null
            var transferStartFrames = 0
            val support =
                outboundDeliverySupport(
                    deliveryRetryCallbacks = retryCallbacks,
                    inlineOutboundDeliveryAdapter = dummyInlineOutboundDeliveryAdapter(runtimeGate),
                    largeTransferOutboundDeliveryAdapter =
                        largeTransferOutboundDeliveryAdapter(
                            runtimeGate = runtimeGate,
                            outboundTransferLifecycleSupport =
                                outboundTransferLifecycleSupport(
                                    outboundTransfers = outboundTransfers,
                                    scheduleRetryDiagnostic = { retryPeerId, retryPriority ->
                                        largeRetryDiagnostics += retryPeerId to retryPriority
                                    },
                                    prepareOutboundTransferSession = { destinationPeerId, _, _ ->
                                        if (outboundSession == null) {
                                            outboundSession =
                                                outboundTransferSession(destinationPeerId)
                                        }
                                        OutboundTransferPreparation.Ready(
                                            checkNotNull(outboundSession)
                                        )
                                    },
                                ),
                            discoveryTransitions = largeDiscoveryTransitions,
                            scheduleRetryDiagnostic = { retryPeerId, retryPriority ->
                                largeRetryDiagnostics += retryPeerId to retryPriority
                            },
                            sendTransferTowardsDestination = { _, frame, _, _ ->
                                when (frame) {
                                    is WireFrame.TransferStart -> {
                                        transferStartFrames += 1
                                        transferStartFrames == 1
                                    }
                                    is WireFrame.TransferChunk -> {
                                        if (transferStartFrames == 1) {
                                            if (frame.chunkIndex == 0) {
                                                launch {
                                                    delay(10)
                                                    val activeSession =
                                                        checkNotNull(outboundSession)
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
                                        } else {
                                            false
                                        }
                                    }
                                    is WireFrame.TransferAck,
                                    is WireFrame.TransferComplete,
                                    is WireFrame.TransferAbort,
                                    is WireFrame.Message,
                                    is WireFrame.Hello,
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
                            clearQueuedOutboundFrames = { clearedPeerId, action ->
                                clearedOutboundFrames += clearedPeerId to action
                            },
                        ),
                    deliveryRetryDeadline = 300.milliseconds,
                )

            // Act
            val result =
                support.sendPayload(
                    mode = MeshEngineOutboundDeliveryMode.LARGE_TRANSFER,
                    peerId = peerId,
                    payload = byteArrayOf(0x01, 0x02),
                    priority = DeliveryPriority.HIGH,
                    hardRunToken = MeshEngineHardRunToken(epoch = 9L),
                )

            // Assert
            val notSent = assertIs<SendResult.NotSent>(result)
            assertEquals(SendFailureReason.UNREACHABLE, notSent.reason)
            assertEquals(
                listOf(
                    OutboundDeliveryRetryCall(attempt = 0, hardRunEpoch = 9L),
                    OutboundDeliveryRetryCall(attempt = 1, hardRunEpoch = 9L),
                ),
                retryCallbacks.calls,
            )
            assertEquals(listOf(true, false), largeDiscoveryTransitions)
            assertEquals(2, largeRetryDiagnostics.size)
            assertEquals(
                listOf(peerId to "transfer.clearQueuedFramesOnFailure"),
                clearedOutboundFrames,
            )
        }
}

private fun outboundDeliverySupport(
    deliveryRetryCallbacks: OutboundDeliveryRetryCallbacksRecorder,
    inlineOutboundDeliveryAdapter: MeshEngineInlineOutboundDeliveryAdapter,
    largeTransferOutboundDeliveryAdapter: MeshEngineLargeTransferOutboundDeliveryAdapter,
    deliveryRetryDeadline: Duration,
): MeshEngineOutboundDeliverySupport {
    return MeshEngineOutboundDeliverySupport(
        outboundDeliveryDriver =
            MeshEngineOutboundDeliveryDriver(
                deliveryRetryDeadline = deliveryRetryDeadline,
                deliveryRetrySupport = deliveryRetrySupport(deliveryRetryCallbacks),
            ),
        inlineOutboundDeliveryAdapter = inlineOutboundDeliveryAdapter,
        largeTransferOutboundDeliveryAdapter = largeTransferOutboundDeliveryAdapter,
    )
}

private fun inlineOutboundDeliveryAdapter(
    runtimeGate: MeshEngineRuntimeGate,
    discoveryTransitions: MutableList<Boolean>,
    scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit,
    ensureHopSession: suspend (PeerId, MeshEngineHardRunToken) -> SessionEstablishmentOutcome,
    prepareOutboundInlineMessage:
        suspend (
            PeerId, ByteArray, DeliveryPriority, Int,
        ) -> MeshEngineOutboundInlineMessagePreparation =
        { _, _, _, _ ->
            error("prepareOutboundInlineMessage should not be called in this test")
        },
): MeshEngineInlineOutboundDeliveryAdapter {
    return MeshEngineInlineOutboundDeliveryAdapter(
        config =
            MeshEngineInlineOutboundDeliveryAdapterConfig(
                inlineMessagePayloadBytes = INLINE_MESSAGE_PAYLOAD_BYTES
            ),
        dependencies =
            MeshEngineInlineOutboundDeliveryAdapterDependencies(
                discoverySuspensionSupport =
                    MeshEngineDiscoverySuspensionSupport { suspended ->
                        discoveryTransitions += suspended
                    },
                prepareOutboundInlineMessage = prepareOutboundInlineMessage,
                dispatchSupport =
                    MeshEngineInlineDispatchSupport(
                        routingContext =
                            MeshEngineInlineDispatchRoutingContext(
                                routeCoordinator = RouteCoordinator(PeerId("local-inline-sender")),
                                routingSupport = routingSupport(runtimeGate),
                            ),
                        dependencies =
                            MeshEngineInlineDispatchDependencies(
                                ensureHopSession = ensureHopSession,
                                sendEncryptedDirectWireFrame = { _, _, _, _ ->
                                    TransportSendResult.Delivered
                                },
                                scheduleRetryDiagnostic = scheduleRetryDiagnostic,
                                emitHopSessionFailed = { _, _, _, _ ->
                                    error("emitHopSessionFailed should not be called in this test")
                                },
                            ),
                        callbacks =
                            MeshEngineInlineDispatchCallbacks(
                                emitDiagnostic = { _, _, _, _, _, _ -> }
                            ),
                    ),
            ),
        callbacks =
            MeshEngineInlineOutboundDeliveryAdapterCallbacks(
                emitDiagnostic = { _, _, _, _, _, _ -> },
                ttlMillisFor = { 1234 },
            ),
    )
}

private fun dummyInlineOutboundDeliveryAdapter(
    runtimeGate: MeshEngineRuntimeGate
): MeshEngineInlineOutboundDeliveryAdapter {
    return inlineOutboundDeliveryAdapter(
        runtimeGate = runtimeGate,
        discoveryTransitions = mutableListOf(),
        scheduleRetryDiagnostic = { _, _ -> },
        ensureHopSession = { _, _ -> SessionEstablishmentOutcome.TrustFailure },
    )
}

private fun dummyLargeTransferOutboundDeliveryAdapter(
    runtimeGate: MeshEngineRuntimeGate
): MeshEngineLargeTransferOutboundDeliveryAdapter {
    return largeTransferOutboundDeliveryAdapter(
        runtimeGate = runtimeGate,
        outboundTransferLifecycleSupport = outboundTransferLifecycleSupport(),
        discoveryTransitions = mutableListOf(),
        scheduleRetryDiagnostic = { _, _ -> },
        sendTransferTowardsDestination = { _, _, _, _ -> true },
        clearQueuedOutboundFrames = { _, _ -> },
    )
}

private fun largeTransferOutboundDeliveryAdapter(
    runtimeGate: MeshEngineRuntimeGate,
    outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport,
    discoveryTransitions: MutableList<Boolean>,
    shouldSuspendDiscovery: (PeerId) -> Boolean = { true },
    scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit,
    sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit,
): MeshEngineLargeTransferOutboundDeliveryAdapter {
    val routingSupport = routingSupport(runtimeGate)
    val progressSupport =
        MeshEngineLargeTransferProgressSupport(
            config =
                MeshEngineLargeTransferProgressConfig(
                    ackSettlementTimeout = 100.milliseconds,
                    ackIdleWindow = 25.milliseconds,
                ),
            dependencies =
                MeshEngineLargeTransferProgressDependencies(
                    runtimeGate = runtimeGate,
                    scheduleRetryDiagnostic = scheduleRetryDiagnostic,
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
                currentTopologyVersion = { 0L },
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

private fun deliveryRetrySupport(
    callbacks: OutboundDeliveryRetryCallbacksRecorder
): MeshEngineDeliveryRetrySupport {
    return MeshEngineDeliveryRetrySupport(
        callbacks =
            MeshEngineDeliveryRetryCallbacks(
                awaitRetry = { attempt, _, _, hardRunToken ->
                    callbacks.calls +=
                        OutboundDeliveryRetryCall(
                            attempt = attempt,
                            hardRunEpoch = hardRunToken.epoch,
                        )
                    callbacks.awaitRetry(attempt, hardRunToken)
                },
                routeMetadata = { _, metadata -> metadata },
                emitDiagnostic = { _, _, _, _, _, _ -> },
            )
    )
}

private fun routingSupport(runtimeGate: MeshEngineRuntimeGate): MeshEngineRoutingSupport {
    return MeshEngineRoutingSupport(
        routeCoordinator = RouteCoordinator(PeerId("local-routing-peer")),
        runtimeGate = runtimeGate,
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        emitDiagnostic = { _, _, _, _, _, _ -> },
        sendEncryptedWireFrame = { _, _, _, _ -> true },
    )
}

private fun outboundTransferSession(destinationPeerId: PeerId): OutboundTransferSession {
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

private data class OutboundDeliveryRetryCall(val attempt: Int, val hardRunEpoch: Long)

private data class RetrySequenceState(var firstWakeupPending: Boolean)

private class OutboundDeliveryRetryCallbacksRecorder(
    private val wakeup: suspend (Int, MeshEngineHardRunToken) -> RetryWakeup
) {
    val calls: MutableList<OutboundDeliveryRetryCall> = mutableListOf()

    suspend fun awaitRetry(attempt: Int, hardRunToken: MeshEngineHardRunToken): RetryWakeup {
        return wakeup(attempt, hardRunToken)
    }
}

private class TestRuntimeGate(private val activeHardRunEpoch: Long) : MeshEngineRuntimeGate {
    private val interruption = CompletableDeferred<MeshEngineRuntimeInterruption>()

    override fun currentState(): MeshLinkState = MeshLinkState.Running

    override fun currentHardRunEpoch(): Long = activeHardRunEpoch

    override fun captureHardRunToken(): MeshEngineHardRunToken {
        return MeshEngineHardRunToken(activeHardRunEpoch)
    }

    override fun isAcceptingNewSends(): Boolean = true

    override fun isHardRunActive(token: MeshEngineHardRunToken): Boolean {
        return token.epoch == activeHardRunEpoch
    }

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

private const val INLINE_MESSAGE_PAYLOAD_BYTES: Int = 1_024
