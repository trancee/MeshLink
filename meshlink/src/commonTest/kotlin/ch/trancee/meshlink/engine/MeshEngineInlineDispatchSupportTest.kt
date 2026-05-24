package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class MeshEngineInlineDispatchSupportTest {
    @Test
    fun `resolveDispatch returns ready when the hop session is established`() = runBlocking {
        // Arrange
        val support =
            inlineDispatchSupport(
                ensureHopSession = { _, _ ->
                    SessionEstablishmentOutcome.Established(
                        HopSession(
                            sendKey = ByteArray(32) { 0x01 },
                            receiveKey = ByteArray(32) { 0x02 },
                        )
                    )
                }
            )

        // Act
        val resolution =
            support.resolveDispatch(
                peerId = PeerId("peer-abcdef"),
                priority = DeliveryPriority.HIGH,
                hardRunToken = MeshEngineHardRunToken(epoch = 5L),
            )

        // Assert
        assertIs<MeshEngineInlineDispatchResolution.Ready>(resolution)
        Unit
    }

    @Test
    fun `resolveDispatch returns await retry when no hop session can be established`() =
        runBlocking {
            // Arrange
            val retryDiagnostics = mutableListOf<Pair<PeerId, DeliveryPriority>>()
            val support =
                inlineDispatchSupport(
                    retryDiagnostics = retryDiagnostics,
                    ensureHopSession = { _, _ -> SessionEstablishmentOutcome.Unreachable },
                )
            val peerId = PeerId("peer-abcdef")

            // Act
            val resolution =
                support.resolveDispatch(
                    peerId = peerId,
                    priority = DeliveryPriority.NORMAL,
                    hardRunToken = MeshEngineHardRunToken(epoch = 5L),
                )

            // Assert
            assertEquals(MeshEngineInlineDispatchResolution.AwaitRetry, resolution)
            assertEquals(listOf(peerId to DeliveryPriority.NORMAL), retryDiagnostics)
        }

    @Test
    fun `resolveDispatch returns trust failure when hop session establishment reports trust failure`() =
        runBlocking {
            // Arrange
            val support =
                inlineDispatchSupport(
                    ensureHopSession = { _, _ -> SessionEstablishmentOutcome.TrustFailure }
                )

            // Act
            val resolution =
                support.resolveDispatch(
                    peerId = PeerId("peer-abcdef"),
                    priority = DeliveryPriority.NORMAL,
                    hardRunToken = MeshEngineHardRunToken(epoch = 5L),
                )

            // Assert
            assertEquals(MeshEngineInlineDispatchResolution.TrustFailure, resolution)
        }

    @Test
    fun `dispatchPreparedMessage returns delivered when the transport delivers`() = runBlocking {
        // Arrange
        val diagnostics = mutableListOf<RecordedInlineDispatchDiagnostic>()
        val support =
            inlineDispatchSupport(
                diagnostics = diagnostics,
                ensureHopSession = { _, _ ->
                    SessionEstablishmentOutcome.Established(
                        HopSession(
                            sendKey = ByteArray(32) { 0x01 },
                            receiveKey = ByteArray(32) { 0x02 },
                        )
                    )
                },
                sendEncryptedDirectWireFrame = { _, _, _, _ -> TransportSendResult.Delivered },
            )
        val resolution =
            assertIs<MeshEngineInlineDispatchResolution.Ready>(
                support.resolveDispatch(
                    peerId = PeerId("peer-abcdef"),
                    priority = DeliveryPriority.HIGH,
                    hardRunToken = MeshEngineHardRunToken(epoch = 5L),
                )
            )

        // Act
        val result =
            support.dispatchPreparedMessage(
                peerId = PeerId("peer-abcdef"),
                priority = DeliveryPriority.HIGH,
                routedMessage = inlineMessage(PeerId("peer-abcdef"), DeliveryPriority.HIGH),
                resolution = resolution,
            )

        // Assert
        assertEquals(MeshEngineInlineDispatchResult.Delivered, result)
        assertEquals(
            listOf(DiagnosticCode.DELIVERY_SUCCEEDED to "delivery.send"),
            diagnostics.map { it.code to it.stage },
        )
    }

    @Test
    fun `dispatchPreparedMessage returns await retry when transport encryption throws`() =
        runBlocking {
            // Arrange
            val hopSessionFailures = mutableListOf<RecordedHopSessionFailure>()
            val support =
                inlineDispatchSupport(
                    hopSessionFailures = hopSessionFailures,
                    ensureHopSession = { _, _ ->
                        SessionEstablishmentOutcome.Established(
                            HopSession(
                                sendKey = ByteArray(32) { 0x01 },
                                receiveKey = ByteArray(32) { 0x02 },
                            )
                        )
                    },
                    sendEncryptedDirectWireFrame = { _, _, _, _ -> error("boom") },
                )
            val resolution =
                assertIs<MeshEngineInlineDispatchResolution.Ready>(
                    support.resolveDispatch(
                        peerId = PeerId("peer-abcdef"),
                        priority = DeliveryPriority.NORMAL,
                        hardRunToken = MeshEngineHardRunToken(epoch = 5L),
                    )
                )

            // Act
            val result =
                support.dispatchPreparedMessage(
                    peerId = PeerId("peer-abcdef"),
                    priority = DeliveryPriority.NORMAL,
                    routedMessage = inlineMessage(PeerId("peer-abcdef"), DeliveryPriority.NORMAL),
                    resolution = resolution,
                )

            // Assert
            assertEquals(MeshEngineInlineDispatchResult.AwaitRetry, result)
            assertEquals(1, hopSessionFailures.size)
            assertEquals("delivery.send.transportEncrypt", hopSessionFailures.single().stage)
            assertEquals(DiagnosticReason.DELIVERY_FAILURE, hopSessionFailures.single().reason)
            assertEquals("IllegalStateException", hopSessionFailures.single().metadata["cause"])
        }
}

