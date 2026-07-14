package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.engine.internal.OutboundTransferPreparation
import ch.trancee.meshlink.engine.transfer.MeshEngineOutboundTransferLifecycleDependencies
import ch.trancee.meshlink.engine.transfer.MeshEngineOutboundTransferLifecycleState
import ch.trancee.meshlink.engine.transfer.MeshEngineOutboundTransferLifecycleSupport
import ch.trancee.meshlink.engine.transfer.MeshEngineTransferAbortCallbacks
import ch.trancee.meshlink.engine.transfer.MeshEngineTransferAbortSupport
import ch.trancee.meshlink.engine.transfer.MeshEngineTransferState
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.RelayTransferSession
import ch.trancee.meshlink.transfer.TransferChunkPlan
import ch.trancee.meshlink.transfer.TransferSessionRoute
import ch.trancee.meshlink.transfer.TransferStartDescriptor
import ch.trancee.meshlink.wire.TransferAbortReasonCode
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeshEngineTransferAbortSupportTest {
    @Test
    fun `abortLocalTransfers clears all local transfer scopes and emits the matching abort frames`() =
        runBlocking<Unit> {
            // Arrange
            val outboundSession = newOutboundSession("outbound-1", PeerId("destination"))
            val inboundSession = newInboundSession("inbound-1", PeerId("upstream"))
            val relaySession =
                newRelaySession(
                    transferId = "relay-1",
                    destinationPeerId = PeerId("relay-destination"),
                    upstreamPeerId = PeerId("relay-upstream"),
                )
            val outboundTransfers = mutableMapOf(outboundSession.transferId to outboundSession)
            val state =
                MeshEngineTransferState(
                    inboundTransfers = mutableMapOf(inboundSession.transferId to inboundSession),
                    relayTransfers = mutableMapOf(relaySession.transferId to relaySession),
                )
            val callbacks = RecordingTransferAbortCallbacks()
            val support =
                transferAbortSupport(
                    state = state,
                    outboundTransferLifecycleSupport =
                        outboundTransferLifecycleSupport(outboundTransfers),
                    callbacks = callbacks,
                )
            val reasonCode = TransferAbortReasonCode.RUNTIME_STOPPED

            // Act
            support.abortLocalTransfers(reasonCode)

            // Assert
            assertTrue(outboundTransfers.isEmpty())
            assertTrue(state.inboundTransfers().isEmpty())
            assertTrue(state.relayTransfers().isEmpty())
            assertEquals(
                listOf(
                    ClearedOutboundFrames(
                        peerId = outboundSession.destinationPeerId,
                        action = "transfer.clearQueuedFramesOnAbort",
                    )
                ),
                callbacks.clearedOutboundFrames,
            )
            assertEquals(
                listOf(
                    RecordedAbortFrame(
                        peerId = inboundSession.upstreamPeerId,
                        action = "transfer.abort.runtimeStop",
                        transferId = inboundSession.transferId,
                        reasonCode = reasonCode.code,
                    ),
                    RecordedAbortFrame(
                        peerId = relaySession.upstreamPeerId,
                        action = "transfer.abort.runtimeStop.upstream",
                        transferId = relaySession.transferId,
                        reasonCode = reasonCode.code,
                    ),
                ),
                callbacks.encryptedFrames,
            )
            assertEquals(
                listOf(
                    RecordedAbortFrame(
                        peerId = outboundSession.destinationPeerId,
                        action = "transfer.abort.runtimeStop",
                        transferId = outboundSession.transferId,
                        reasonCode = reasonCode.code,
                    ),
                    RecordedAbortFrame(
                        peerId = relaySession.destinationPeerId,
                        action = "transfer.abort.runtimeStop.downstream",
                        transferId = relaySession.transferId,
                        reasonCode = reasonCode.code,
                    ),
                ),
                callbacks.routedFrames,
            )
            assertEquals(
                listOf("outbound", "inbound", "relay"),
                callbacks.diagnostics.map { diagnostic ->
                    diagnostic.metadata["transferAbortScope"]
                },
            )
            assertTrue(
                callbacks.diagnostics.all { diagnostic ->
                    diagnostic.code == DiagnosticCode.TRANSFER_FAILED &&
                        diagnostic.severity == DiagnosticSeverity.ERROR &&
                        diagnostic.stage == "transfer.abort.runtimeStop" &&
                        diagnostic.reason == DiagnosticReason.TRANSFER_FAILURE &&
                        diagnostic.metadata["reasonCode"] == reasonCode.code.toString() &&
                        diagnostic.metadata["transferAbortReason"] == reasonCode.name &&
                        diagnostic.metadata["route"] == "available"
                }
            )
        }

    @Test
    fun `abortLocalTransfers is a no-op when there are no active transfers`() =
        runBlocking<Unit> {
            // Arrange
            val state =
                MeshEngineTransferState(
                    inboundTransfers = mutableMapOf(),
                    relayTransfers = mutableMapOf(),
                )
            val callbacks = RecordingTransferAbortCallbacks()
            val support =
                transferAbortSupport(
                    state = state,
                    outboundTransferLifecycleSupport = outboundTransferLifecycleSupport(),
                    callbacks = callbacks,
                )

            // Act
            support.abortLocalTransfers(TransferAbortReasonCode.RUNTIME_STOPPED)

            // Assert
            assertTrue(callbacks.clearedOutboundFrames.isEmpty())
            assertTrue(callbacks.encryptedFrames.isEmpty())
            assertTrue(callbacks.routedFrames.isEmpty())
            assertTrue(callbacks.diagnostics.isEmpty())
        }
}

