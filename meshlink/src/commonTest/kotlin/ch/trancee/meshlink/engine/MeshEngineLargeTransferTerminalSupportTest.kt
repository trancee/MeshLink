package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.TransferChunkPlan
import ch.trancee.meshlink.transfer.TransferSessionRoute
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class MeshEngineLargeTransferTerminalSupportTest {
    @Test
    fun `complete returns sent and clears frames and removes the session`() = runBlocking {
        // Arrange
        val diagnostics = mutableListOf<RecordedLargeTransferTerminalDiagnostic>()
        val clearedOutboundFrames = mutableListOf<Pair<PeerId, String>>()
        val sentFrames = mutableListOf<RecordedLargeTransferTerminalFrame>()
        val outboundTransfers = mutableMapOf<String, OutboundTransferSession>()
        val session = terminalOutboundTransferSession(PeerId("peer-abcdef"))
        outboundTransfers[session.transferId] = session
        val support =
            largeTransferTerminalSupport(
                diagnostics = diagnostics,
                clearedOutboundFrames = clearedOutboundFrames,
                sentFrames = sentFrames,
                outboundTransfers = outboundTransfers,
            )

        // Act
        val result = support.complete(session, MeshEngineHardRunToken(epoch = 7L))

        // Assert
        assertEquals(SendResult.Sent, result)
        assertEquals(
            listOf(session.destinationPeerId to "transfer.clearQueuedFrames"),
            clearedOutboundFrames,
        )
        assertEquals(1, sentFrames.size)
        assertEquals(session.destinationPeerId, sentFrames.single().peerId)
        assertEquals("transfer.complete", sentFrames.single().action)
        assertEquals(7L, sentFrames.single().hardRunEpoch)
        val transferComplete = assertIs<WireFrame.TransferComplete>(sentFrames.single().frame)
        assertEquals(session.transferId, transferComplete.transferId)
        assertEquals(emptyMap(), outboundTransfers)
        assertEquals(
            listOf(DiagnosticCode.TRANSFER_COMPLETED to "transfer.send.complete"),
            diagnostics.map { it.code to it.stage },
        )
    }

    @Test
    fun `fail returns unreachable when no route was available`() = runBlocking {
        // Arrange
        val diagnostics = mutableListOf<RecordedLargeTransferTerminalDiagnostic>()
        val clearedOutboundFrames = mutableListOf<Pair<PeerId, String>>()
        val outboundTransfers = mutableMapOf<String, OutboundTransferSession>()
        val session = terminalOutboundTransferSession(PeerId("peer-abcdef"))
        outboundTransfers[session.transferId] = session
        val support =
            largeTransferTerminalSupport(
                diagnostics = diagnostics,
                clearedOutboundFrames = clearedOutboundFrames,
                outboundTransfers = outboundTransfers,
            )

        // Act
        val result =
            support.fail(
                activeSession = session,
                peerId = session.destinationPeerId,
                lastRouteAvailable = false,
            )

        // Assert
        val notSent = assertIs<SendResult.NotSent>(result)
        assertEquals(SendFailureReason.UNREACHABLE, notSent.reason)
        assertEquals(
            listOf(session.destinationPeerId to "transfer.clearQueuedFramesOnFailure"),
            clearedOutboundFrames,
        )
        assertEquals(emptyMap(), outboundTransfers)
        assertEquals(
            listOf(DiagnosticCode.DELIVERY_UNREACHABLE to "transfer.retryExpired"),
            diagnostics.map { it.code to it.stage },
        )
    }

    @Test
    fun `fail returns transfer timed out when a route was previously available`() = runBlocking {
        // Arrange
        val diagnostics = mutableListOf<RecordedLargeTransferTerminalDiagnostic>()
        val clearedOutboundFrames = mutableListOf<Pair<PeerId, String>>()
        val outboundTransfers = mutableMapOf<String, OutboundTransferSession>()
        val session = terminalOutboundTransferSession(PeerId("peer-abcdef"))
        outboundTransfers[session.transferId] = session
        val support =
            largeTransferTerminalSupport(
                diagnostics = diagnostics,
                clearedOutboundFrames = clearedOutboundFrames,
                outboundTransfers = outboundTransfers,
            )

        // Act
        val result =
            support.fail(
                activeSession = session,
                peerId = session.destinationPeerId,
                lastRouteAvailable = true,
            )

        // Assert
        val notSent = assertIs<SendResult.NotSent>(result)
        assertEquals(SendFailureReason.TRANSFER_TIMED_OUT, notSent.reason)
        assertEquals(
            listOf(session.destinationPeerId to "transfer.clearQueuedFramesOnFailure"),
            clearedOutboundFrames,
        )
        assertEquals(emptyMap(), outboundTransfers)
        assertEquals(
            listOf(DiagnosticCode.TRANSFER_FAILED to "transfer.send.timeout"),
            diagnostics.map { it.code to it.stage },
        )
    }

    @Test
    fun `abort returns transfer aborted and clears frames and removes the session`() = runBlocking {
        // Arrange
        val clearedOutboundFrames = mutableListOf<Pair<PeerId, String>>()
        val outboundTransfers = mutableMapOf<String, OutboundTransferSession>()
        val session = terminalOutboundTransferSession(PeerId("peer-abcdef"))
        outboundTransfers[session.transferId] = session
        val support =
            largeTransferTerminalSupport(
                clearedOutboundFrames = clearedOutboundFrames,
                outboundTransfers = outboundTransfers,
            )

        // Act
        val result = support.abort(session)

        // Assert
        val notSent = assertIs<SendResult.NotSent>(result)
        assertEquals(SendFailureReason.TRANSFER_ABORTED, notSent.reason)
        assertEquals(
            listOf(session.destinationPeerId to "transfer.clearQueuedFramesOnAbort"),
            clearedOutboundFrames,
        )
        assertEquals(emptyMap(), outboundTransfers)
    }
}

