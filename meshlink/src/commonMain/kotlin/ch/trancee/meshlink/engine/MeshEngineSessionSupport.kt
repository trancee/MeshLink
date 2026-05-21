package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal data class MeshEngineSessionState(
    val hopSessions: MutableMap<String, HopSession>,
    val pendingInitiatorHandshakes: MutableMap<String, PendingInitiatorHandshake>,
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
    suspend fun establishedHopSession(peerId: PeerId): HopSession? {
        return (ensureHopSession(peerId) as? SessionEstablishmentOutcome.Established)?.session
    }

    suspend fun ensureHopSession(peerId: PeerId): SessionEstablishmentOutcome {
        val existingSession = state.hopSessions[peerId.value]
        val pendingHandshake = state.pendingInitiatorHandshakes[peerId.value]

        return when {
            existingSession != null -> SessionEstablishmentOutcome.Established(existingSession)
            pendingHandshake != null ->
                awaitSessionEstablishment(peerId, pendingHandshake.sessionDeferred)
            !callbacks.hasTransport() -> SessionEstablishmentOutcome.Unreachable
            else -> initiateHopSession(peerId)
        }
    }

    private suspend fun initiateHopSession(peerId: PeerId): SessionEstablishmentOutcome {
        val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
        val message1 = manager.createMessage1(localIdentity.noiseIdentity)
        val sessionDeferred = CompletableDeferred<SessionEstablishmentOutcome>()
        state.pendingInitiatorHandshakes[peerId.value] =
            PendingInitiatorHandshake(manager, sessionDeferred)

        return when (
            callbacks.sendDirectWireFrame(
                peerId,
                DirectWireFrame.HandshakeMessage1(message1),
                "handshake.message1",
                null,
            )
        ) {
            TransportSendResult.Delivered -> awaitSessionEstablishment(peerId, sessionDeferred)
            is TransportSendResult.Dropped -> {
                state.pendingInitiatorHandshakes.remove(peerId.value)
                callbacks.emitHopSessionFailed(
                    peerId,
                    "transport.handshake.message1.send",
                    DiagnosticReason.DELIVERY_FAILURE,
                    emptyMap(),
                )
                SessionEstablishmentOutcome.Unreachable
            }
        }
    }

    private suspend fun awaitSessionEstablishment(
        peerId: PeerId,
        sessionDeferred: CompletableDeferred<SessionEstablishmentOutcome>,
    ): SessionEstablishmentOutcome {
        return try {
            withTimeout(handshakeTimeout.inWholeMilliseconds) { sessionDeferred.await() }
        } catch (_: TimeoutCancellationException) {
            state.pendingInitiatorHandshakes.remove(peerId.value)
            callbacks.emitHopSessionFailed(
                peerId,
                "transport.handshake.timeout",
                DiagnosticReason.DELIVERY_FAILURE,
                emptyMap(),
            )
            SessionEstablishmentOutcome.Unreachable
        }
    }
}
