package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.RelayTransferSession
import ch.trancee.meshlink.transfer.TransferChunkPlan
import ch.trancee.meshlink.transfer.TransferSessionRoute
import ch.trancee.meshlink.transfer.TransferStartDescriptor
import ch.trancee.meshlink.wire.TransferAbortReasonCode
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeshEngineTransferSupportTest {
    @Test
    fun `handleTransferStart routes local destinations to inbound support`() = runBlocking {
        // Arrange
        val upstreamPeerId = PeerId("upstream")
        val frame =
            transferSupportNewTransferStartFrame(
                transferId = "transfer-1",
                destinationPeerId = PeerId("self"),
            )
        val fixture = transferSupportFixture(isLocalPeerId = { peerId -> peerId.value == "self" })

        // Act
        fixture.support.handleTransferStart(peerId = upstreamPeerId, frame = frame)

        // Assert
        assertTrue(fixture.inboundTransfers.containsKey(frame.transferId))
        assertEquals(
            listOf(
                RecordedTransferSupportInboundAck(
                    peerIdValue = upstreamPeerId.value,
                    action = "transfer.ack.start",
                    transferId = frame.transferId,
                    highestContiguousAck = -1,
                    hardRunEpoch = fixture.hardRunToken.epoch,
                )
            ),
            fixture.inboundAcks,
        )
        assertTrue(fixture.relayRoutedFrames.isEmpty())
    }

    @Test
    fun `handleTransferStart routes remote destinations to relay support`() = runBlocking {
        // Arrange
        val upstreamPeerId = PeerId("upstream")
        val frame =
            transferSupportNewTransferStartFrame(
                transferId = "transfer-2",
                destinationPeerId = PeerId("destination"),
            )
        val fixture = transferSupportFixture(isLocalPeerId = { false })

        // Act
        fixture.support.handleTransferStart(peerId = upstreamPeerId, frame = frame)

        // Assert
        val relaySession = fixture.relayTransfers.getValue(frame.transferId)
        assertEquals(upstreamPeerId.value, relaySession.upstreamPeerId.value)
        assertEquals(frame.destinationPeerId.value, relaySession.destinationPeerId.value)
        assertEquals(
            listOf(
                RecordedTransferSupportRelayFrame(
                    peerIdValue = frame.destinationPeerId.value,
                    action = "transfer.forward.start",
                    transferId = frame.transferId,
                    hardRunEpoch = fixture.hardRunToken.epoch,
                )
            ),
            fixture.relayRoutedFrames,
        )
        assertTrue(fixture.inboundAcks.isEmpty())
    }

    @Test
    fun `handleTransferChunk forwards to relay when inbound support does not own the transfer id`() =
        runBlocking {
            // Arrange
            val relaySession =
                transferSupportNewRelaySession(
                    transferId = "transfer-3",
                    destinationPeerId = PeerId("destination"),
                    upstreamPeerId = PeerId("upstream"),
                )
            val fixture =
                transferSupportFixture(
                    relayTransfers = mutableMapOf(relaySession.transferId to relaySession)
                )
            val chunk =
                WireFrame.TransferChunk(
                    transferId = relaySession.transferId,
                    chunkIndex = 0,
                    payload = byteArrayOf(0x01),
                )

            // Act
            fixture.support.handleTransferChunk(peerId = relaySession.upstreamPeerId, frame = chunk)

            // Assert
            assertEquals(
                listOf(
                    RecordedTransferSupportRelayFrame(
                        peerIdValue = relaySession.destinationPeerId.value,
                        action = "transfer.forward.chunk",
                        transferId = relaySession.transferId,
                        hardRunEpoch = relaySession.hardRunToken.epoch,
                    )
                ),
                fixture.relayRoutedFrames,
            )
        }

    @Test
    fun `handleTransferAck acknowledges outbound transfers before consulting relay support`() =
        runBlocking {
            // Arrange
            val outboundSession =
                transferSupportNewOutboundSession(
                    transferId = "transfer-4",
                    destinationPeerId = PeerId("destination"),
                )
            val relaySession =
                transferSupportNewRelaySession(
                    transferId = outboundSession.transferId,
                    destinationPeerId = PeerId("relay-destination"),
                    upstreamPeerId = PeerId("relay-upstream"),
                )
            val fixture =
                transferSupportFixture(
                    outboundTransfers = mutableMapOf(outboundSession.transferId to outboundSession),
                    relayTransfers = mutableMapOf(relaySession.transferId to relaySession),
                )
            val ack =
                WireFrame.TransferAck(
                    transferId = outboundSession.transferId,
                    highestContiguousAck = 0,
                    selectiveRanges = byteArrayOf(),
                )

            // Act
            fixture.support.handleTransferAck(peerId = PeerId("ignored-upstream"), frame = ack)

            // Assert
            assertEquals(1, outboundSession.acknowledgedChunkCount())
            assertTrue(fixture.relayEncryptedFrames.isEmpty())
        }

    @Test
    fun `handleTransferComplete routes complete local sessions to inbound delivery`() =
        runBlocking {
            // Arrange
            val upstreamPeerId = PeerId("upstream")
            val inboundSession =
                transferSupportNewInboundSession(
                    transferId = "transfer-5",
                    upstreamPeerId = upstreamPeerId,
                    destinationPeerId = PeerId("self"),
                    totalChunks = 2,
                    totalBytes = 5,
                )
            inboundSession.acceptChunk(
                WireFrame.TransferChunk(
                    transferId = inboundSession.transferId,
                    chunkIndex = 0,
                    payload = "he".encodeToByteArray(),
                )
            )
            inboundSession.acceptChunk(
                WireFrame.TransferChunk(
                    transferId = inboundSession.transferId,
                    chunkIndex = 1,
                    payload = "llo".encodeToByteArray(),
                )
            )
            val fixture =
                transferSupportFixture(
                    inboundTransfers = mutableMapOf(inboundSession.transferId to inboundSession),
                    isLocalPeerId = { peerId -> peerId.value == "self" },
                )

            // Act
            fixture.support.handleTransferComplete(
                peerId = upstreamPeerId,
                frame = WireFrame.TransferComplete(inboundSession.transferId),
            )

            // Assert
            val delivery = fixture.inboundDeliveries.single()
            assertEquals(upstreamPeerId.value, delivery.peerIdValue)
            assertEquals(inboundSession.originPeerId.value, delivery.originPeerIdValue)
            assertEquals(DeliveryPriority.NORMAL, delivery.priority)
            assertEquals(inboundSession.hardRunToken.epoch, delivery.hardRunEpoch)
            assertContentEquals("hello".encodeToByteArray(), delivery.payload)
        }

    @Test
    fun `handleTransferAbort falls back to outbound transfer removal and diagnostic emission`() =
        runBlocking {
            // Arrange
            val outboundSession =
                transferSupportNewOutboundSession(
                    transferId = "transfer-6",
                    destinationPeerId = PeerId("destination"),
                )
            val fixture =
                transferSupportFixture(
                    outboundTransfers = mutableMapOf(outboundSession.transferId to outboundSession)
                )

            // Act
            fixture.support.handleTransferAbort(
                peerId = PeerId("abort-source"),
                frame =
                    WireFrame.TransferAbort(
                        transferId = outboundSession.transferId,
                        reasonCode = 99,
                    ),
            )

            // Assert
            assertFalse(fixture.outboundTransfers.containsKey(outboundSession.transferId))
            assertEquals(
                listOf(
                    RecordedTransferSupportDiagnostic(
                        code = DiagnosticCode.TRANSFER_FAILED,
                        severity = DiagnosticSeverity.ERROR,
                        stage = "transfer.abort",
                        peerSuffix = "source",
                        reason = DiagnosticReason.TRANSFER_FAILURE,
                        metadata = mapOf("reasonCode" to "99"),
                    )
                ),
                fixture.diagnostics,
            )
        }

    @Test
    fun `abortLocalTransfers delegates to the abort support`() = runBlocking {
        // Arrange
        val outboundSession =
            transferSupportNewOutboundSession(
                transferId = "transfer-7",
                destinationPeerId = PeerId("destination"),
            )
        val inboundSession =
            transferSupportNewInboundSession(
                transferId = "transfer-8",
                upstreamPeerId = PeerId("upstream"),
                destinationPeerId = PeerId("self"),
                totalChunks = 1,
                totalBytes = 1,
            )
        val relaySession =
            transferSupportNewRelaySession(
                transferId = "transfer-9",
                destinationPeerId = PeerId("relay-destination"),
                upstreamPeerId = PeerId("relay-upstream"),
            )
        val fixture =
            transferSupportFixture(
                outboundTransfers = mutableMapOf(outboundSession.transferId to outboundSession),
                inboundTransfers = mutableMapOf(inboundSession.transferId to inboundSession),
                relayTransfers = mutableMapOf(relaySession.transferId to relaySession),
            )

        // Act
        fixture.support.abortLocalTransfers(TransferAbortReasonCode.RUNTIME_STOPPED)

        // Assert
        assertTrue(fixture.outboundTransfers.isEmpty())
        assertTrue(fixture.inboundTransfers.isEmpty())
        assertTrue(fixture.relayTransfers.isEmpty())
        assertEquals(
            listOf(
                RecordedTransferSupportAbortFrame(
                    peerIdValue = outboundSession.destinationPeerId.value,
                    action = "transfer.abort.runtimeStop",
                    transferId = outboundSession.transferId,
                ),
                RecordedTransferSupportAbortFrame(
                    peerIdValue = relaySession.destinationPeerId.value,
                    action = "transfer.abort.runtimeStop.downstream",
                    transferId = relaySession.transferId,
                ),
            ),
            fixture.abortRoutedFrames,
        )
    }
}

