package ch.trancee.meshlink.transfer

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.MeshEngineRuntimeSurface
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TransferSessionTest {
    @Test
    fun `markAcknowledged counts newly acknowledged chunks only once`() {
        // Arrange
        val session = newOutboundSession(chunkCount = 4)
        val ackFrame =
            WireFrame.TransferAck(
                transferId = session.transferId,
                highestContiguousAck = 1,
                selectiveRanges = byteArrayOf(),
            )

        // Act
        session.markAcknowledged(ackFrame)
        session.markAcknowledged(ackFrame)

        // Assert
        assertEquals(2, session.acknowledgedChunkCount())
        assertEquals(listOf(2, 3), session.missingChunkIndices())
    }

    @Test
    fun `acceptChunk batches acknowledgements until transfer completion`() {
        // Arrange
        val session = newInboundSession(chunkCount = 16)

        // Act
        repeat(15) { chunkIndex ->
            val acceptance =
                session.acceptChunk(
                    WireFrame.TransferChunk(
                        transferId = session.transferId,
                        chunkIndex = chunkIndex,
                        payload = byteArrayOf(chunkIndex.toByte()),
                    )
                )

            // Assert
            assertTrue(acceptance.accepted, "Chunk ${chunkIndex + 1} should be accepted")
            assertFalse(
                acceptance.shouldAcknowledge,
                "Chunk ${chunkIndex + 1} should stay batched until completion",
            )
        }
        val finalChunkAcceptance =
            session.acceptChunk(
                WireFrame.TransferChunk(
                    transferId = session.transferId,
                    chunkIndex = 15,
                    payload = byteArrayOf(15),
                )
            )

        // Assert
        assertTrue(finalChunkAcceptance.accepted)
        assertTrue(finalChunkAcceptance.complete)
        assertTrue(finalChunkAcceptance.shouldAcknowledge)
    }

    @Test
    fun `awaitAcknowledgementSettlement waits for an acknowledgement burst before returning`() =
        runBlocking {
            // Arrange
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            val session = newOutboundSession(chunkCount = 4)
            val acknowledgementProducer = launch {
                delay(10)
                session.markAcknowledged(
                    WireFrame.TransferAck(
                        transferId = session.transferId,
                        highestContiguousAck = 0,
                        selectiveRanges = byteArrayOf(),
                    )
                )
                delay(10)
                session.markAcknowledged(
                    WireFrame.TransferAck(
                        transferId = session.transferId,
                        highestContiguousAck = 3,
                        selectiveRanges = byteArrayOf(),
                    )
                )
            }

            // Act
            val settlement =
                session.awaitAcknowledgementSettlement(
                    maximumWait = 300.milliseconds,
                    idleWindow = 50.milliseconds,
                    runtimeGate = runtimeSurface.runtimeGate,
                    hardRunToken = hardRunToken,
                )
            acknowledgementProducer.join()

            // Assert
            val completed = assertIs<AcknowledgementSettlementResult.Completed>(settlement)
            assertEquals(4, completed.acknowledgedChunkCount)
            assertTrue(session.isComplete())
        }

    @Test
    fun `awaitAcknowledgementSettlement stops at the idle window when the next acknowledgement arrives late`() =
        runBlocking {
            // Arrange
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            val session = newOutboundSession(chunkCount = 4)
            val acknowledgementProducer = launch {
                delay(40)
                session.markAcknowledged(
                    WireFrame.TransferAck(
                        transferId = session.transferId,
                        highestContiguousAck = 0,
                        selectiveRanges = byteArrayOf(),
                    )
                )
            }

            // Act
            val settlement =
                session.awaitAcknowledgementSettlement(
                    maximumWait = 200.milliseconds,
                    idleWindow = 20.milliseconds,
                    runtimeGate = runtimeSurface.runtimeGate,
                    hardRunToken = hardRunToken,
                )
            acknowledgementProducer.join()

            // Assert
            val completed = assertIs<AcknowledgementSettlementResult.Completed>(settlement)
            assertEquals(0, completed.acknowledgedChunkCount)
            assertEquals(1, session.acknowledgedChunkCount())
            assertFalse(session.isComplete())
        }

    @Test
    fun `acceptChunk requests an acknowledgement after sixteen new chunks`() {
        // Arrange
        val session = newInboundSession(chunkCount = 128)

        // Act
        repeat(15) { chunkIndex ->
            val acceptance =
                session.acceptChunk(
                    WireFrame.TransferChunk(
                        transferId = session.transferId,
                        chunkIndex = chunkIndex,
                        payload = byteArrayOf(chunkIndex.toByte()),
                    )
                )

            // Assert
            assertTrue(acceptance.accepted)
            assertFalse(
                acceptance.shouldAcknowledge,
                "Chunk ${chunkIndex + 1} should stay batched before the 16-chunk threshold",
            )
        }
        val thresholdAcceptance =
            session.acceptChunk(
                WireFrame.TransferChunk(
                    transferId = session.transferId,
                    chunkIndex = 15,
                    payload = byteArrayOf(15),
                )
            )

        // Assert
        assertTrue(thresholdAcceptance.accepted)
        assertEquals(16, thresholdAcceptance.receivedChunkCount)
        assertEquals(16, thresholdAcceptance.newlyReceivedChunksSinceLastAck)
        assertEquals(15, thresholdAcceptance.highestContiguousAck)
        assertTrue(thresholdAcceptance.shouldAcknowledge)
    }

    @Test
    fun `acceptChunk requests an acknowledgement immediately when a duplicate chunk arrives`() {
        // Arrange
        val session = newInboundSession(chunkCount = 4)
        val firstChunk =
            WireFrame.TransferChunk(
                transferId = session.transferId,
                chunkIndex = 0,
                payload = byteArrayOf(0),
            )

        // Act
        val firstDelivery = session.acceptChunk(firstChunk)
        val duplicateDelivery = session.acceptChunk(firstChunk)

        // Assert
        assertTrue(firstDelivery.accepted)
        assertFalse(firstDelivery.shouldAcknowledge)
        assertTrue(duplicateDelivery.accepted)
        assertTrue(duplicateDelivery.duplicateChunk)
        assertTrue(duplicateDelivery.shouldAcknowledge)
        assertEquals(0, session.highestContiguousAck())
    }

    @Test
    fun `acceptChunk advances the contiguous acknowledgement when a gap closes`() {
        // Arrange
        val session = newInboundSession(chunkCount = 4)

        // Act
        val outOfOrderDelivery =
            session.acceptChunk(
                WireFrame.TransferChunk(
                    transferId = session.transferId,
                    chunkIndex = 2,
                    payload = byteArrayOf(2),
                )
            )
        val firstContiguousDelivery =
            session.acceptChunk(
                WireFrame.TransferChunk(
                    transferId = session.transferId,
                    chunkIndex = 0,
                    payload = byteArrayOf(0),
                )
            )
        val gapClosingDelivery =
            session.acceptChunk(
                WireFrame.TransferChunk(
                    transferId = session.transferId,
                    chunkIndex = 1,
                    payload = byteArrayOf(1),
                )
            )

        // Assert
        assertEquals(-1, outOfOrderDelivery.highestContiguousAck)
        assertEquals(0, firstContiguousDelivery.highestContiguousAck)
        assertEquals(2, gapClosingDelivery.highestContiguousAck)
        assertEquals(2, session.highestContiguousAck())
    }

    private fun newOutboundSession(chunkCount: Int): OutboundTransferSession {
        val chunks = List(chunkCount) { index -> byteArrayOf(index.toByte()) }
        return OutboundTransferSession(
            route = transferRoute(destinationPeerId = PeerId("destination")),
            chunkPlan =
                TransferChunkPlan(
                    chunks = chunks,
                    totalBytes = chunks.sumOf { chunk -> chunk.size },
                    maxChunkPayloadBytes = 1,
                ),
        )
    }

    private fun newInboundSession(chunkCount: Int): InboundTransferSession {
        return InboundTransferSession(
            startDescriptor =
                TransferStartDescriptor(
                    route = transferRoute(destinationPeerId = PeerId("destination")),
                    totalBytes = chunkCount,
                    totalChunks = chunkCount,
                    maxChunkPayloadBytes = 1,
                ),
            upstreamPeerId = PeerId("upstream"),
            hardRunToken = MeshEngineRuntimeSurface().beginHardRun(),
        )
    }

    private fun transferRoute(destinationPeerId: PeerId): TransferSessionRoute {
        return TransferSessionRoute(
            transferId = "transfer-1",
            messageId = "message-1",
            originPeerId = PeerId("origin"),
            destinationPeerId = destinationPeerId,
        )
    }
}
