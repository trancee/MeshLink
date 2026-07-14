package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeAwaitActiveResult
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeGate
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeInterruption
import ch.trancee.meshlink.engine.internal.HopSession
import ch.trancee.meshlink.engine.internal.SessionEstablishmentOutcome
import ch.trancee.meshlink.engine.lifecycle.MeshEngineDiscoverySuspensionSupport
import ch.trancee.meshlink.engine.routing.MeshEngineRoutingSupport
import ch.trancee.meshlink.engine.transfer.MeshEngineInlineDispatchCallbacks
import ch.trancee.meshlink.engine.transfer.MeshEngineInlineDispatchDependencies
import ch.trancee.meshlink.engine.transfer.MeshEngineInlineDispatchRoutingContext
import ch.trancee.meshlink.engine.transfer.MeshEngineInlineDispatchSupport
import ch.trancee.meshlink.engine.transfer.MeshEngineInlineOutboundDeliveryAdapter
import ch.trancee.meshlink.engine.transfer.MeshEngineInlineOutboundDeliveryAdapterCallbacks
import ch.trancee.meshlink.engine.transfer.MeshEngineInlineOutboundDeliveryAdapterConfig
import ch.trancee.meshlink.engine.transfer.MeshEngineInlineOutboundDeliveryAdapterDependencies
import ch.trancee.meshlink.engine.transfer.MeshEngineOutboundDeliveryAttemptContext
import ch.trancee.meshlink.engine.transfer.MeshEngineOutboundDeliveryAttemptOutcome
import ch.trancee.meshlink.engine.transfer.MeshEngineOutboundInlineMessagePreparation
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class MeshEngineInlineOutboundDeliveryAdapterTest {
    @Test
    fun `attemptOutboundDelivery returns completed sent when dispatch delivers`() =
        runBlocking<Unit> {
            // Arrange
            val adapter =
                inlineOutboundDeliveryAdapter(
                    prepareOutboundInlineMessage = { peerId, _, priority, ttlMillis ->
                        MeshEngineOutboundInlineMessagePreparation.Ready(
                            WireFrame.Message(
                                messageId = "message-1",
                                originPeerId = PeerId("origin-abcdef"),
                                destinationPeerId = peerId,
                                priority = priority,
                                ttlMillis = ttlMillis,
                                encryptedPayload = byteArrayOf(0x01),
                            )
                        )
                    },
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
            val context = inlineAttemptContext(payload = ByteArray(32) { 0x01 })

            // Act
            val outcome = adapter.attemptOutboundDelivery(Unit, context)

            // Assert
            val completed = assertIs<MeshEngineOutboundDeliveryAttemptOutcome.Completed>(outcome)
            assertEquals(SendResult.Sent, completed.result)
        }

    @Test
    fun `attemptOutboundDelivery returns completed trust failure when preparation reports missing trust`() =
        runBlocking<Unit> {
            // Arrange
            val diagnostics = mutableListOf<RecordedInlineOutboundDeliveryDiagnostic>()
            val adapter =
                inlineOutboundDeliveryAdapter(
                    diagnostics = diagnostics,
                    prepareOutboundInlineMessage = { _, _, _, _ ->
                        MeshEngineOutboundInlineMessagePreparation.MissingTrust
                    },
                    ensureHopSession = { _, _ ->
                        SessionEstablishmentOutcome.Established(
                            HopSession(
                                sendKey = ByteArray(32) { 0x01 },
                                receiveKey = ByteArray(32) { 0x02 },
                            )
                        )
                    },
                )
            val context = inlineAttemptContext(payload = ByteArray(32) { 0x01 })

            // Act
            val outcome = adapter.attemptOutboundDelivery(Unit, context)

            // Assert
            val completed = assertIs<MeshEngineOutboundDeliveryAttemptOutcome.Completed>(outcome)
            val notSent = assertIs<SendResult.NotSent>(completed.result)
            assertEquals(SendFailureReason.TRUST_FAILURE, notSent.reason)
            assertEquals(
                listOf(DiagnosticCode.TRUST_FAILURE to "delivery.send.noTrust"),
                diagnostics.map { it.code to it.stage },
            )
        }

    @Test
    fun `attemptOutboundDelivery returns await retry when dispatch requests retry`() =
        runBlocking<Unit> {
            // Arrange
            val retryDiagnostics = mutableListOf<Pair<PeerId, DeliveryPriority>>()
            val adapter =
                inlineOutboundDeliveryAdapter(
                    retryDiagnostics = retryDiagnostics,
                    prepareOutboundInlineMessage = { peerId, _, priority, ttlMillis ->
                        MeshEngineOutboundInlineMessagePreparation.Ready(
                            WireFrame.Message(
                                messageId = "message-1",
                                originPeerId = PeerId("origin-abcdef"),
                                destinationPeerId = peerId,
                                priority = priority,
                                ttlMillis = ttlMillis,
                                encryptedPayload = byteArrayOf(0x01),
                            )
                        )
                    },
                    ensureHopSession = { _, _ -> SessionEstablishmentOutcome.Unreachable },
                )
            val context = inlineAttemptContext(payload = ByteArray(32) { 0x01 })

            // Act
            val outcome = adapter.attemptOutboundDelivery(Unit, context)

            // Assert
            val awaitRetry =
                assertIs<MeshEngineOutboundDeliveryAttemptOutcome.AwaitRetry<Unit>>(outcome)
            assertEquals(listOf(context.peerId to context.priority), retryDiagnostics)
            assertEquals("delivery.retryScheduled", awaitRetry.retryPolicy.profile.scheduledStage)
            assertEquals("delivery.retrying", awaitRetry.retryPolicy.profile.retryingStage)
            assertEquals(true, awaitRetry.retryPolicy.emitDiagnostics)
        }

    @Test
    fun `withDiscoveryPolicy suspends discovery for payloads above the inline threshold`() =
        runBlocking<Unit> {
            // Arrange
            val discoveryTransitions = mutableListOf<Boolean>()
            val adapter = inlineOutboundDeliveryAdapter(discoveryTransitions = discoveryTransitions)
            val context = inlineAttemptContext(payload = ByteArray(1025) { 0x01 })

            // Act
            val result = adapter.withDiscoveryPolicy(context) { "done" }

            // Assert
            assertEquals("done", result)
            assertEquals(listOf(true, false), discoveryTransitions)
        }
}

private fun inlineOutboundDeliveryAdapter(
    diagnostics: MutableList<RecordedInlineOutboundDeliveryDiagnostic> = mutableListOf(),
    discoveryTransitions: MutableList<Boolean> = mutableListOf(),
    retryDiagnostics: MutableList<Pair<PeerId, DeliveryPriority>> = mutableListOf(),
    prepareOutboundInlineMessage:
        suspend (
            PeerId, ByteArray, DeliveryPriority, Int,
        ) -> MeshEngineOutboundInlineMessagePreparation =
        { _, _, _, _ ->
            MeshEngineOutboundInlineMessagePreparation.MissingTrust
        },
    ensureHopSession: suspend (PeerId, MeshEngineHardRunToken) -> SessionEstablishmentOutcome =
        { _, _ ->
            SessionEstablishmentOutcome.TrustFailure
        },
    sendEncryptedDirectWireFrame:
        suspend (PeerId, HopSession, WireFrame, String) -> TransportSendResult =
        { _, _, _, _ ->
            TransportSendResult.Delivered
        },
): MeshEngineInlineOutboundDeliveryAdapter {
    return MeshEngineInlineOutboundDeliveryAdapter(
        config =
            MeshEngineInlineOutboundDeliveryAdapterConfig(
                inlineMessagePayloadBytes = INLINE_OUTBOUND_DELIVERY_THRESHOLD_BYTES
            ),
        dependencies =
            MeshEngineInlineOutboundDeliveryAdapterDependencies(
                discoverySuspensionSupport =
                    MeshEngineDiscoverySuspensionSupport { suspended ->
                        discoveryTransitions += suspended
                    },
                prepareOutboundInlineMessage = prepareOutboundInlineMessage,
                dispatchSupport =
                    MeshEngineInlineDispatchSupport(
                        routingContext =
                            MeshEngineInlineDispatchRoutingContext(
                                routeCoordinator = RouteCoordinator(PeerId("local-inline-adapter")),
                                routingSupport = inlineRoutingSupport(diagnostics),
                            ),
                        dependencies =
                            MeshEngineInlineDispatchDependencies(
                                ensureHopSession = ensureHopSession,
                                sendEncryptedDirectWireFrame = sendEncryptedDirectWireFrame,
                                scheduleRetryDiagnostic = { peerId, priority ->
                                    retryDiagnostics += peerId to priority
                                },
                                emitHopSessionFailed = { _, _, _, _ -> },
                            ),
                        callbacks =
                            MeshEngineInlineDispatchCallbacks {
                                code,
                                severity,
                                stage,
                                peerSuffix,
                                reason,
                                metadata ->
                                diagnostics +=
                                    RecordedInlineOutboundDeliveryDiagnostic(
                                        code = code,
                                        severity = severity,
                                        stage = stage,
                                        peerSuffix = peerSuffix,
                                        reason = reason,
                                        metadata = metadata,
                                    )
                            },
                    ),
            ),
        callbacks =
            MeshEngineInlineOutboundDeliveryAdapterCallbacks(
                emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                    diagnostics +=
                        RecordedInlineOutboundDeliveryDiagnostic(
                            code = code,
                            severity = severity,
                            stage = stage,
                            peerSuffix = peerSuffix,
                            reason = reason,
                            metadata = metadata,
                        )
                },
                ttlMillisFor = { 1234 },
            ),
    )
}

private fun inlineAttemptContext(
    payload: ByteArray,
    peerId: PeerId = PeerId("peer-abcdef"),
): MeshEngineOutboundDeliveryAttemptContext {
    return MeshEngineOutboundDeliveryAttemptContext(
        peerId = peerId,
        payload = payload,
        priority = DeliveryPriority.NORMAL,
        hardRunToken = MeshEngineHardRunToken(epoch = 5L),
        remainingBudget = 250.milliseconds,
    )
}

private fun inlineRoutingSupport(
    diagnostics: MutableList<RecordedInlineOutboundDeliveryDiagnostic>
): MeshEngineRoutingSupport {
    return MeshEngineRoutingSupport(
        routeCoordinator = RouteCoordinator(PeerId("local-routing-inline")),
        runtimeGate = InlineAdapterRuntimeGate(),
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
            diagnostics +=
                RecordedInlineOutboundDeliveryDiagnostic(
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

private data class RecordedInlineOutboundDeliveryDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)

private class InlineAdapterRuntimeGate : MeshEngineRuntimeGate {
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

private const val INLINE_OUTBOUND_DELIVERY_THRESHOLD_BYTES: Int = 1_024