private data class TransferSupportFixture(
    val support: MeshEngineTransferSupport,
    val hardRunToken: MeshEngineHardRunToken,
    val outboundTransfers: MutableMap<String, OutboundTransferSession>,
    val inboundTransfers: MutableMap<String, InboundTransferSession>,
    val relayTransfers: MutableMap<String, RelayTransferSession>,
    val inboundAcks: MutableList<RecordedTransferSupportInboundAck>,
    val inboundDeliveries: MutableList<RecordedTransferSupportInboundDelivery>,
    val relayEncryptedFrames: MutableList<RecordedTransferSupportRelayFrame>,
    val relayRoutedFrames: MutableList<RecordedTransferSupportRelayFrame>,
    val abortEncryptedFrames: MutableList<RecordedTransferSupportAbortFrame>,
    val abortRoutedFrames: MutableList<RecordedTransferSupportAbortFrame>,
    val clearedOutboundFrames: MutableList<RecordedTransferSupportClearedFrames>,
    val diagnostics: MutableList<RecordedTransferSupportDiagnostic>,
)

private fun transferSupportFixture(
    outboundTransfers: MutableMap<String, OutboundTransferSession> = mutableMapOf(),
    inboundTransfers: MutableMap<String, InboundTransferSession> = mutableMapOf(),
    relayTransfers: MutableMap<String, RelayTransferSession> = mutableMapOf(),
    isLocalPeerId: (PeerId) -> Boolean = { false },
): TransferSupportFixture {
    val hardRunToken = MeshEngineHardRunToken(epoch = 7)
    val inboundAcks = mutableListOf<RecordedTransferSupportInboundAck>()
    val inboundDeliveries = mutableListOf<RecordedTransferSupportInboundDelivery>()
    val relayEncryptedFrames = mutableListOf<RecordedTransferSupportRelayFrame>()
    val relayRoutedFrames = mutableListOf<RecordedTransferSupportRelayFrame>()
    val abortEncryptedFrames = mutableListOf<RecordedTransferSupportAbortFrame>()
    val abortRoutedFrames = mutableListOf<RecordedTransferSupportAbortFrame>()
    val clearedOutboundFrames = mutableListOf<RecordedTransferSupportClearedFrames>()
    val diagnostics = mutableListOf<RecordedTransferSupportDiagnostic>()
    val state =
        MeshEngineTransferState(
            inboundTransfers = inboundTransfers,
            relayTransfers = relayTransfers,
        )
    val outboundTransferLifecycleSupport =
        MeshEngineOutboundTransferLifecycleSupport(
            state = MeshEngineOutboundTransferLifecycleState(outboundTransfers = outboundTransfers),
            dependencies =
                MeshEngineOutboundTransferLifecycleDependencies(
                    prepareOutboundTransferSession = { _, _, _ ->
                        error("unexpected outbound transfer preparation")
                    },
                    scheduleRetryDiagnostic = { _, _ -> Unit },
                ),
        )
    val inboundSupport =
        MeshEngineInboundTransferSupport(
            inboundTransfers = inboundTransfers,
            callbacks =
                MeshEngineInboundTransferSupportCallbacks(
                    sendEncryptedWireFrame = { peerId, frame, action, inboundHardRunToken ->
                        val ack = frame as WireFrame.TransferAck
                        inboundAcks +=
                            RecordedTransferSupportInboundAck(
                                peerIdValue = peerId.value,
                                action = action,
                                transferId = ack.transferId,
                                highestContiguousAck = ack.highestContiguousAck,
                                hardRunEpoch = inboundHardRunToken?.epoch,
                            )
                        true
                    },
                    deliverInnerEnvelope = {
                        peerId,
                        originPeerId,
                        payload,
                        priority,
                        inboundHardRunToken ->
                        inboundDeliveries +=
                            RecordedTransferSupportInboundDelivery(
                                peerIdValue = peerId.value,
                                originPeerIdValue = originPeerId.value,
                                payload = payload,
                                priority = priority,
                                hardRunEpoch = inboundHardRunToken.epoch,
                            )
                    },
                    routeMetadata = { _, metadata -> metadata + ("route" to "available") },
                    emitDiagnostic = { _, _, _, _, _, _ -> Unit },
                ),
        )
    val relaySupport =
        MeshEngineRelayTransferSupport(
            relayTransfers = relayTransfers,
            callbacks =
                MeshEngineRelayTransferCallbacks(
                    sendEncryptedWireFrame = { peerId, frame, action, relayHardRunToken ->
                        relayEncryptedFrames +=
                            RecordedTransferSupportRelayFrame(
                                peerIdValue = peerId.value,
                                action = action,
                                transferId = frame.transferIdForTransferSupportTest(),
                                hardRunEpoch = relayHardRunToken?.epoch,
                            )
                        true
                    },
                    sendTransferTowardsDestination = { peerId, frame, action, relayHardRunToken ->
                        relayRoutedFrames +=
                            RecordedTransferSupportRelayFrame(
                                peerIdValue = peerId.value,
                                action = action,
                                transferId = frame.transferIdForTransferSupportTest(),
                                hardRunEpoch = relayHardRunToken?.epoch,
                            )
                        true
                    },
                ),
        )
    val abortSupport =
        MeshEngineTransferAbortSupport(
            state = state,
            outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
            callbacks =
                MeshEngineTransferAbortCallbacks(
                    sendEncryptedWireFrame = { peerId, frame, action, _ ->
                        val abortFrame = frame as WireFrame.TransferAbort
                        abortEncryptedFrames +=
                            RecordedTransferSupportAbortFrame(
                                peerIdValue = peerId.value,
                                action = action,
                                transferId = abortFrame.transferId,
                            )
                        true
                    },
                    sendTransferTowardsDestination = { peerId, frame, action, _ ->
                        val abortFrame = frame as WireFrame.TransferAbort
                        abortRoutedFrames +=
                            RecordedTransferSupportAbortFrame(
                                peerIdValue = peerId.value,
                                action = action,
                                transferId = abortFrame.transferId,
                            )
                        true
                    },
                    clearQueuedOutboundFrames = { peerId, action ->
                        clearedOutboundFrames +=
                            RecordedTransferSupportClearedFrames(
                                peerIdValue = peerId.value,
                                action = action,
                            )
                    },
                    routeMetadata = { _, metadata -> metadata + ("route" to "available") },
                ),
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                diagnostics +=
                    RecordedTransferSupportDiagnostic(
                        code = code,
                        severity = severity,
                        stage = stage,
                        peerSuffix = peerSuffix,
                        reason = reason,
                        metadata = metadata,
                    )
            },
        )
    val support =
        MeshEngineTransferSupport(
            state = state,
            outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
            callbacks =
                MeshEngineTransferCallbacks(
                    captureHardRunToken = { hardRunToken },
                    isLocalPeerId = isLocalPeerId,
                ),
            inboundSupport = inboundSupport,
            relaySupport = relaySupport,
            abortSupport = abortSupport,
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                diagnostics +=
                    RecordedTransferSupportDiagnostic(
                        code = code,
                        severity = severity,
                        stage = stage,
                        peerSuffix = peerSuffix,
                        reason = reason,
                        metadata = metadata,
                    )
            },
        )
    return TransferSupportFixture(
        support = support,
        hardRunToken = hardRunToken,
        outboundTransfers = outboundTransfers,
        inboundTransfers = inboundTransfers,
        relayTransfers = relayTransfers,
        inboundAcks = inboundAcks,
        inboundDeliveries = inboundDeliveries,
        relayEncryptedFrames = relayEncryptedFrames,
        relayRoutedFrames = relayRoutedFrames,
        abortEncryptedFrames = abortEncryptedFrames,
        abortRoutedFrames = abortRoutedFrames,
        clearedOutboundFrames = clearedOutboundFrames,
        diagnostics = diagnostics,
    )
}