private fun inlineDispatchSupport(
    diagnostics: MutableList<RecordedInlineDispatchDiagnostic> = mutableListOf(),
    retryDiagnostics: MutableList<Pair<PeerId, DeliveryPriority>> = mutableListOf(),
    hopSessionFailures: MutableList<RecordedHopSessionFailure> = mutableListOf(),
    ensureHopSession: suspend (PeerId, MeshEngineHardRunToken) -> SessionEstablishmentOutcome =
        { _, _ ->
            SessionEstablishmentOutcome.TrustFailure
        },
    sendEncryptedDirectWireFrame:
        suspend (PeerId, HopSession, WireFrame, String) -> TransportSendResult =
        { _, _, _, _ ->
            TransportSendResult.Delivered
        },
): MeshEngineInlineDispatchSupport {
    return MeshEngineInlineDispatchSupport(
        routingContext =
            MeshEngineInlineDispatchRoutingContext(
                routeCoordinator = RouteCoordinator(PeerId("local-inline-dispatch")),
                routingSupport = inlineDispatchRoutingSupport(diagnostics),
            ),
        dependencies =
            MeshEngineInlineDispatchDependencies(
                ensureHopSession = ensureHopSession,
                sendEncryptedDirectWireFrame = sendEncryptedDirectWireFrame,
                scheduleRetryDiagnostic = { peerId, priority ->
                    retryDiagnostics += peerId to priority
                },
                emitHopSessionFailed = { peerId, stage, reason, metadata ->
                    hopSessionFailures +=
                        RecordedHopSessionFailure(
                            peerId = peerId,
                            stage = stage,
                            reason = reason,
                            metadata = metadata,
                        )
                },
            ),
        callbacks =
            MeshEngineInlineDispatchCallbacks { code, severity, stage, peerSuffix, reason, metadata
                ->
                diagnostics +=
                    RecordedInlineDispatchDiagnostic(
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

private fun inlineDispatchRoutingSupport(
    diagnostics: MutableList<RecordedInlineDispatchDiagnostic>
): MeshEngineRoutingSupport {
    return MeshEngineRoutingSupport(
        routeCoordinator = RouteCoordinator(PeerId("local-inline-routing")),
        runtimeGate = InlineDispatchRuntimeGate(),
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
            diagnostics +=
                RecordedInlineDispatchDiagnostic(
                    code = code,
                    severity = severity,
                    stage = stage,
                    peerSuffix = peerSuffix,
                    reason = reason,
                    metadata = metadata,
                )
        },
        sendEncryptedWireFrame = { _, _, _, _ -> true },
    )
}

private fun inlineMessage(peerId: PeerId, priority: DeliveryPriority): WireFrame.Message {
    return WireFrame.Message(
        messageId = "message-1",
        originPeerId = PeerId("origin-abcdef"),
        destinationPeerId = peerId,
        priority = priority,
        ttlMillis = 1_234,
        encryptedPayload = byteArrayOf(0x01),
    )
}

private data class RecordedInlineDispatchDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)

private data class RecordedHopSessionFailure(
    val peerId: PeerId,
    val stage: String,
    val reason: DiagnosticReason,
    val metadata: Map<String, String>,
)

private class InlineDispatchRuntimeGate : MeshEngineRuntimeGate {
    private val interruption = CompletableDeferred<MeshEngineRuntimeInterruption>()

    override fun currentState(): MeshLinkState = MeshLinkState.Running

    override fun currentHardRunEpoch(): Long = 5L

    override fun captureHardRunToken(): MeshEngineHardRunToken = MeshEngineHardRunToken(5L)

    override fun isAcceptingNewSends(): Boolean = true

    override fun isHardRunActive(token: MeshEngineHardRunToken): Boolean = token.epoch == 5L

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
