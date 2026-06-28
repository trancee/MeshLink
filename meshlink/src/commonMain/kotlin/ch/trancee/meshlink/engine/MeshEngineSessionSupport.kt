package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal data class MeshEngineSessionState(
    val sessionRegistry: MeshEngineSessionRegistry,
    val runtimeGate: MeshEngineRuntimeGate,
)

internal data class MeshEngineSessionCallbacks(
    val hasTransport: () -> Boolean,
    val sendDirectWireFrame:
        suspend (
            PeerId, DirectWireFrame, String, ch.trancee.meshlink.transport.TransportMode?,
        ) -> TransportSendResult,
    val emitHopSessionFailed: (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
)

internal class MeshEngineSessionSupport(
    private val localIdentity: LocalIdentity,
    private val state: MeshEngineSessionState,
    private val handshakeTimeout: Duration,
    private val callbacks: MeshEngineSessionCallbacks,
) {
    suspend fun establishedHopSession(
        peerId: PeerId,
        hardRunToken: MeshEngineHardRunToken? = null,
    ): HopSession? {
        return (ensureHopSession(peerId, hardRunToken) as? SessionEstablishmentOutcome.Established)
            ?.session
    }

    suspend fun ensureHopSession(
        peerId: PeerId,
        hardRunToken: MeshEngineHardRunToken? = null,
    ): SessionEstablishmentOutcome {
        val currentReservation = state.sessionRegistry.initiatorHandshakeReservation(peerId)
        val reservation =
            when {
                currentReservation != null -> currentReservation
                !callbacks.hasTransport() -> null
                else -> reserveInitiatorHandshake(peerId)
            }

        return reservation?.awaitOrReturn(peerId, hardRunToken)
            ?: SessionEstablishmentOutcome.Unreachable
    }

    private suspend fun reserveInitiatorHandshake(peerId: PeerId): InitiatorHandshakeReservation {
        return state.sessionRegistry.initiatorHandshakeReservation(peerId) {
            val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val message1 = manager.createMessage1()
            val pendingHandshake =
                PendingInitiatorHandshake(
                    manager = manager,
                    sessionDeferred = CompletableDeferred(),
                )
            CreatedInitiatorHandshake(pendingHandshake = pendingHandshake, message1 = message1)
        } ?: error("Initiator reservation must create or reuse a session state")
    }

    private suspend fun initiateReservedHopSession(
        peerId: PeerId,
        reservation: InitiatorHandshakeReservation.Created,
        hardRunToken: MeshEngineHardRunToken?,
    ): SessionEstablishmentOutcome {
        return when (
            sendHandshakeMessage1(
                peerId = peerId,
                message1 = reservation.message1,
                hardRunToken = hardRunToken,
            )
        ) {
            TransportSendResult.Delivered ->
                awaitSessionEstablishment(peerId, reservation.pendingHandshake, hardRunToken)
            is TransportSendResult.Dropped -> {
                failPendingInitiatorHandshake(
                    peerId = peerId,
                    pendingHandshake = reservation.pendingHandshake,
                    stage = "transport.handshake.message1.send",
                )
            }
        }
    }

    private suspend fun sendHandshakeMessage1(
        peerId: PeerId,
        message1: ByteArray,
        hardRunToken: MeshEngineHardRunToken?,
    ): TransportSendResult {
        val retryWindow = TimeSource.Monotonic.markNow()
        while (true) {
            val result =
                callbacks.sendDirectWireFrame(
                    peerId,
                    DirectWireFrame.HandshakeMessage1(message1),
                    "handshake.message1",
                    TransportMode.GATT,
                )
            if (result !is TransportSendResult.Dropped || !result.isTransientLinkNotReady()) {
                return result
            }
            if (retryWindow.elapsedNow() >= handshakeTimeout) {
                return result
            }
            if (hardRunToken == null) {
                delay(HANDSHAKE_MESSAGE1_RETRY_DELAY)
            } else {
                when (
                    waitWithRuntimeGate(
                        runtimeGate = state.runtimeGate,
                        hardRunToken = hardRunToken,
                        maximumActiveWait = HANDSHAKE_MESSAGE1_RETRY_DELAY,
                        awaitChange = { activeWait ->
                            withTimeoutOrNull(activeWait) { delay(activeWait) }
                        },
                    )
                ) {
                    is MeshEngineRuntimeTimedWaitResult.Completed,
                    MeshEngineRuntimeTimedWaitResult.TimedOut -> {}
                    MeshEngineRuntimeTimedWaitResult.HardRunEnded -> {
                        return TransportSendResult.Dropped("MeshLink hard run ended")
                    }
                }
            }
        }
    }

    private suspend fun awaitSessionEstablishment(
        peerId: PeerId,
        pendingHandshake: PendingInitiatorHandshake,
        hardRunToken: MeshEngineHardRunToken?,
    ): SessionEstablishmentOutcome {
        if (hardRunToken == null) {
            return withTimeoutOrNull(handshakeTimeout) { pendingHandshake.sessionDeferred.await() }
                ?: failPendingInitiatorHandshake(
                    peerId = peerId,
                    pendingHandshake = pendingHandshake,
                    stage = "transport.handshake.timeout",
                )
        }

        return when (
            val waitResult =
                waitWithRuntimeGate(
                    runtimeGate = state.runtimeGate,
                    hardRunToken = hardRunToken,
                    maximumActiveWait = handshakeTimeout,
                    awaitChange = { activeWait ->
                        withTimeoutOrNull(activeWait) { pendingHandshake.sessionDeferred.await() }
                    },
                )
        ) {
            is MeshEngineRuntimeTimedWaitResult.Completed -> waitResult.value
            MeshEngineRuntimeTimedWaitResult.TimedOut ->
                failPendingInitiatorHandshake(
                    peerId = peerId,
                    pendingHandshake = pendingHandshake,
                    stage = "transport.handshake.timeout",
                )
            MeshEngineRuntimeTimedWaitResult.HardRunEnded ->
                pendingHandshake.sessionDeferred.completedOutcomeOr(
                    fallback = SessionEstablishmentOutcome.Unreachable
                )
        }
    }

    private suspend fun failPendingInitiatorHandshake(
        peerId: PeerId,
        pendingHandshake: PendingInitiatorHandshake,
        stage: String,
    ): SessionEstablishmentOutcome {
        val removed = state.sessionRegistry.failInitiatorHandshake(peerId, pendingHandshake)
        if (removed) {
            pendingHandshake.sessionDeferred.complete(SessionEstablishmentOutcome.Unreachable)
            callbacks.emitHopSessionFailed(
                peerId,
                stage,
                DiagnosticReason.DELIVERY_FAILURE,
                mapOf("peerId" to peerId.value, "pendingHandshake" to "initiator"),
            )
        }
        return pendingHandshake.sessionDeferred.completedOutcomeOr(
            fallback = SessionEstablishmentOutcome.Unreachable
        )
    }

    private suspend fun InitiatorHandshakeReservation.awaitOrReturn(
        peerId: PeerId,
        hardRunToken: MeshEngineHardRunToken?,
    ): SessionEstablishmentOutcome {
        return when (this) {
            is InitiatorHandshakeReservation.Established ->
                SessionEstablishmentOutcome.Established(session)
            is InitiatorHandshakeReservation.Pending ->
                awaitSessionEstablishment(peerId, pendingHandshake, hardRunToken)
            is InitiatorHandshakeReservation.Created ->
                initiateReservedHopSession(peerId, this, hardRunToken)
        }
    }
}

internal fun buildMeshEngineRuntimeSessionSupport(
    localIdentity: LocalIdentity,
    sessionRegistry: MeshEngineSessionRegistry,
    runtimeGate: MeshEngineRuntimeGate,
    hasTransport: () -> Boolean,
    sendDirectWireFrame:
        suspend (
            PeerId, DirectWireFrame, String, ch.trancee.meshlink.transport.TransportMode?,
        ) -> TransportSendResult,
    emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
    peerRouteMetadata: (PeerId, Map<String, String>) -> Map<String, String>,
): MeshEngineSessionSupport {
    return MeshEngineSessionSupport(
        localIdentity = localIdentity,
        state =
            MeshEngineSessionState(sessionRegistry = sessionRegistry, runtimeGate = runtimeGate),
        handshakeTimeout = HANDSHAKE_TIMEOUT,
        callbacks =
            MeshEngineSessionCallbacks(
                hasTransport = hasTransport,
                sendDirectWireFrame = sendDirectWireFrame,
                emitHopSessionFailed = { peerId, stage, reason, metadata ->
                    emitDiagnostic(
                        DiagnosticCode.HOP_SESSION_FAILED,
                        DiagnosticSeverity.WARN,
                        stage,
                        peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                        reason,
                        peerRouteMetadata(peerId, metadata),
                    )
                },
            ),
    )
}

private suspend fun CompletableDeferred<SessionEstablishmentOutcome>.completedOutcomeOr(
    fallback: SessionEstablishmentOutcome
): SessionEstablishmentOutcome {
    return if (isCompleted) {
        await()
    } else {
        fallback
    }
}

private fun TransportSendResult.Dropped.isTransientLinkNotReady(): Boolean {
    return reason.contains("L2CAP connection is not ready")
}

private val HANDSHAKE_TIMEOUT = 3.seconds
private val HANDSHAKE_MESSAGE1_RETRY_DELAY = 100.milliseconds