private fun WireFrame.transferIdForTransferSupportTest(): String {
    return when (this) {
        is WireFrame.TransferStart -> transferId
        is WireFrame.TransferChunk -> transferId
        is WireFrame.TransferAck -> transferId
        is WireFrame.TransferComplete -> transferId
        is WireFrame.TransferAbort -> transferId
        else -> error("Unexpected frame type $this")
    }
}

private fun transferSupportNewTransferStartFrame(
    transferId: String,
    destinationPeerId: PeerId,
    totalChunks: Int = 1,
    totalBytes: Int = 1,
): WireFrame.TransferStart {
    return WireFrame.TransferStart(
        route =
            WireFrame.TransferStartRoute(
                transferId = transferId,
                messageId = "message-$transferId",
                originPeerId = PeerId("origin"),
                destinationPeerId = destinationPeerId,
            ),
        sizing =
            WireFrame.TransferStartSizing(
                totalBytes = totalBytes,
                totalChunks = totalChunks,
                maxChunkPayloadBytes = totalBytes,
            ),
    )
}

private fun transferSupportNewOutboundSession(
    transferId: String,
    destinationPeerId: PeerId,
): OutboundTransferSession {
    val chunks = listOf(byteArrayOf(0), byteArrayOf(1))
    return OutboundTransferSession(
        route =
            TransferSessionRoute(
                transferId = transferId,
                messageId = "message-$transferId",
                originPeerId = PeerId("origin"),
                destinationPeerId = destinationPeerId,
            ),
        chunkPlan =
            TransferChunkPlan(
                chunks = chunks,
                totalBytes = chunks.sumOf { chunk -> chunk.size },
                maxChunkPayloadBytes = 1,
            ),
    )
}

