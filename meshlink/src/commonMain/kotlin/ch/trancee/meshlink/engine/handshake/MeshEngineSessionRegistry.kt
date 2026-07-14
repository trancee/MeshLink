package ch.trancee.meshlink.engine.handshake

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.internal.HopSession
import ch.trancee.meshlink.engine.internal.PendingInitiatorHandshake
import ch.trancee.meshlink.engine.internal.PendingResponderHandshake
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Outcome of registering interest in a peer's hop session becoming established without also
 * reserving (and potentially triggering) an initiator handshake for it -- used by the side of a
 * peer pair that defers to the other side to initiate, per [shouldInitiateHandshakeTowards].
 */
internal sealed class EstablishedSessionWait {
    class Already internal constructor(internal val session: HopSession) : EstablishedSessionWait()

    class Pending internal constructor(internal val deferred: CompletableDeferred<HopSession>) :
        EstablishedSessionWait()
}

internal data class CreatedInitiatorHandshake(
    val pendingHandshake: PendingInitiatorHandshake,
    val message1: ByteArray,
)

internal sealed class InitiatorHandshakeReservation {
    class Established internal constructor(internal val session: HopSession) :
        InitiatorHandshakeReservation()

    class Pending internal constructor(internal val pendingHandshake: PendingInitiatorHandshake) :
        InitiatorHandshakeReservation()

    class Created
    internal constructor(
        internal val pendingHandshake: PendingInitiatorHandshake,
        internal val message1: ByteArray,
    ) : InitiatorHandshakeReservation()
}

internal class MeshEngineSessionRegistry {
    private val sessionMutex = Mutex()
    private val hopSessions: MutableMap<String, HopSession> = linkedMapOf()
    private val pendingInitiatorHandshakes: MutableMap<String, PendingInitiatorHandshake> =
        linkedMapOf()
    private val pendingResponderHandshakes: MutableMap<String, PendingResponderHandshake> =
        linkedMapOf()
    // Tracks the exact message1 bytes that produced the current pending responder handshake or
    // hop session for a peer, so a redundant delivery of that same wire frame (for example via
    // MeshLink's redundant GATT/L2CAP side-link transports) can be told apart from a genuinely
    // new handshake attempt -- such as a peer reconnecting under the same transport peerId with
    // a rotated identity -- which must still be processed.
    private val lastResponderMessage1: MutableMap<String, ByteArray> = linkedMapOf()
    // Tracks the exact message2 bytes that completed the current hop session for a peer as
    // initiator, so a redundant delivery of that same wire frame (again, the redundant
    // GATT/L2CAP side-link transports) can be silently ignored instead of logged as an
    // "unexpected" frame once the handshake has already completed.
    private val lastInitiatorMessage2: MutableMap<String, ByteArray> = linkedMapOf()
    private val peerAliases: MutableMap<String, String> = linkedMapOf()
    // Deferreds registered by the side of a peer pair that is deferring initiation to the other
    // side (see shouldInitiateHandshakeTowards), so it can be woken up once that side's handshake
    // completes and it has an established hop session as responder, instead of self-initiating a
    // redundant, competing handshake.
    private val establishedSessionWaiters:
        MutableMap<String, MutableList<CompletableDeferred<HopSession>>> =
        linkedMapOf()
    // Monotonically increasing counter handed out to each newly-created PendingInitiatorHandshake
    // (see PendingInitiatorHandshake.attemptId) -- temporary diagnostic aid for correlating
    // transport.handshake.message2.* diagnostics with the specific attempt they belong to.
    private var nextInitiatorAttemptId: Long = 0L
    // Bounded, most-recent-first retention of initiator handshake attempts that were superseded
    // (removed from pendingInitiatorHandshakes) before completing -- for example one that timed
    // out, or was interrupted mid-flight by a lifecycle pause/resume. A physically real BLE
    // transport can deliver that superseded attempt's message2 reply late, after a newer attempt
    // for the same peer has already taken its place in pendingInitiatorHandshakes; feeding that
    // stale message2 into the newer attempt's NoiseXXHandshakeManager fails the Noise transcript
    // check (CryptoFailure) and wrongly aborts the newer, still-legitimate attempt. Retaining a
    // few recently-superseded attempts lets the caller try the stale message2 against them first
    // -- since Noise's AEAD authentication can only succeed against the manager whose message1 it
    // actually answers, a successful decrypt there is a definitive (cryptographically verified)
    // match, at which point the stale reply is safely dropped instead of failing the current
    // attempt.
    private val supersededInitiatorHandshakes:
        MutableMap<String, MutableList<PendingInitiatorHandshake>> =
        linkedMapOf()

