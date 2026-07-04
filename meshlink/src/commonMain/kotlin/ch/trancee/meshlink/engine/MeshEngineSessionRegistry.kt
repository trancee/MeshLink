package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    suspend fun initiatorHandshakeReservation(
        peerId: PeerId,
        createHandshake: (() -> CreatedInitiatorHandshake)? = null,
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

            val createdHandshake = createHandshake()
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
            hopSessions[peerId.value] = session
            lastInitiatorMessage2[peerId.value] = message2
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
            true
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
            pendingResponderHandshakes.remove(resolvedPeerIdValue)
            lastResponderMessage1.remove(resolvedPeerIdValue)
            lastInitiatorMessage2.remove(resolvedPeerIdValue)
            peerAliases.remove(peerId.value)
            peerAliases.remove(resolvedPeerIdValue)
            peerAliases.entries.removeAll { (_, canonicalPeerIdValue) ->
                canonicalPeerIdValue == resolvedPeerIdValue
            }
            pendingHandshake
        }
    }

    suspend fun clear(): List<PendingInitiatorHandshake> {
        return sessionMutex.withLock {
            val pendingHandshakes = pendingInitiatorHandshakes.values.toList()
            hopSessions.clear()
            pendingInitiatorHandshakes.clear()
            pendingResponderHandshakes.clear()
            lastResponderMessage1.clear()
            lastInitiatorMessage2.clear()
            peerAliases.clear()
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
}