private fun transferSupportNewInboundSession(
    transferId: String,
    upstreamPeerId: PeerId,
    destinationPeerId: PeerId,
    totalChunks: Int,
    totalBytes: Int,
): InboundTransferSession {
    return InboundTransferSession(
        startDescriptor =
            TransferStartDescriptor(
                route =
                    TransferSessionRoute(
                        transferId = transferId,
                        messageId = "message-$transferId",
                        originPeerId = PeerId("origin"),
                        destinationPeerId = destinationPeerId,
                    ),
                totalBytes = totalBytes,
                totalChunks = totalChunks,
                maxChunkPayloadBytes = totalBytes.coerceAtLeast(1),
            ),
        upstreamPeerId = upstreamPeerId,
        hardRunToken = MeshEngineHardRunToken(epoch = 6),
    )
}

private fun transferSupportNewRelaySession(
    transferId: String,
    destinationPeerId: PeerId,
    upstreamPeerId: PeerId,
): RelayTransferSession {
    return RelayTransferSession(
        transferId = transferId,
        messageId = "message-$transferId",
        originPeerId = PeerId("origin"),
        destinationPeerId = destinationPeerId,
        upstreamPeerId = upstreamPeerId,
        hardRunToken = MeshEngineHardRunToken(epoch = 3),
    )
}

