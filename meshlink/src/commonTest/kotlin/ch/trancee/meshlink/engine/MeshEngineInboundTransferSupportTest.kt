package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.TransferSessionRoute
import ch.trancee.meshlink.transfer.TransferStartDescriptor
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeshEngineInboundTransferSupportTest {
    @Test
    fun `handleTransferStart stores the inbound session and sends the start acknowledgement`() =
        runBlocking {
            // Arrange
            val inboundTransfers = mutableMapOf<String, InboundTransferSession>()
            val callbacks = RecordingInboundTransferCallbacks()
            val support =
                inboundTransferSupport(inboundTransfers = inboundTransfers, callbacks = callbacks)
            val peerId = PeerId("upstream")
            val frame = newTransferStartFrame(transferId = "transfer-1", totalChunks = 2)
            val hardRunToken = MeshEngineHardRunToken(epoch = 4)

            // Act
            support.handleTransferStart(peerId = peerId, frame = frame, hardRunToken = hardRunToken)

            // Assert
            val session = inboundTransfers.getValue(frame.transferId)
            assertEquals(peerId, session.upstreamPeerId)
            assertEquals(
                listOf(
                    RecordedInboundAck(
                        peerId = peerId,
                        action = "transfer.ack.start",
                        transferId = frame.transferId,
                        highestContiguousAck = -1,
                        hardRunEpoch = hardRunToken.epoch,
                    )
                ),
                callbacks.acks,
            )
            assertEquals(
                listOf("transfer.receive.start", "transfer.ack.start"),
                callbacks.diagnostics.map { diagnostic -> diagnostic.stage },
            )
        }

    @Test
    fun `handleTransferChunk acknowledges and delivers a completed one chunk inbound transfer`() =
        runBlocking {
            // Arrange
            val inboundTransfers = mutableMapOf<String, InboundTransferSession>()
            val callbacks = RecordingInboundTransferCallbacks()
            val support =
                inboundTransferSupport(inboundTransfers = inboundTransfers, callbacks = callbacks)
            val peerId = PeerId("upstream")
            val frame = newTransferStartFrame(transferId = "transfer-1", totalChunks = 1)
            val hardRunToken = MeshEngineHardRunToken(epoch = 5)
            support.handleTransferStart(peerId = peerId, frame = frame, hardRunToken = hardRunToken)
            val chunk =
                WireFrame.TransferChunk(
                    transferId = frame.transferId,
                    chunkIndex = 0,
                    payload = "hello".encodeToByteArray(),
                )

            // Act
            val handled = support.handleTransferChunk(peerId = peerId, frame = chunk)

            // Assert
            assertTrue(handled)
            assertFalse(inboundTransfers.containsKey(frame.transferId))
            assertEquals(
                RecordedInboundAck(
                    peerId = peerId,
                    action = "transfer.ack.chunk",
                    transferId = frame.transferId,
                    highestContiguousAck = 0,
                    hardRunEpoch = hardRunToken.epoch,
                ),
                callbacks.acks.last(),
            )
            val delivered = callbacks.deliveries.single()
            assertEquals(peerId, delivered.peerId)
            assertEquals(frame.originPeerId, delivered.originPeerId)
            assertEquals(DeliveryPriority.NORMAL, delivered.priority)
            assertEquals(hardRunToken.epoch, delivered.hardRunEpoch)
            assertContentEquals(chunk.payload, delivered.payload)
            assertEquals(
                listOf(
                    "transfer.receive.start",
                    "transfer.ack.start",
                    "transfer.receive.chunk",
                    "transfer.ack.chunk",
                    "transfer.receive.complete",
                ),
                callbacks.diagnostics.map { diagnostic -> diagnostic.stage },
            )
        }

    @Test
    fun `handleTransferComplete delivers an already assembled inbound session`() = runBlocking {
        // Arrange
        val peerId = PeerId("upstream")
        val session =
            newInboundSession(transferId = "transfer-1", upstreamPeerId = peerId, totalChunks = 2)
        session.acceptChunk(
            WireFrame.TransferChunk(
                transferId = session.transferId,
                chunkIndex = 0,
                payload = "he".encodeToByteArray(),
            )
        )
        session.acceptChunk(
            WireFrame.TransferChunk(
                transferId = session.transferId,
                chunkIndex = 1,
                payload = "llo".encodeToByteArray(),
            )
        )
        val inboundTransfers = mutableMapOf(session.transferId to session)
        val callbacks = RecordingInboundTransferCallbacks()
        val support =
            inboundTransferSupport(inboundTransfers = inboundTransfers, callbacks = callbacks)

        // Act
        val handled =
            support.handleTransferComplete(
                peerId = peerId,
                frame = WireFrame.TransferComplete(session.transferId),
            )

        // Assert
        assertTrue(handled)
        assertFalse(inboundTransfers.containsKey(session.transferId))
        val delivered = callbacks.deliveries.single()
        assertEquals(session.originPeerId, delivered.originPeerId)
        assertContentEquals("hello".encodeToByteArray(), delivered.payload)
    }

    @Test
    fun `handleTransferAbort removes the tracked inbound session`() {
        // Arrange
        val session =
            newInboundSession(
                transferId = "transfer-1",
                upstreamPeerId = PeerId("upstream"),
                totalChunks = 1,
            )
        val inboundTransfers = mutableMapOf(session.transferId to session)
        val callbacks = RecordingInboundTransferCallbacks()
        val support =
            inboundTransferSupport(inboundTransfers = inboundTransfers, callbacks = callbacks)

        // Act
        val handled =
            support.handleTransferAbort(
                WireFrame.TransferAbort(transferId = session.transferId, reasonCode = 1)
            )

        // Assert
        assertTrue(handled)
        assertFalse(inboundTransfers.containsKey(session.transferId))
        assertTrue(callbacks.acks.isEmpty())
        assertTrue(callbacks.deliveries.isEmpty())
    }
}

