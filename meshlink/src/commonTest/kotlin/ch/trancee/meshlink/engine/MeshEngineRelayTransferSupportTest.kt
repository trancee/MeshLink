package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transfer.RelayTransferSession
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeshEngineRelayTransferSupportTest {
    @Test
    fun `handleTransferStart creates a relay session and forwards the start frame`() = runBlocking {
        // Arrange
        val relayTransfers = mutableMapOf<String, RelayTransferSession>()
        val callbacks = RecordingRelayTransferCallbacks()
        val support = relayTransferSupport(relayTransfers = relayTransfers, callbacks = callbacks)
        val sourcePeerId = PeerId("upstream")
        val hardRunToken = MeshEngineHardRunToken(epoch = 4)
        val frame =
            WireFrame.TransferStart(
                route =
                    WireFrame.TransferStartRoute(
                        transferId = "transfer-1",
                        messageId = "message-1",
                        originPeerId = PeerId("origin"),
                        destinationPeerId = PeerId("destination"),
                    ),
                sizing =
                    WireFrame.TransferStartSizing(
                        totalBytes = 2,
                        totalChunks = 2,
                        maxChunkPayloadBytes = 1,
                    ),
            )

        // Act
        support.handleTransferStart(
            peerId = sourcePeerId,
            frame = frame,
            hardRunToken = hardRunToken,
        )

        // Assert
        val relaySession = relayTransfers.getValue(frame.transferId)
        assertEquals(sourcePeerId, relaySession.upstreamPeerId)
        assertEquals(frame.destinationPeerId, relaySession.destinationPeerId)
        assertEquals(
            listOf(
                RecordedRelayFrame(
                    peerId = frame.destinationPeerId,
                    action = "transfer.forward.start",
                    transferId = frame.transferId,
                    hardRunEpoch = hardRunToken.epoch,
                )
            ),
            callbacks.routedFrames,
        )
    }

    @Test
    fun `handleTransferStart refreshes the upstream peer for an existing relay session`() =
        runBlocking {
            // Arrange
            val existingSession =
                RelayTransferSession(
                    transferId = "transfer-1",
                    messageId = "message-1",
                    originPeerId = PeerId("origin"),
                    destinationPeerId = PeerId("destination"),
                    upstreamPeerId = PeerId("old-upstream"),
                    hardRunToken = MeshEngineHardRunToken(epoch = 7),
                )
            val relayTransfers = mutableMapOf(existingSession.transferId to existingSession)
            val callbacks = RecordingRelayTransferCallbacks()
            val support =
                relayTransferSupport(relayTransfers = relayTransfers, callbacks = callbacks)
            val frame =
                WireFrame.TransferStart(
                    route =
                        WireFrame.TransferStartRoute(
                            transferId = existingSession.transferId,
                            messageId = existingSession.messageId,
                            originPeerId = existingSession.originPeerId,
                            destinationPeerId = existingSession.destinationPeerId,
                        ),
                    sizing =
                        WireFrame.TransferStartSizing(
                            totalBytes = 2,
                            totalChunks = 2,
                            maxChunkPayloadBytes = 1,
                        ),
                )
            val refreshedUpstream = PeerId("new-upstream")

            // Act
            support.handleTransferStart(
                peerId = refreshedUpstream,
                frame = frame,
                hardRunToken = MeshEngineHardRunToken(epoch = 99),
            )

            // Assert
            assertEquals(refreshedUpstream, existingSession.upstreamPeerId)
            assertEquals(
                listOf(
                    RecordedRelayFrame(
                        peerId = existingSession.destinationPeerId,
                        action = "transfer.forward.start",
                        transferId = existingSession.transferId,
                        hardRunEpoch = existingSession.hardRunToken.epoch,
                    )
                ),
                callbacks.routedFrames,
            )
        }

    @Test
    fun `handleTransferChunk and handleTransferAck forward along the stored relay path`() =
        runBlocking {
            // Arrange
            val relaySession =
                RelayTransferSession(
                    transferId = "transfer-1",
                    messageId = "message-1",
                    originPeerId = PeerId("origin"),
                    destinationPeerId = PeerId("destination"),
                    upstreamPeerId = PeerId("upstream"),
                    hardRunToken = MeshEngineHardRunToken(epoch = 5),
                )
            val relayTransfers = mutableMapOf(relaySession.transferId to relaySession)
            val callbacks = RecordingRelayTransferCallbacks()
            val support =
                relayTransferSupport(relayTransfers = relayTransfers, callbacks = callbacks)
            val chunk =
                WireFrame.TransferChunk(
                    transferId = relaySession.transferId,
                    chunkIndex = 0,
                    payload = byteArrayOf(1),
                )
            val ack =
                WireFrame.TransferAck(
                    transferId = relaySession.transferId,
                    highestContiguousAck = 0,
                    selectiveRanges = byteArrayOf(),
                )

            // Act
            val chunkHandled = support.handleTransferChunk(chunk)
            val ackHandled = support.handleTransferAck(ack)

            // Assert
            assertTrue(chunkHandled)
            assertTrue(ackHandled)
            assertEquals(
                listOf(
                    RecordedRelayFrame(
                        peerId = relaySession.destinationPeerId,
                        action = "transfer.forward.chunk",
                        transferId = relaySession.transferId,
                        hardRunEpoch = relaySession.hardRunToken.epoch,
                    )
                ),
                callbacks.routedFrames,
            )
            assertEquals(
                listOf(
                    RecordedRelayFrame(
                        peerId = relaySession.upstreamPeerId,
                        action = "transfer.forward.ack",
                        transferId = relaySession.transferId,
                        hardRunEpoch = relaySession.hardRunToken.epoch,
                    )
                ),
                callbacks.encryptedFrames,
            )
        }

    @Test
    fun `terminal relay frames forward once and remove the relay session`() = runBlocking {
        // Arrange
        val completedSession =
            RelayTransferSession(
                transferId = "transfer-complete",
                messageId = "message-complete",
                originPeerId = PeerId("origin"),
                destinationPeerId = PeerId("destination-complete"),
                upstreamPeerId = PeerId("upstream-complete"),
                hardRunToken = MeshEngineHardRunToken(epoch = 6),
            )
        val abortedSession =
            RelayTransferSession(
                transferId = "transfer-abort",
                messageId = "message-abort",
                originPeerId = PeerId("origin"),
                destinationPeerId = PeerId("destination-abort"),
                upstreamPeerId = PeerId("upstream-abort"),
                hardRunToken = MeshEngineHardRunToken(epoch = 8),
            )
        val relayTransfers =
            mutableMapOf(
                completedSession.transferId to completedSession,
                abortedSession.transferId to abortedSession,
            )
        val callbacks = RecordingRelayTransferCallbacks()
        val support = relayTransferSupport(relayTransfers = relayTransfers, callbacks = callbacks)

        // Act
        val completeHandled =
            support.handleTransferComplete(WireFrame.TransferComplete(completedSession.transferId))
        val abortHandled =
            support.handleTransferAbort(
                WireFrame.TransferAbort(transferId = abortedSession.transferId, reasonCode = 1)
            )

        // Assert
        assertTrue(completeHandled)
        assertTrue(abortHandled)
        assertFalse(relayTransfers.containsKey(completedSession.transferId))
        assertFalse(relayTransfers.containsKey(abortedSession.transferId))
        assertEquals(
            listOf(
                RecordedRelayFrame(
                    peerId = completedSession.destinationPeerId,
                    action = "transfer.forward.complete",
                    transferId = completedSession.transferId,
                    hardRunEpoch = completedSession.hardRunToken.epoch,
                ),
                RecordedRelayFrame(
                    peerId = abortedSession.destinationPeerId,
                    action = "transfer.forward.abort",
                    transferId = abortedSession.transferId,
                    hardRunEpoch = abortedSession.hardRunToken.epoch,
                ),
            ),
            callbacks.routedFrames,
        )
    }

    @Test
    fun `relay handlers ignore unknown transfer ids`() = runBlocking {
        // Arrange
        val callbacks = RecordingRelayTransferCallbacks()
        val support = relayTransferSupport(relayTransfers = mutableMapOf(), callbacks = callbacks)

        // Act
        val chunkHandled =
            support.handleTransferChunk(
                WireFrame.TransferChunk(
                    transferId = "missing-transfer",
                    chunkIndex = 0,
                    payload = byteArrayOf(1),
                )
            )
        val ackHandled =
            support.handleTransferAck(
                WireFrame.TransferAck(
                    transferId = "missing-transfer",
                    highestContiguousAck = 0,
                    selectiveRanges = byteArrayOf(),
                )
            )
        val completeHandled =
            support.handleTransferComplete(WireFrame.TransferComplete("missing-transfer"))
        val abortHandled =
            support.handleTransferAbort(
                WireFrame.TransferAbort(transferId = "missing-transfer", reasonCode = 1)
            )

        // Assert
        assertFalse(chunkHandled)
        assertFalse(ackHandled)
        assertFalse(completeHandled)
        assertFalse(abortHandled)
        assertTrue(callbacks.encryptedFrames.isEmpty())
        assertTrue(callbacks.routedFrames.isEmpty())
    }
}

