package ch.trancee.meshlink.transfer

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `acceptChunk batches acknowledgements until enough new chunks arrive`() {
        // Arrange
        val session = newInboundSession(chunkCount = 16)

        // Act
        repeat(15) { chunkIndex ->
            val shouldAcknowledge =
                session.acceptChunk(
                    WireFrame.TransferChunk(
                        transferId = session.transferId,
                        chunkIndex = chunkIndex,
                        payload = byteArrayOf(chunkIndex.toByte()),
                    )
                )

            // Assert
            assertFalse(shouldAcknowledge, "Chunk ${chunkIndex + 1} should stay batched")
        }
        val finalChunkShouldAcknowledge =
            session.acceptChunk(
                WireFrame.TransferChunk(
                    transferId = session.transferId,
                    chunkIndex = 15,
                    payload = byteArrayOf(15),
                )
            )

        // Assert
        assertTrue(finalChunkShouldAcknowledge)
    }

    @Test
    fun `awaitAcknowledgementSettlement waits for an acknowledgement burst before returning`() =
        runBlocking {
            // Arrange
            val session = newOutboundSession(chunkCount = 4)
            val acknowledgementProducer = launch {
                delay(5)
                session.markAcknowledged(
                    WireFrame.TransferAck(
                        transferId = session.transferId,
                        highestContiguousAck = 0,
                        selectiveRanges = byteArrayOf(),
                    )
                )
                delay(5)
                session.markAcknowledged(
                    WireFrame.TransferAck(
                        transferId = session.transferId,
                        highestContiguousAck = 3,
                        selectiveRanges = byteArrayOf(),
                    )
                )
            }

            // Act
            val acknowledgedChunkCount =
                session.awaitAcknowledgementSettlement(
                    maximumWait = 200.milliseconds,
                    idleWindow = 20.milliseconds,
                )
            acknowledgementProducer.join()

            // Assert
            assertEquals(4, acknowledgedChunkCount)
            assertTrue(session.isComplete())
        }

    @Test
    fun `awaitAcknowledgementSettlement stops at the idle window when the next acknowledgement arrives late`() =
        runBlocking {
            // Arrange
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
            val settledAcknowledgedChunkCount =
                session.awaitAcknowledgementSettlement(
                    maximumWait = 200.milliseconds,
                    idleWindow = 20.milliseconds,
                )
            acknowledgementProducer.join()

            // Assert
            assertEquals(0, settledAcknowledgedChunkCount)
            assertEquals(1, session.acknowledgedChunkCount())
            assertFalse(session.isComplete())
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
        val firstDeliveryShouldAcknowledge = session.acceptChunk(firstChunk)
        val duplicateDeliveryShouldAcknowledge = session.acceptChunk(firstChunk)

        // Assert
        assertFalse(firstDeliveryShouldAcknowledge)
        assertTrue(duplicateDeliveryShouldAcknowledge)
        assertEquals(0, session.highestContiguousAck())
    }

    private fun newOutboundSession(chunkCount: Int): OutboundTransferSession {
        val chunks = List(chunkCount) { index -> byteArrayOf(index.toByte()) }
        return OutboundTransferSession(
            transferId = "transfer-1",
            messageId = "message-1",
            originPeerId = PeerId("origin"),
            destinationPeerId = PeerId("destination"),
            chunks = chunks,
            totalBytes = chunks.sumOf { chunk -> chunk.size },
            maxChunkPayloadBytes = 1,
        )
    }

    private fun newInboundSession(chunkCount: Int): InboundTransferSession {
        return InboundTransferSession(
            transferId = "transfer-1",
            messageId = "message-1",
            originPeerId = PeerId("origin"),
            destinationPeerId = PeerId("destination"),
            upstreamPeerId = PeerId("upstream"),
            totalBytes = chunkCount,
            totalChunks = chunkCount,
            maxChunkPayloadBytes = 1,
        )
    }
}
