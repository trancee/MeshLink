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
        runBlocking {
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
                    inlineSendSupport =
                        inlineSendSupport(
                            runtimeGate = runtimeGate,
                            discoveryTransitions = inlineDiscoveryTransitions,
                            scheduleRetryDiagnostic = { retryPeerId, retryPriority ->
                                inlineRetryDiagnostics += retryPeerId to retryPriority
                            },
                            ensureHopSession = { _, _ -> SessionEstablishmentOutcome.Unreachable },
                        ),
                    largeTransferSupport = dummyLargeTransferSupport(runtimeGate),
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
    fun `sendPayload resets retry attempts after large transfer progress`() = runBlocking {
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
        var transferPreparationCalls = 0
        var transferStartFrames = 0
        val largeTransferSupport =
            MeshEngineLargeTransferSupport(
                config =
                    MeshEngineLargeTransferConfig(
                        ackSettlementTimeout = 100.milliseconds,
                        ackIdleWindow = 25.milliseconds,
                    ),
                state = MeshEngineLargeTransferState(outboundTransfers = outboundTransfers),
                routingSupport = routingSupport(runtimeGate),
                dependencies =
                    MeshEngineLargeTransferDependencies(
                        runtimeGate = runtimeGate,
                        currentTopologyVersion = { 4L },
                        discoverySuspensionSupport =
                            MeshEngineDiscoverySuspensionSupport { suspended ->
                                largeDiscoveryTransitions += suspended
                            },
                        prepareOutboundTransferSession = { destinationPeerId, _, _ ->
                            transferPreparationCalls += 1
                            when (transferPreparationCalls) {
                                1 -> OutboundTransferPreparation.PendingRoute
                                else -> {
                                    if (outboundSession == null) {
                                        outboundSession = outboundTransferSession(destinationPeerId)
                                        val activeSession = checkNotNull(outboundSession)
                                        outboundTransfers[activeSession.transferId] = activeSession
                                    }
                                    OutboundTransferPreparation.Ready(checkNotNull(outboundSession))
                                }
                            }
                        },
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
                                is WireFrame.RouteDigest -> true
                            }
                        },
                        clearQueuedOutboundFrames = { clearedPeerId, action ->
                            clearedOutboundFrames += clearedPeerId to action
                        },
                    ),
                emitDiagnostic = { _, _, _, _, _, _ -> },
            )
        val support =
            outboundDeliverySupport(
                deliveryRetryCallbacks = retryCallbacks,
                inlineSendSupport = dummyInlineSendSupport(runtimeGate),
                largeTransferSupport = largeTransferSupport,
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
                OutboundDeliveryRetryCall(attempt = 0, hardRunEpoch = 9L),
            ),
            retryCallbacks.calls,
        )
        assertEquals(listOf(true, false), largeDiscoveryTransitions)
        assertEquals(2, largeRetryDiagnostics.size)
        assertEquals(listOf(peerId to "transfer.clearQueuedFramesOnFailure"), clearedOutboundFrames)
    }
}

private fun outboundDeliverySupport(
    deliveryRetryCallbacks: OutboundDeliveryRetryCallbacksRecorder,
    inlineSendSupport: MeshEngineInlineSendSupport,
    largeTransferSupport: MeshEngineLargeTransferSupport,
    deliveryRetryDeadline: Duration,
): MeshEngineOutboundDeliverySupport {
    return MeshEngineOutboundDeliverySupport(
        config = MeshEngineOutboundDeliveryConfig(deliveryRetryDeadline = deliveryRetryDeadline),
        dependencies =
            MeshEngineOutboundDeliveryDependencies(
                deliveryRetrySupport = deliveryRetrySupport(deliveryRetryCallbacks)
            ),
        inlineSendSupport = inlineSendSupport,
        largeTransferSupport = largeTransferSupport,
    )
}

private fun inlineSendSupport(
    runtimeGate: MeshEngineRuntimeGate,
    discoveryTransitions: MutableList<Boolean>,
    scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit,
    ensureHopSession: suspend (PeerId, MeshEngineHardRunToken) -> SessionEstablishmentOutcome,
): MeshEngineInlineSendSupport {
    return MeshEngineInlineSendSupport(
        config = MeshEngineInlineConfig(inlineMessagePayloadBytes = INLINE_MESSAGE_PAYLOAD_BYTES),
        routingContext =
            MeshEngineInlineRoutingContext(
                routeCoordinator = RouteCoordinator(PeerId("local-inline-sender")),
                routingSupport = routingSupport(runtimeGate),
            ),
        dependencies =
            MeshEngineInlineDependencies(
                discoverySuspensionSupport =
                    MeshEngineDiscoverySuspensionSupport { suspended ->
                        discoveryTransitions += suspended
                    },
                ensureHopSession = ensureHopSession,
                sendEncryptedDirectWireFrame = { _, _, _, _ -> TransportSendResult.Delivered },
                prepareOutboundInlineMessage = { _, _, _, _ ->
                    error("prepareOutboundInlineMessage should not be called in this test")
                },
                scheduleRetryDiagnostic = scheduleRetryDiagnostic,
                emitHopSessionFailed = { _, _, _, _ ->
                    error("emitHopSessionFailed should not be called in this test")
                },
            ),
        callbacks =
            MeshEngineInlineCallbacks(
                emitDiagnostic = { _, _, _, _, _, _ -> },
                ttlMillisFor = { 1234 },
            ),
    )
}

private fun dummyInlineSendSupport(
    runtimeGate: MeshEngineRuntimeGate
): MeshEngineInlineSendSupport {
    return inlineSendSupport(
        runtimeGate = runtimeGate,
        discoveryTransitions = mutableListOf(),
        scheduleRetryDiagnostic = { _, _ -> },
        ensureHopSession = { _, _ -> SessionEstablishmentOutcome.TrustFailure },
    )
}

private fun dummyLargeTransferSupport(
    runtimeGate: MeshEngineRuntimeGate
): MeshEngineLargeTransferSupport {
    return MeshEngineLargeTransferSupport(
        config =
            MeshEngineLargeTransferConfig(
                ackSettlementTimeout = 100.milliseconds,
                ackIdleWindow = 25.milliseconds,
            ),
        state = MeshEngineLargeTransferState(outboundTransfers = mutableMapOf()),
        routingSupport = routingSupport(runtimeGate),
        dependencies =
            MeshEngineLargeTransferDependencies(
                runtimeGate = runtimeGate,
                currentTopologyVersion = { 0L },
                discoverySuspensionSupport = MeshEngineDiscoverySuspensionSupport { _ -> },
                prepareOutboundTransferSession = { _, _, _ ->
                    OutboundTransferPreparation.Failed(
                        SendResult.NotSent(SendFailureReason.UNREACHABLE)
                    )
                },
                scheduleRetryDiagnostic = { _, _ -> },
                sendTransferTowardsDestination = { _, _, _, _ -> true },
                clearQueuedOutboundFrames = { _, _ -> },
            ),
        emitDiagnostic = { _, _, _, _, _, _ -> },
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
