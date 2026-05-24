package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.TransferChunkPlan
import ch.trancee.meshlink.transfer.TransferSessionRoute
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeshEngineOutboundTransferLifecycleSupportTest {
    @Test
    fun `resolveActiveOrPrepareSession registers a prepared session and marks acknowledgements`() =
        runBlocking {
            // Arrange
            val outboundTransfers = mutableMapOf<String, OutboundTransferSession>()
            val preparedSession = outboundTransferSession(PeerId("destination-abcdef"))
            val support =
                outboundTransferLifecycleSupport(
                    outboundTransfers = outboundTransfers,
                    prepareOutboundTransferSession = { _, _, _ ->
                        OutboundTransferPreparation.Ready(preparedSession)
                    },
                )

            // Act
            val resolution =
                support.resolveActiveOrPrepareSession(
                    activeSession = null,
                    peerId = preparedSession.destinationPeerId,
                    payload = byteArrayOf(0x01, 0x02),
                    priority = DeliveryPriority.HIGH,
                    hardRunToken = MeshEngineHardRunToken(epoch = 7L),
                )
            val acknowledged =
                support.markAcknowledged(
                    WireFrame.TransferAck(
                        transferId = preparedSession.transferId,
                        highestContiguousAck = 0,
                        selectiveRanges = byteArrayOf(),
                    )
                )

            // Assert
            val ready = assertIs<MeshEngineOutboundTransferLifecycleResolution.Ready>(resolution)
            assertEquals(preparedSession, ready.session)
            assertEquals(preparedSession, outboundTransfers[preparedSession.transferId])
            assertTrue(acknowledged)
            assertEquals(1, preparedSession.acknowledgedChunkCount())
        }

    @Test
    fun `resolveActiveOrPrepareSession schedules retry when route preparation is pending`() =
        runBlocking {
            // Arrange
            val retryDiagnostics = mutableListOf<Pair<PeerId, DeliveryPriority>>()
            val peerId = PeerId("destination-abcdef")
            val support =
                outboundTransferLifecycleSupport(
                    scheduleRetryDiagnostic = { retryPeerId, priority ->
                        retryDiagnostics += retryPeerId to priority
                    },
                    prepareOutboundTransferSession = { _, _, _ ->
                        OutboundTransferPreparation.PendingRoute
                    },
                )

            // Act
            val resolution =
                support.resolveActiveOrPrepareSession(
                    activeSession = null,
                    peerId = peerId,
                    payload = byteArrayOf(0x01),
                    priority = DeliveryPriority.NORMAL,
                    hardRunToken = MeshEngineHardRunToken(epoch = 8L),
                )

            // Assert
            assertEquals(MeshEngineOutboundTransferLifecycleResolution.AwaitRetry, resolution)
            assertEquals(listOf(peerId to DeliveryPriority.NORMAL), retryDiagnostics)
        }

    @Test
    fun `takeAllSessions snapshots and clears registered outbound sessions`() {
        // Arrange
        val firstSession =
            outboundTransferSession(PeerId("destination-first"), transferId = "transfer-1")
        val secondSession =
            outboundTransferSession(PeerId("destination-second"), transferId = "transfer-2")
        val outboundTransfers =
            linkedMapOf(
                firstSession.transferId to firstSession,
                secondSession.transferId to secondSession,
            )
        val support = outboundTransferLifecycleSupport(outboundTransfers = outboundTransfers)

        // Act
        val sessions = support.takeAllSessions()

        // Assert
        assertEquals(listOf(firstSession, secondSession), sessions)
        assertTrue(outboundTransfers.isEmpty())
    }
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

private fun outboundTransferSession(
    destinationPeerId: PeerId,
    transferId: String = "transfer-1",
): OutboundTransferSession {
    return OutboundTransferSession.fromOwnedPlan(
        route =
            TransferSessionRoute(
                transferId = transferId,
                messageId = "message-$transferId",
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