private fun largeTransferTerminalSupport(
    diagnostics: MutableList<RecordedLargeTransferTerminalDiagnostic> = mutableListOf(),
    clearedOutboundFrames: MutableList<Pair<PeerId, String>> = mutableListOf(),
    sentFrames: MutableList<RecordedLargeTransferTerminalFrame> = mutableListOf(),
    outboundTransfers: MutableMap<String, OutboundTransferSession> = mutableMapOf(),
): MeshEngineLargeTransferTerminalSupport {
    return MeshEngineLargeTransferTerminalSupport(
        outboundTransferLifecycleSupport =
            MeshEngineOutboundTransferLifecycleSupport(
                state =
                    MeshEngineOutboundTransferLifecycleState(outboundTransfers = outboundTransfers),
                dependencies =
                    MeshEngineOutboundTransferLifecycleDependencies(
                        prepareOutboundTransferSession = { _, _, _ ->
                            OutboundTransferPreparation.Failed(
                                SendResult.NotSent(SendFailureReason.UNREACHABLE)
                            )
                        },
                        scheduleRetryDiagnostic = { _, _ -> },
                    ),
            ),
        dependencies =
            MeshEngineLargeTransferTerminalDependencies(
                clearQueuedOutboundFrames = { peerId, action ->
                    clearedOutboundFrames += peerId to action
                },
                sendTransferTowardsDestination = { peerId, frame, action, hardRunToken ->
                    sentFrames +=
                        RecordedLargeTransferTerminalFrame(
                            peerId = peerId,
                            frame = frame,
                            action = action,
                            hardRunEpoch = hardRunToken?.epoch,
                        )
                    true
                },
            ),
        callbacks =
            MeshEngineLargeTransferTerminalCallbacks(
                routeMetadata = { emptyMap() },
                emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                    diagnostics +=
                        RecordedLargeTransferTerminalDiagnostic(
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

private fun terminalOutboundTransferSession(destinationPeerId: PeerId): OutboundTransferSession {
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

private data class RecordedLargeTransferTerminalFrame(
    val peerId: PeerId,
    val frame: WireFrame,
    val action: String,
    val hardRunEpoch: Long?,
)

private data class RecordedLargeTransferTerminalDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)
