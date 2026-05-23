package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult
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
    fun `attemptOutboundDelivery returns completed sent when the transport delivers`() =
        runBlocking {
            // Arrange
            val adapter =
                inlineOutboundDeliveryAdapter(
                    ensureHopSession = { _, _ ->
                        SessionEstablishmentOutcome.Established(
                            HopSession(
                                sendKey = ByteArray(32) { 0x01 },
                                receiveKey = ByteArray(32) { 0x02 },
                            )
                        )
                    },
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
    fun `attemptOutboundDelivery returns await retry when no hop session can be established`() =
        runBlocking {
            // Arrange
            val retryDiagnostics = mutableListOf<Pair<PeerId, DeliveryPriority>>()
            val adapter =
                inlineOutboundDeliveryAdapter(
                    scheduleRetryDiagnostic = { peerId, priority ->
                        retryDiagnostics += peerId to priority
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
        runBlocking {
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
    discoveryTransitions: MutableList<Boolean> = mutableListOf(),
    scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit = { _, _ -> },
    ensureHopSession: suspend (PeerId, MeshEngineHardRunToken) -> SessionEstablishmentOutcome =
        { _, _ ->
            SessionEstablishmentOutcome.TrustFailure
        },
    prepareOutboundInlineMessage:
        suspend (
            PeerId, ByteArray, DeliveryPriority, Int,
        ) -> MeshEngineOutboundInlineMessagePreparation =
        { _, _, _, _ ->
            MeshEngineOutboundInlineMessagePreparation.MissingTrust
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
        routingContext =
            MeshEngineInlineOutboundDeliveryAdapterRoutingContext(
                routeCoordinator = RouteCoordinator(PeerId("local-inline-adapter")),
                routingSupport = inlineRoutingSupport(),
            ),
        dependencies =
            MeshEngineInlineOutboundDeliveryAdapterDependencies(
                discoverySuspensionSupport =
                    MeshEngineDiscoverySuspensionSupport { suspended ->
                        discoveryTransitions += suspended
                    },
                ensureHopSession = ensureHopSession,
                sendEncryptedDirectWireFrame = sendEncryptedDirectWireFrame,
                prepareOutboundInlineMessage = prepareOutboundInlineMessage,
                scheduleRetryDiagnostic = scheduleRetryDiagnostic,
                emitHopSessionFailed = { _, _, _, _ -> },
            ),
        callbacks =
            MeshEngineInlineOutboundDeliveryAdapterCallbacks(
                emitDiagnostic = { _, _, _, _, _, _ -> },
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

private fun inlineRoutingSupport(): MeshEngineRoutingSupport {
    return MeshEngineRoutingSupport(
        routeCoordinator = RouteCoordinator(PeerId("local-routing-inline")),
        runtimeGate = InlineAdapterRuntimeGate(),
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        emitDiagnostic = { _, _, _, _, _, _ -> },
        sendEncryptedWireFrame = { _, _, _, _ -> true },
    )
}

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