    suspend fun initiatorHandshakeReservation(
        peerId: PeerId,
        createHandshake: ((Long) -> CreatedInitiatorHandshake)? = null,
    ): InitiatorHandshakeReservation? {
        return sessionMutex.withLock {
            hopSessions[peerId.value]?.let { existingSession ->
                return@withLock InitiatorHandshakeReservation.Established(existingSession)
            }
            pendingInitiatorHandshakes[peerId.value]?.let { pendingHandshake ->
                return@withLock InitiatorHandshakeReservation.Pending(pendingHandshake)
            }
            if (createHandshake == null) {
                return@withLock null
            }

            nextInitiatorAttemptId += 1
            val createdHandshake = createHandshake(nextInitiatorAttemptId)
            pendingInitiatorHandshakes[peerId.value] = createdHandshake.pendingHandshake
            InitiatorHandshakeReservation.Created(
                pendingHandshake = createdHandshake.pendingHandshake,
                message1 = createdHandshake.message1,
            )
        }
    }

    suspend fun hopSession(peerId: PeerId): HopSession? {
        return sessionMutex.withLock { hopSessions[resolvePeerIdValue(peerId.value)] }
    }

    /**
     * Registers interest in [peerId]'s hop session becoming established without reserving an
     * initiator handshake for it. Returns [EstablishedSessionWait.Already] immediately if a session
     * already exists, otherwise [EstablishedSessionWait.Pending] with a deferred that completes
     * once the peer's handshake finishes (as initiator or responder).
     */
    suspend fun awaitOrRegisterEstablishedSessionWaiter(peerId: PeerId): EstablishedSessionWait {
        return sessionMutex.withLock {
            hopSessions[resolvePeerIdValue(peerId.value)]?.let { existingSession ->
                return@withLock EstablishedSessionWait.Already(existingSession)
            }
            val deferred = CompletableDeferred<HopSession>()
            establishedSessionWaiters.getOrPut(peerId.value) { mutableListOf() }.add(deferred)
            EstablishedSessionWait.Pending(deferred)
        }
    }

    /** Cancels a waiter previously registered via [awaitOrRegisterEstablishedSessionWaiter]. */
    suspend fun removeEstablishedSessionWaiter(
        peerId: PeerId,
        deferred: CompletableDeferred<HopSession>,
    ) {
        sessionMutex.withLock {
            establishedSessionWaiters[peerId.value]?.let { waiters ->
                waiters.remove(deferred)
                if (waiters.isEmpty()) {
                    establishedSessionWaiters.remove(peerId.value)
                }
            }
        }
    }

    private fun notifyEstablishedSessionWaiters(peerId: PeerId, session: HopSession) {
        establishedSessionWaiters.remove(peerId.value)?.forEach { it.complete(session) }
    }

    suspend fun resolvePeerId(peerId: PeerId): PeerId {
        return sessionMutex.withLock { PeerId(resolvePeerIdValue(peerId.value)) }
    }

    suspend fun completeInitiatorHandshake(
        peerId: PeerId,
        pendingHandshake: PendingInitiatorHandshake,
        session: HopSession,
        message2: ByteArray,
    ): Boolean {
        return sessionMutex.withLock {
            if (pendingInitiatorHandshakes[peerId.value] !== pendingHandshake) {
                return@withLock false
            }
            pendingInitiatorHandshakes.remove(peerId.value)
            supersededInitiatorHandshakes.remove(peerId.value)
            hopSessions[peerId.value] = session
            lastInitiatorMessage2[peerId.value] = message2
            notifyEstablishedSessionWaiters(peerId, session)
            true
        }
    }

    /**
     * Returns the message2 bytes that completed the peer's current initiator hop session, if any is
     * tracked. Used to distinguish a redundant delivery of the same wire frame from a genuinely new
     * or corrupted frame.
     */
    suspend fun lastInitiatorMessage2(peerId: PeerId): ByteArray? {
        return sessionMutex.withLock { lastInitiatorMessage2[peerId.value] }
    }

