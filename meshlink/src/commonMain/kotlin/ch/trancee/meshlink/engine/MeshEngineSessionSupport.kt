package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal data class MeshEngineSessionState(val sessionRegistry: MeshEngineSessionRegistry)

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
    suspend fun establishedHopSession(peerId: PeerId): HopSession? {
        return (ensureHopSession(peerId) as? SessionEstablishmentOutcome.Established)?.session
    }

    suspend fun ensureHopSession(peerId: PeerId): SessionEstablishmentOutcome {
        val currentReservation = state.sessionRegistry.initiatorHandshakeReservation(peerId)
        val reservation =
            when {
                currentReservation != null -> currentReservation
                !callbacks.hasTransport() -> null
                else -> reserveInitiatorHandshake(peerId)
            }

        return reservation?.awaitOrReturn(peerId) ?: SessionEstablishmentOutcome.Unreachable
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
    ): SessionEstablishmentOutcome {
        return when (sendHandshakeMessage1(peerId = peerId, message1 = reservation.message1)) {
            TransportSendResult.Delivered ->
                awaitSessionEstablishment(peerId, reservation.pendingHandshake)
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
    ): TransportSendResult {
        val retryWindow = TimeSource.Monotonic.markNow()
        while (true) {
            val result =
                callbacks.sendDirectWireFrame(
                    peerId,
                    DirectWireFrame.HandshakeMessage1(message1),
                    "handshake.message1",
                    null,
                )
            if (result !is TransportSendResult.Dropped || !result.isTransientLinkNotReady()) {
                return result
            }
            if (retryWindow.elapsedNow() >= handshakeTimeout) {
                return result
            }
            delay(HANDSHAKE_MESSAGE1_RETRY_DELAY)
        }
    }

    private suspend fun awaitSessionEstablishment(
        peerId: PeerId,
        pendingHandshake: PendingInitiatorHandshake,
    ): SessionEstablishmentOutcome {
        return try {
            withTimeout(handshakeTimeout.inWholeMilliseconds) {
                pendingHandshake.sessionDeferred.await()
            }
        } catch (_: TimeoutCancellationException) {
            failPendingInitiatorHandshake(
                peerId = peerId,
                pendingHandshake = pendingHandshake,
                stage = "transport.handshake.timeout",
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
                emptyMap(),
            )
        }
        return pendingHandshake.sessionDeferred.completedOutcomeOr(
            fallback = SessionEstablishmentOutcome.Unreachable
        )
    }

    private suspend fun InitiatorHandshakeReservation.awaitOrReturn(
        peerId: PeerId
    ): SessionEstablishmentOutcome {
        return when (this) {
            is InitiatorHandshakeReservation.Established ->
                SessionEstablishmentOutcome.Established(session)
            is InitiatorHandshakeReservation.Pending ->
                awaitSessionEstablishment(peerId, pendingHandshake)
            is InitiatorHandshakeReservation.Created -> initiateReservedHopSession(peerId, this)
        }
    }
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

private val HANDSHAKE_MESSAGE1_RETRY_DELAY = 100.milliseconds