private fun relayTransferSupport(
    relayTransfers: MutableMap<String, RelayTransferSession>,
    callbacks: RecordingRelayTransferCallbacks,
): MeshEngineRelayTransferSupport {
    return MeshEngineRelayTransferSupport(
        relayTransfers = relayTransfers,
        callbacks =
            MeshEngineRelayTransferCallbacks(
                sendEncryptedWireFrame = { peerId, frame, action, hardRunToken ->
                    callbacks.encryptedFrames +=
                        RecordedRelayFrame(
                            peerId = peerId,
                            action = action,
                            transferId = frame.transferIdForTest(),
                            hardRunEpoch = hardRunToken?.epoch,
                        )
                    true
                },
                sendTransferTowardsDestination = { peerId, frame, action, hardRunToken ->
                    callbacks.routedFrames +=
                        RecordedRelayFrame(
                            peerId = peerId,
                            action = action,
                            transferId = frame.transferIdForTest(),
                            hardRunEpoch = hardRunToken?.epoch,
                        )
                    true
                },
            ),
    )
}

private fun WireFrame.transferIdForTest(): String {
    return when (this) {
        is WireFrame.TransferStart -> transferId
        is WireFrame.TransferChunk -> transferId
        is WireFrame.TransferAck -> transferId
        is WireFrame.TransferComplete -> transferId
        is WireFrame.TransferAbort -> transferId
        else -> error("Unexpected frame type $this")
    }
}

private data class RecordedRelayFrame(
    val peerId: PeerId,
    val action: String,
    val transferId: String,
    val hardRunEpoch: Long?,
)

private class RecordingRelayTransferCallbacks {
    val encryptedFrames: MutableList<RecordedRelayFrame> = mutableListOf()
    val routedFrames: MutableList<RecordedRelayFrame> = mutableListOf()
}