    /**
     * Atomically claims [pendingHandshake] for message2 processing if it isn't already being
     * processed by a concurrent duplicate delivery. Returns true if this call won the race and
     * should proceed to call `manager.processMessage2AndCreateMessage3()`; returns false if another
     * concurrent call is already processing it, in which case this delivery should be dropped as a
     * duplicate-in-flight rather than racing the same NoiseXXHandshakeManager instance (see
     * [PendingInitiatorHandshake.processing] for why that's unsafe).
     */
    suspend fun tryBeginProcessingMessage2(pendingHandshake: PendingInitiatorHandshake): Boolean {
        return sessionMutex.withLock {
            if (pendingHandshake.processing) {
                false
            } else {
                pendingHandshake.processing = true
                true
            }
        }
    }

    /**
     * Releases the claim taken by [tryBeginProcessingMessage2] once processing of a message2
     * delivery for [pendingHandshake] has finished (successfully or not), so a later, genuinely new
     * message2 delivery for the same still-pending attempt is not permanently blocked as a
     * duplicate-in-flight.
     */
    suspend fun endProcessingMessage2(pendingHandshake: PendingInitiatorHandshake) {
        sessionMutex.withLock { pendingHandshake.processing = false }
    }

    /**
     * Returns the [PendingInitiatorHandshake.attemptId] of a superseded attempt for [peerId] whose
     * [PendingInitiatorHandshake.matchedStaleMessage2Payload] equals [payload], if any -- i.e. a
     * repeat delivery of a message2 already cryptographically confirmed stale by an earlier call to
     * [tryClaimSupersededAttemptForMessage2] and [recordSupersededAttemptMatch]. Checked first so a
     * duplicate delivery of the same stale payload is recognized without needing to (unsafely)
     * re-invoke the superseded attempt's single-use NoiseXXHandshakeManager.
     */
    suspend fun previouslyMatchedSupersededAttemptId(peerId: PeerId, payload: ByteArray): Long? {
        return sessionMutex.withLock {
            supersededInitiatorHandshakes[peerId.value]
                ?.lastOrNull { it.matchedStaleMessage2Payload?.contentEquals(payload) == true }
                ?.attemptId
        }
    }

    /**
     * Returns a snapshot of superseded initiator handshake attempts for [peerId] (most recent last)
     * that have not yet been claimed for a message2 verification attempt, atomically marking them
     * claimed (via [PendingInitiatorHandshake.processing], reusing the same guard used for the
     * current pending attempt) so a concurrent duplicate delivery cannot race the same manager
     * instance. Callers must, for each claimed attempt, either call [recordSupersededAttemptMatch]
     * on a successful match, or [releaseSupersededAttemptClaim] if it did not match -- since
     * [NoiseXXHandshakeManager.tryProcessMessage2AndCreateMessage3] does not mutate the manager on
     * a failed trial, a non-matching attempt remains safely retryable against a later, different
     * message2 delivery.
     */
    suspend fun tryClaimSupersededAttemptsForMessage2(
        peerId: PeerId
    ): List<PendingInitiatorHandshake> {
        return sessionMutex.withLock {
            val candidates =
                supersededInitiatorHandshakes[peerId.value] ?: return@withLock emptyList()
            candidates.filter { candidate ->
                if (candidate.processing) {
                    false
                } else {
                    candidate.processing = true
                    true
                }
            }
        }
    }

    /**
     * Releases the claim taken by [tryClaimSupersededAttemptsForMessage2] for the superseded
     * attempt with [attemptId] belonging to [peerId] after a trial decrypt against it did *not*
     * match -- its manager was left unmutated by the failed trial (see
     * [NoiseXXHandshakeManager.tryProcessMessage2AndCreateMessage3]), so it remains safely
     * retryable against a later, different message2 delivery, for example that same attempt's own
     * genuine stale reply arriving after an unrelated frame was tried against it first.
     */
    suspend fun releaseSupersededAttemptClaim(peerId: PeerId, attemptId: Long) {
        sessionMutex.withLock {
            supersededInitiatorHandshakes[peerId.value]
                ?.firstOrNull { it.attemptId == attemptId }
                ?.processing = false
        }
    }