private fun inboundTransferSupport(
    inboundTransfers: MutableMap<String, InboundTransferSession>,
    callbacks: RecordingInboundTransferCallbacks,
): MeshEngineInboundTransferSupport {
    return MeshEngineInboundTransferSupport(
        inboundTransfers = inboundTransfers,
        callbacks =
            MeshEngineInboundTransferSupportCallbacks(
                sendEncryptedWireFrame = { peerId, frame, action, hardRunToken ->
                    val ack = frame as WireFrame.TransferAck
                    callbacks.acks +=
                        RecordedInboundAck(
                            peerId = peerId,
                            action = action,
                            transferId = ack.transferId,
                            highestContiguousAck = ack.highestContiguousAck,
                            hardRunEpoch = hardRunToken?.epoch,
                        )
                    true
                },
                deliverInnerEnvelope = { peerId, originPeerId, payload, priority, hardRunToken ->
                    callbacks.deliveries +=
                        RecordedInboundDelivery(
                            peerId = peerId,
                            originPeerId = originPeerId,
                            payload = payload,
                            priority = priority,
                            hardRunEpoch = hardRunToken.epoch,
                        )
                },
                routeMetadata = { _, metadata -> metadata + ("route" to "available") },
                emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                    callbacks.diagnostics +=
                        RecordingInboundDiagnostic(
                            code = code,
                            severity = severity,
                            stage = stage,
                            peerSuffix = peerSuffix,
                            reason = reason,
                            metadata = metadata,
                        )
                },
            ),
    )
}

private fun newTransferStartFrame(
    transferId: String,
    totalChunks: Int,
    totalBytes: Int = 5,
): WireFrame.TransferStart {
    return WireFrame.TransferStart(
        route =
            WireFrame.TransferStartRoute(
                transferId = transferId,
                messageId = "message-$transferId",
                originPeerId = PeerId("origin"),
                destinationPeerId = PeerId("self"),
            ),
        sizing =
            WireFrame.TransferStartSizing(
                totalBytes = totalBytes,
                totalChunks = totalChunks,
                maxChunkPayloadBytes = 5,
            ),
    )
}

private fun newInboundSession(
    transferId: String,
    upstreamPeerId: PeerId,
    totalChunks: Int,
    totalBytes: Int = 5,
): InboundTransferSession {
    return InboundTransferSession(
        startDescriptor =
            TransferStartDescriptor(
                route =
                    TransferSessionRoute(
                        transferId = transferId,
                        messageId = "message-$transferId",
                        originPeerId = PeerId("origin"),
                        destinationPeerId = PeerId("self"),
                    ),
                totalBytes = totalBytes,
                totalChunks = totalChunks,
                maxChunkPayloadBytes = 5,
            ),
        upstreamPeerId = upstreamPeerId,
        hardRunToken = MeshEngineHardRunToken(epoch = 6),
    )
}

private data class RecordedInboundAck(
    val peerId: PeerId,
    val action: String,
    val transferId: String,
    val highestContiguousAck: Int,
    val hardRunEpoch: Long?,
)

private data class RecordedInboundDelivery(
    val peerId: PeerId,
    val originPeerId: PeerId,
    val payload: ByteArray,
    val priority: DeliveryPriority,
    val hardRunEpoch: Long,
)

private data class RecordingInboundDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)

private class RecordingInboundTransferCallbacks {
    val acks: MutableList<RecordedInboundAck> = mutableListOf()
    val deliveries: MutableList<RecordedInboundDelivery> = mutableListOf()
    val diagnostics: MutableList<RecordingInboundDiagnostic> = mutableListOf()
}
