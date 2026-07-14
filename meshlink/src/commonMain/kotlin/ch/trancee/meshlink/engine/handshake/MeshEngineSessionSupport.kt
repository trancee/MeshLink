package ch.trancee.meshlink.engine.handshake

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeGate
import ch.trancee.meshlink.engine.internal.HopSession
import ch.trancee.meshlink.engine.internal.MeshEngineEmitDiagnostic
import ch.trancee.meshlink.engine.internal.MeshEnginePeerRouteMetadata
import ch.trancee.meshlink.engine.internal.PendingInitiatorHandshake
import ch.trancee.meshlink.engine.internal.SessionEstablishmentOutcome
import ch.trancee.meshlink.engine.internal.diagnosticSuffix
import ch.trancee.meshlink.engine.internal.isTransientLinkNotReady
import ch.trancee.meshlink.engine.lifecycle.MeshEngineRuntimeTimedWaitResult
import ch.trancee.meshlink.engine.lifecycle.waitWithRuntimeGate
import ch.trancee.meshlink.engine.transport.DirectWireFrame
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
    val emitHopSessionFailed:
        suspend (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
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
        if (currentReservation != null) {
            return currentReservation.awaitOrReturn(peerId, hardRunToken)
        }
        if (!callbacks.hasTransport()) {
            return SessionEstablishmentOutcome.Unreachable
        }
        if (!shouldInitiateHandshakeTowards(localIdentity.peerId, peerId)) {
            // Defer to the peer to initiate (see shouldInitiateHandshakeTowards) so both sides
            // don't simultaneously become Noise-XX initiators towards each other. Wait briefly
            // for their handshake to complete as responder -- long enough to catch a handshake
            // that's already in flight (e.g. from their own prewarm or explicit send), but short
            // enough that we don't stall an otherwise-healthy send for peers that never initiate
            // on their own -- then fall back to initiating ourselves.
            awaitEstablishedSessionAsResponder(peerId, hardRunToken)?.let { session ->
                return SessionEstablishmentOutcome.Established(session)
            }
        }

        val reservation = reserveInitiatorHandshake(peerId)
        return reservation.awaitOrReturn(peerId, hardRunToken)
    }

    private suspend fun awaitEstablishedSessionAsResponder(
        peerId: PeerId,
        hardRunToken: MeshEngineHardRunToken?,
    ): HopSession? {
        return when (
            val wait = state.sessionRegistry.awaitOrRegisterEstablishedSessionWaiter(peerId)
        ) {
            is EstablishedSessionWait.Already -> wait.session
            is EstablishedSessionWait.Pending -> {
                val session = awaitResponderSessionAdaptively(peerId, wait.deferred, hardRunToken)
                if (session == null) {
                    state.sessionRegistry.removeEstablishedSessionWaiter(peerId, wait.deferred)
                }
                session
            }
        }
    }

    /**
     * Waits for [deferred] (the deferring side's established-session waiter, see
     * [awaitEstablishedSessionAsResponder]) in two phases: a short fixed
     * [DEFER_TO_PEER_INITIATOR_FAST_WAIT] window first, then -- only if a responder handshake for
     * [peerId] is actually known to be in flight by that point -- an extended wait up to
     * [DEFER_TO_PEER_INITIATOR_WAIT] total. This keeps the common case (a peer that never
     * proactively initiates on its own, so nothing will ever complete this waiter) falling back to
     * self-initiating quickly, while still giving a genuinely in-flight responder handshake
     * (message1 already received, message2/message3 still pending) realistic headroom to finish
     * before we race a redundant, competing initiator handshake of our own.
     */
    private suspend fun awaitResponderSessionAdaptively(
        peerId: PeerId,
        deferred: CompletableDeferred<HopSession>,
        hardRunToken: MeshEngineHardRunToken?,
    ): HopSession? {
        awaitDeferredWithTimeout(deferred, DEFER_TO_PEER_INITIATOR_FAST_WAIT, hardRunToken)?.let {
            return it
        }
        if (deferred.isCompleted) {
            return deferred.await()
        }
        if (state.sessionRegistry.pendingResponderHandshake(peerId) == null) {
            return null
        }
        val remainingWait = DEFER_TO_PEER_INITIATOR_WAIT - DEFER_TO_PEER_INITIATOR_FAST_WAIT
        return awaitDeferredWithTimeout(deferred, remainingWait, hardRunToken)
    }

    private suspend fun awaitDeferredWithTimeout(
        deferred: CompletableDeferred<HopSession>,
        timeout: Duration,
        hardRunToken: MeshEngineHardRunToken?,
    ): HopSession? {
        if (hardRunToken == null) {
            return withTimeoutOrNull(timeout) { deferred.await() }
        }
        return when (
            val waitResult =
                waitWithRuntimeGate(
                    runtimeGate = state.runtimeGate,
                    hardRunToken = hardRunToken,
                    maximumActiveWait = timeout,
                    awaitChange = { activeWait ->
                        withTimeoutOrNull(activeWait) { deferred.await() }
                    },
                )
        ) {
            is MeshEngineRuntimeTimedWaitResult.Completed -> waitResult.value
            MeshEngineRuntimeTimedWaitResult.TimedOut -> null
            MeshEngineRuntimeTimedWaitResult.HardRunEnded ->
                if (deferred.isCompleted) deferred.await() else null
        }
    }

    private suspend fun awaitHandshakeRetryDelay(hardRunToken: MeshEngineHardRunToken?): Boolean {
        if (hardRunToken == null) {
            delay(HANDSHAKE_RETRY_DELAY)
            return true
        }
        return when (
            waitWithRuntimeGate(
                runtimeGate = state.runtimeGate,
                hardRunToken = hardRunToken,
                maximumActiveWait = HANDSHAKE_RETRY_DELAY,
                awaitChange = { activeWait -> withTimeoutOrNull(activeWait) { delay(activeWait) } },
            )
        ) {
            is MeshEngineRuntimeTimedWaitResult.Completed,
            MeshEngineRuntimeTimedWaitResult.TimedOut -> true
            MeshEngineRuntimeTimedWaitResult.HardRunEnded -> false
        }
    }

    private suspend fun reserveInitiatorHandshake(peerId: PeerId): InitiatorHandshakeReservation {
        return state.sessionRegistry.initiatorHandshakeReservation(peerId) { attemptId ->
            val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
            val message1 = manager.createMessage1(meshDomainHash = localIdentity.meshDomainHash)
            val pendingHandshake =
                PendingInitiatorHandshake(
                    manager = manager,
                    sessionDeferred = CompletableDeferred(),
                    attemptId = attemptId,
                )
            CreatedInitiatorHandshake(pendingHandshake = pendingHandshake, message1 = message1)
        } ?: error("Initiator reservation must create or reuse a session state")
    }

    private suspend fun initiateReservedHopSession(
        peerId: PeerId,
        reservation: InitiatorHandshakeReservation.Created,
        hardRunToken: MeshEngineHardRunToken?,
        attempt: Int = 1,
    ): SessionEstablishmentOutcome {
        return when (
            sendHandshakeMessage1(
                peerId = peerId,
                message1 = reservation.message1,
                hardRunToken = hardRunToken,
            )
        ) {
            TransportSendResult.Delivered -> {
                val outcome =
                    awaitSessionEstablishment(peerId, reservation.pendingHandshake, hardRunToken)
                // message1 was delivered, so a failure here means the peer's reply never arrived
                // in time -- often because of a transient transport hiccup (for example a BLE
                // GATT link that drops with a generic `status=133` disconnect mid-handshake and
                // then reconnects a few seconds later). Nothing else re-triggers a fresh handshake
                // attempt for an already-discovered peer, so retry a bounded number of times here
                // as long as the transport is still available, rather than failing permanently.
                if (
                    outcome is SessionEstablishmentOutcome.Established ||
                        attempt >= HANDSHAKE_RETRY_ATTEMPTS ||
                        !callbacks.hasTransport() ||
                        !awaitHandshakeRetryDelay(hardRunToken)
                ) {
                    outcome
                } else {
                    retryHandshakeAfterTransientTimeout(peerId, hardRunToken, attempt)
                }
            }
            is TransportSendResult.Dropped -> {
                failPendingInitiatorHandshake(
                    peerId = peerId,
                    pendingHandshake = reservation.pendingHandshake,
                    stage = "transport.handshake.message1.send",
                )
            }
        }
    }

    private suspend fun retryHandshakeAfterTransientTimeout(
        peerId: PeerId,
        hardRunToken: MeshEngineHardRunToken?,
        previousAttempt: Int,
    ): SessionEstablishmentOutcome {
        return when (val nextReservation = reserveInitiatorHandshake(peerId)) {
            is InitiatorHandshakeReservation.Established ->
                SessionEstablishmentOutcome.Established(nextReservation.session)
            is InitiatorHandshakeReservation.Pending ->
                awaitSessionEstablishment(peerId, nextReservation.pendingHandshake, hardRunToken)
            is InitiatorHandshakeReservation.Created ->
                initiateReservedHopSession(
                    peerId,
                    nextReservation,
                    hardRunToken,
                    previousAttempt + 1,
                )
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
                mapOf(
                    "peerId" to peerId.value,
                    "pendingHandshake" to "initiator",
                    "attemptId" to pendingHandshake.attemptId.toString(),
                ),
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
    emitDiagnostic: MeshEngineEmitDiagnostic,
    peerRouteMetadata: MeshEnginePeerRouteMetadata,
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
                        peerId.diagnosticSuffix(),
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

/**
 * Returns whether [localPeerId] should proactively initiate a Noise-XX handshake towards
 * [remotePeerId], rather than deferring to the remote peer to initiate towards it instead. Uses a
 * stable, symmetric tie-break (lower peer id value initiates) so both sides of a peer pair always
 * agree on which one initiates. This prevents both sides from simultaneously becoming initiators
 * for the same peer relationship, which would otherwise leave one of the two competing handshakes
 * dangling until it times out or is rejected as an unexpected duplicate once the other completes.
 */
internal fun shouldInitiateHandshakeTowards(localPeerId: PeerId, remotePeerId: PeerId): Boolean {
    return localPeerId.value < remotePeerId.value
}

// Widened from 3s -> 6s: the full GATT side-link setup (connect -> MTU negotiation -> service
// discovery -> CCCD write) has been observed to take up to ~10s on slower/older BLE chipsets
// (e.g. iPhone 12 mini), which was consistently exceeding the previous 3s window and causing
// HOP_SESSION_FAILED stage=transport.handshake.timeout loops on otherwise-healthy links. 6s gives
// slower hardware realistic headroom while still failing fast enough that a genuinely unreachable
// peer doesn't stall sends for too long.
private val HANDSHAKE_TIMEOUT = 6.seconds
private val HANDSHAKE_MESSAGE1_RETRY_DELAY = 100.milliseconds

/**
 * How long the deferring side of a peer pair (see [shouldInitiateHandshakeTowards]) waits, in the
 * fast phase (see [MeshEngineSessionSupport.awaitResponderSessionAdaptively]), before checking
 * whether a responder handshake is actually in flight. Kept short so a peer that never proactively
 * initiates on its own (nothing will ever complete the waiter) falls back to self-initiating
 * quickly rather than always paying the full [DEFER_TO_PEER_INITIATOR_WAIT].
 */
private val DEFER_TO_PEER_INITIATOR_FAST_WAIT = 250.milliseconds

/**
 * Total ceiling on how long the deferring side of a peer pair (see
 * [shouldInitiateHandshakeTowards]) waits for the peer's own handshake to complete as responder
 * before falling back to initiating itself -- but only once a responder handshake for that peer is
 * confirmed to actually be in flight (see
 * [MeshEngineSessionSupport.awaitResponderSessionAdaptively]).
 *
 * Widened from 250ms -> 5s: on physical BLE hardware a full Noise-XX handshake as responder
 * (message1 receipt -> message2 send -> message3 receipt, including GATT side-link setup) has been
 * observed to take ~2s end to end. The previous flat 250ms window was far shorter than that, so the
 * deferring side reliably gave up before an in-flight handshake it was waiting on had a chance to
 * finish, and initiated a redundant, competing handshake of its own -- producing a stray
 * `HOP_SESSION_FAILED` (message2.unexpected, then transport.handshake.timeout) even though the
 * original handshake succeeded and the session/route were already healthy. 5s gives a genuinely
 * in-flight responder handshake realistic headroom to complete, while staying below
 * [HANDSHAKE_TIMEOUT] so a peer whose responder handshake stalls still falls back to initiating
 * with time left to retry before the caller gives up entirely. The adaptive fast-phase check above
 * ensures this longer ceiling is only ever paid when there's actually something in flight to wait
 * for.
 */
private val DEFER_TO_PEER_INITIATOR_WAIT = 5.seconds

/**
 * Total handshake attempts made by [MeshEngineSessionSupport.ensureHopSession], including the
 * first. Increased from 3 -> 5 alongside the [HANDSHAKE_TIMEOUT] increase so peers with slow BLE
 * side-link setup get more chances to complete a handshake before the caller gives up entirely.
 */
private const val HANDSHAKE_RETRY_ATTEMPTS = 5
private val HANDSHAKE_RETRY_DELAY = 500.milliseconds