    /**
     * Records that [payload] was cryptographically confirmed (by a successful Noise AEAD decrypt)
     * to be a stale message2 reply belonging to the superseded attempt with [attemptId] for
     * [peerId], so a later repeat delivery of the same payload can be recognized via
     * [previouslyMatchedSupersededAttemptId] without re-invoking the manager. The attempt's claim
     * (see [tryClaimSupersededAttemptsForMessage2]) is intentionally left taken -- a matched
     * attempt should not be trial-decrypted again.
     */
    suspend fun recordSupersededAttemptMatch(peerId: PeerId, attemptId: Long, payload: ByteArray) {
        sessionMutex.withLock {
            supersededInitiatorHandshakes[peerId.value]
                ?.firstOrNull { it.attemptId == attemptId }
                ?.matchedStaleMessage2Payload = payload
        }
    }

    suspend fun rebindPendingInitiatorHandshake(
        fromPeerId: PeerId,
        toPeerId: PeerId,
        pendingHandshake: PendingInitiatorHandshake,
    ): Boolean {
        return sessionMutex.withLock {
            if (fromPeerId.value == toPeerId.value) {
                return@withLock pendingInitiatorHandshakes[fromPeerId.value] === pendingHandshake
            }
            if (pendingInitiatorHandshakes[fromPeerId.value] !== pendingHandshake) {
                return@withLock false
            }
            if (
                pendingInitiatorHandshakes.containsKey(toPeerId.value) ||
                    hopSessions.containsKey(toPeerId.value)
            ) {
                return@withLock false
            }
            pendingInitiatorHandshakes.remove(fromPeerId.value)
            pendingInitiatorHandshakes[toPeerId.value] = pendingHandshake
            peerAliases[fromPeerId.value] = toPeerId.value
            true
        }
    }

    suspend fun failInitiatorHandshake(
        peerId: PeerId,
        pendingHandshake: PendingInitiatorHandshake,
    ): Boolean {
        return sessionMutex.withLock {
            if (pendingInitiatorHandshakes[peerId.value] !== pendingHandshake) {
                return@withLock false
            }
            pendingInitiatorHandshakes.remove(peerId.value)
            retainSupersededInitiatorHandshake(peerId.value, pendingHandshake)
            true
        }
    }

    /**
     * Records [pendingHandshake] as superseded for [peerIdValue], bounded to the
     * [MAX_SUPERSEDED_INITIATOR_HANDSHAKES_PER_PEER] most recent attempts, so a late/stale message2
     * reply belonging to it can still be recognized (see [tryClaimSupersededAttemptsForMessage2]).
     * Resets [PendingInitiatorHandshake.processing] to false: it is repurposed here from guarding
     * concurrent *normal* message2 handling (which is now permanently done for a superseded
     * attempt) to guarding concurrent *stale-match* attempts against it, a non-overlapping usage
     * window that starts fresh at the moment of superseding. Must be called while holding
     * [sessionMutex].
     */
    private fun retainSupersededInitiatorHandshake(
        peerIdValue: String,
        pendingHandshake: PendingInitiatorHandshake,
    ) {
        pendingHandshake.processing = false
        val retained = supersededInitiatorHandshakes.getOrPut(peerIdValue) { mutableListOf() }
        retained.add(pendingHandshake)
        while (retained.size > MAX_SUPERSEDED_INITIATOR_HANDSHAKES_PER_PEER) {
            retained.removeAt(0)
        }
    }

    suspend fun pendingResponderHandshake(peerId: PeerId): PendingResponderHandshake? {
        return sessionMutex.withLock { pendingResponderHandshakes[peerId.value] }
    }

    /**
     * Returns the message1 bytes that produced the peer's current pending responder handshake or
     * established hop session, if any is tracked. Used to distinguish a redundant delivery of the
     * same wire frame from a genuinely new handshake attempt.
     */
    suspend fun lastResponderMessage1(peerId: PeerId): ByteArray? {
        return sessionMutex.withLock { lastResponderMessage1[resolvePeerIdValue(peerId.value)] }
    }

    suspend fun storePendingResponderHandshake(
        peerId: PeerId,
        pendingHandshake: PendingResponderHandshake,
        message1: ByteArray,
    ): Unit {
        sessionMutex.withLock {
            pendingResponderHandshakes[peerId.value] = pendingHandshake
            lastResponderMessage1[peerId.value] = message1
        }
    }

    suspend fun completeResponderHandshake(
        peerId: PeerId,
        pendingHandshake: PendingResponderHandshake,
        session: HopSession,
    ): Boolean {
        return sessionMutex.withLock {
            if (pendingResponderHandshakes[peerId.value] !== pendingHandshake) {
                return@withLock false
            }
            pendingResponderHandshakes.remove(peerId.value)
            hopSessions[peerId.value] = session
            notifyEstablishedSessionWaiters(peerId, session)
            true
        }
    }