private fun transferAbortSupport(
    state: MeshEngineTransferState,
    outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport,
    callbacks: RecordingTransferAbortCallbacks,
): MeshEngineTransferAbortSupport {
    return MeshEngineTransferAbortSupport(
        state = state,
        outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
        callbacks =
            MeshEngineTransferAbortCallbacks(
                sendEncryptedWireFrame = { peerId, frame, action, _ ->
                    val abortFrame = frame as WireFrame.TransferAbort
                    callbacks.encryptedFrames +=
                        RecordedAbortFrame(
                            peerId = peerId,
                            action = action,
                            transferId = abortFrame.transferId,
                            reasonCode = abortFrame.reasonCode,
                        )
                    true
                },
                sendTransferTowardsDestination = { peerId, frame, action, _ ->
                    val abortFrame = frame as WireFrame.TransferAbort
                    callbacks.routedFrames +=
                        RecordedAbortFrame(
                            peerId = peerId,
                            action = action,
                            transferId = abortFrame.transferId,
                            reasonCode = abortFrame.reasonCode,
                        )
                    true
                },
                clearQueuedOutboundFrames = { peerId, action ->
                    callbacks.clearedOutboundFrames +=
                        ClearedOutboundFrames(peerId = peerId, action = action)
                },
                routeMetadata = { _, metadata -> metadata + ("route" to "available") },
            ),
        emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
            callbacks.diagnostics +=
                RecordingTransferAbortDiagnostic(
                    code = code,
                    severity = severity,
                    stage = stage,
                    peerSuffix = peerSuffix,
                    reason = reason,
                    metadata = metadata,
                )
        },
    )
}

private fun outboundTransferLifecycleSupport(
    outboundTransfers: MutableMap<String, OutboundTransferSession> = mutableMapOf()
): MeshEngineOutboundTransferLifecycleSupport {
    return MeshEngineOutboundTransferLifecycleSupport(
        state = MeshEngineOutboundTransferLifecycleState(outboundTransfers = outboundTransfers),
        dependencies =
            MeshEngineOutboundTransferLifecycleDependencies(
                prepareOutboundTransferSession = { _, _, _ ->
                    OutboundTransferPreparation.Failed(
                        ch.trancee.meshlink.api.SendResult.NotSent(
                            ch.trancee.meshlink.api.SendFailureReason.UNREACHABLE
                        )
                    )
                },
                scheduleRetryDiagnostic = { _, _ -> },
            ),
    )
}

private fun newOutboundSession(
    transferId: String,
    destinationPeerId: PeerId,
): OutboundTransferSession {
    val chunks = listOf(byteArrayOf(0), byteArrayOf(1))
    return OutboundTransferSession(
        route = transferRoute(transferId = transferId, destinationPeerId = destinationPeerId),
        chunkPlan =
            TransferChunkPlan(
                chunks = chunks,
                totalBytes = chunks.sumOf { chunk -> chunk.size },
                maxChunkPayloadBytes = 1,
            ),
    )
}

private fun newInboundSession(transferId: String, upstreamPeerId: PeerId): InboundTransferSession {
    return InboundTransferSession(
        startDescriptor =
            TransferStartDescriptor(
                route = transferRoute(transferId = transferId, destinationPeerId = PeerId("self")),
                totalBytes = 2,
                totalChunks = 2,
                maxChunkPayloadBytes = 1,
            ),
        upstreamPeerId = upstreamPeerId,
        hardRunToken = MeshEngineHardRunToken(epoch = 2),
    )
}

private fun newRelaySession(
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

private fun transferRoute(transferId: String, destinationPeerId: PeerId): TransferSessionRoute {
    return TransferSessionRoute(
        transferId = transferId,
        messageId = "message-$transferId",
        originPeerId = PeerId("origin"),
        destinationPeerId = destinationPeerId,
    )
}

private data class RecordedAbortFrame(
    val peerId: PeerId,
    val action: String,
    val transferId: String,
    val reasonCode: Int,
)

private data class ClearedOutboundFrames(val peerId: PeerId, val action: String)

private data class RecordingTransferAbortDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)

private class RecordingTransferAbortCallbacks {
    val clearedOutboundFrames: MutableList<ClearedOutboundFrames> = mutableListOf()
    val encryptedFrames: MutableList<RecordedAbortFrame> = mutableListOf()
    val routedFrames: MutableList<RecordedAbortFrame> = mutableListOf()
    val diagnostics: MutableList<RecordingTransferAbortDiagnostic> = mutableListOf()
}
