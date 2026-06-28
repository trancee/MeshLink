package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.TransferChunkPlan
import ch.trancee.meshlink.transfer.TransferSessionRoute
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MeshEngineLargeTransferProgressSupportTest {
    @Test
    fun `advance schedules retry when no route is available`() =
        runBlocking<Unit> {
            // Arrange
            val retryDiagnostics = mutableListOf<Pair<PeerId, DeliveryPriority>>()
            val session = outboundTransferSession(PeerId("destination-abcdef"))
            val support =
                largeTransferProgressSupport(
                    scheduleRetryDiagnostic = { peerId, priority ->
                        retryDiagnostics += peerId to priority
                    },
                    sendTransferTowardsDestination = { _, _, _, _ -> false },
                )

            // Act
            val result =
                support.advance(
                    session = session,
                    priority = DeliveryPriority.HIGH,
                    remainingBudget = 250.milliseconds,
                    hardRunToken = MeshEngineHardRunToken(epoch = 7L),
                )

            // Assert
            assertEquals(false, result.lastRouteAvailable)
            assertEquals(false, result.transferProgressObserved)
            assertEquals(false, result.sessionIsComplete)
            assertEquals(
                listOf(session.destinationPeerId to DeliveryPriority.HIGH),
                retryDiagnostics,
            )
        }

    @Test
    fun `advance reports progress when acknowledgement settlement observes new acknowledgements`() =
        runBlocking<Unit> {
            // Arrange
            val session = outboundTransferSession(PeerId("destination-abcdef"))
            val support =
                largeTransferProgressSupport(
                    sendTransferTowardsDestination = { _, frame, _, _ ->
                        when (frame) {
                            is WireFrame.TransferStart -> true
                            is WireFrame.TransferChunk -> {
                                if (frame.chunkIndex == 0) {
                                    launch {
                                        delay(10)
                                        session.markAcknowledged(
                                            WireFrame.TransferAck(
                                                transferId = session.transferId,
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
                            is WireFrame.Ihu,
                            is WireFrame.RouteUpdate,
                            is WireFrame.RouteRetraction,
                            is WireFrame.SeqNoRequest,
                            is WireFrame.RouteDigest -> true
                        }
                    }
                )

            // Act
            val result =
                support.advance(
                    session = session,
                    priority = DeliveryPriority.NORMAL,
                    remainingBudget = 250.milliseconds,
                    hardRunToken = MeshEngineHardRunToken(epoch = 7L),
                )

            // Assert
            assertEquals(true, result.lastRouteAvailable)
            assertEquals(true, result.transferProgressObserved)
            assertEquals(false, result.sessionIsComplete)
            assertTrue(session.acknowledgedChunkCount() > 0)
        }
}

private fun largeTransferProgressSupport(
    scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit = { _, _ -> },
    sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
): MeshEngineLargeTransferProgressSupport {
    val routingSupport =
        MeshEngineRoutingSupport(
            routeCoordinator = RouteCoordinator(PeerId("local-routing-large-progress")),
            runtimeGate = LargeTransferProgressRuntimeGate(),
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            emitDiagnostic = { _, _, _, _, _, _ -> },
            sendEncryptedWireFrame = { _, _, _, _ -> true },
        )
    return MeshEngineLargeTransferProgressSupport(
        config =
            MeshEngineLargeTransferProgressConfig(
                ackSettlementTimeout = 100.milliseconds,
                ackIdleWindow = 25.milliseconds,
            ),
        dependencies =
            MeshEngineLargeTransferProgressDependencies(
                runtimeGate = LargeTransferProgressRuntimeGate(),
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

private class LargeTransferProgressRuntimeGate : MeshEngineRuntimeGate {
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