private data class RecordedTransferSupportInboundAck(
    val peerIdValue: String,
    val action: String,
    val transferId: String,
    val highestContiguousAck: Int,
    val hardRunEpoch: Long?,
)

private data class RecordedTransferSupportInboundDelivery(
    val peerIdValue: String,
    val originPeerIdValue: String,
    val payload: ByteArray,
    val priority: DeliveryPriority,
    val hardRunEpoch: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordedTransferSupportInboundDelivery) return false
        return peerIdValue == other.peerIdValue &&
            originPeerIdValue == other.originPeerIdValue &&
            payload.contentEquals(other.payload) &&
            priority == other.priority &&
            hardRunEpoch == other.hardRunEpoch
    }

    override fun hashCode(): Int {
        var result = peerIdValue.hashCode()
        result = 31 * result + originPeerIdValue.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + priority.hashCode()
        result = 31 * result + hardRunEpoch.hashCode()
        return result
    }
}

private data class RecordedTransferSupportRelayFrame(
    val peerIdValue: String,
    val action: String,
    val transferId: String,
    val hardRunEpoch: Long?,
)

private data class RecordedTransferSupportAbortFrame(
    val peerIdValue: String,
    val action: String,
    val transferId: String,
)

private data class RecordedTransferSupportClearedFrames(val peerIdValue: String, val action: String)

private data class RecordedTransferSupportDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)