    suspend fun rebindPendingResponderHandshake(
        fromPeerId: PeerId,
        toPeerId: PeerId,
        pendingHandshake: PendingResponderHandshake,
    ): Boolean {
        return sessionMutex.withLock {
            if (fromPeerId.value == toPeerId.value) {
                return@withLock pendingResponderHandshakes[fromPeerId.value] === pendingHandshake
            }
            if (pendingResponderHandshakes[fromPeerId.value] !== pendingHandshake) {
                return@withLock false
            }
            if (
                pendingResponderHandshakes.containsKey(toPeerId.value) ||
                    hopSessions.containsKey(toPeerId.value)
            ) {
                return@withLock false
            }
            pendingResponderHandshakes.remove(fromPeerId.value)
            pendingResponderHandshakes[toPeerId.value] = pendingHandshake
            lastResponderMessage1.remove(fromPeerId.value)?.let { message1 ->
                lastResponderMessage1[toPeerId.value] = message1
            }
            peerAliases[fromPeerId.value] = toPeerId.value
            true
        }
    }

    suspend fun removePendingResponderHandshake(
        peerId: PeerId,
        pendingHandshake: PendingResponderHandshake? = null,
    ): PendingResponderHandshake? {
        return sessionMutex.withLock {
            val currentPendingHandshake =
                pendingResponderHandshakes[peerId.value] ?: return@withLock null
            if (pendingHandshake != null && currentPendingHandshake !== pendingHandshake) {
                return@withLock null
            }
            lastResponderMessage1.remove(peerId.value)
            pendingResponderHandshakes.remove(peerId.value)
        }
    }

    suspend fun clearPeer(peerId: PeerId): PendingInitiatorHandshake? {
        return sessionMutex.withLock {
            val resolvedPeerIdValue = resolvePeerIdValue(peerId.value)
            hopSessions.remove(resolvedPeerIdValue)
            val pendingHandshake = pendingInitiatorHandshakes.remove(resolvedPeerIdValue)
            supersededInitiatorHandshakes.remove(peerId.value)
            supersededInitiatorHandshakes.remove(resolvedPeerIdValue)
            pendingResponderHandshakes.remove(resolvedPeerIdValue)
            lastResponderMessage1.remove(resolvedPeerIdValue)
            lastInitiatorMessage2.remove(resolvedPeerIdValue)
            peerAliases.remove(peerId.value)
            peerAliases.remove(resolvedPeerIdValue)
            peerAliases.entries.removeAll { (_, canonicalPeerIdValue) ->
                canonicalPeerIdValue == resolvedPeerIdValue
            }
            establishedSessionWaiters.remove(peerId.value)?.forEach { it.cancel() }
            establishedSessionWaiters.remove(resolvedPeerIdValue)?.forEach { it.cancel() }
            pendingHandshake
        }
    }

    suspend fun clear(): List<PendingInitiatorHandshake> {
        return sessionMutex.withLock {
            val pendingHandshakes = pendingInitiatorHandshakes.values.toList()
            hopSessions.clear()
            pendingInitiatorHandshakes.clear()
            supersededInitiatorHandshakes.clear()
            pendingResponderHandshakes.clear()
            lastResponderMessage1.clear()
            lastInitiatorMessage2.clear()
            peerAliases.clear()
            establishedSessionWaiters.values.forEach { waiters -> waiters.forEach { it.cancel() } }
            establishedSessionWaiters.clear()
            pendingHandshakes
        }
    }

    private fun resolvePeerIdValue(peerIdValue: String): String {
        var currentPeerIdValue = peerIdValue
        val visitedPeerIds: MutableSet<String> = linkedSetOf()
        while (true) {
            if (!visitedPeerIds.add(currentPeerIdValue)) {
                return currentPeerIdValue
            }
            currentPeerIdValue = peerAliases[currentPeerIdValue] ?: return currentPeerIdValue
        }
    }

    private companion object {
        // Bounds memory/CPU spent retrying stale message2 deliveries against superseded attempts
        // (see supersededInitiatorHandshakes) -- a few is enough to cover the realistic case of a
        // handful of rapid retries superseding each other in quick succession (for example around
        // a lifecycle pause/resume), without retaining unbounded handshake history per peer.
        const val MAX_SUPERSEDED_INITIATOR_HANDSHAKES_PER_PEER: Int = 4
    }
}
