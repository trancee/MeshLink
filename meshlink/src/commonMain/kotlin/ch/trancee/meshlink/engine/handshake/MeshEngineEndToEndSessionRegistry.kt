package ch.trancee.meshlink.engine.handshake

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed class EndToEndInitiatorHandshakeReservation {
    class Established internal constructor(internal val session: EndToEndSession) :
        EndToEndInitiatorHandshakeReservation()

    class Pending
    internal constructor(internal val pendingHandshake: PendingEndToEndInitiatorHandshake) :
        EndToEndInitiatorHandshakeReservation()

    class Created
    internal constructor(
        internal val pendingHandshake: PendingEndToEndInitiatorHandshake,
        internal val message1Frame: WireFrame.EndToEndHandshakeMessage1,
    ) : EndToEndInitiatorHandshakeReservation()
}

/**
 * Tracks in-flight and established end-to-end (multi-hop) Noise XX sessions, keyed by the *remote*
 * peer on the other end of the session (mirroring how [MeshEngineSessionRegistry] keys hop sessions
 * by the directly connected peer). Unlike the hop-level registry, there is no temporary-peer-id
 * aliasing here: [PeerId] values reaching this registry are already-resolved logical mesh peer
 * identifiers, not raw transport addresses.
 */
internal class MeshEngineEndToEndSessionRegistry {
    private val mutex = Mutex()
    private val sessions: MutableMap<String, EndToEndSession> = linkedMapOf()
    private val pendingInitiatorHandshakes: MutableMap<String, PendingEndToEndInitiatorHandshake> =
        linkedMapOf()
    private val pendingResponderHandshakes: MutableMap<String, PendingEndToEndResponderHandshake> =
        linkedMapOf()

    suspend fun session(peerId: PeerId): EndToEndSession? {
        return mutex.withLock { sessions[peerId.value] }
    }

    suspend fun initiatorHandshakeReservation(
        peerId: PeerId,
        createHandshake: (suspend () -> CreatedEndToEndInitiatorHandshake)? = null,
    ): EndToEndInitiatorHandshakeReservation? {
        return mutex.withLock {
            sessions[peerId.value]?.let { existingSession ->
                return@withLock EndToEndInitiatorHandshakeReservation.Established(existingSession)
            }
            pendingInitiatorHandshakes[peerId.value]?.let { pendingHandshake ->
                return@withLock EndToEndInitiatorHandshakeReservation.Pending(pendingHandshake)
            }
            if (createHandshake == null) {
                return@withLock null
            }

            val created = createHandshake()
            pendingInitiatorHandshakes[peerId.value] = created.pendingHandshake
            EndToEndInitiatorHandshakeReservation.Created(
                pendingHandshake = created.pendingHandshake,
                message1Frame = created.message1Frame,
            )
        }
    }

    suspend fun completeInitiatorHandshake(
        peerId: PeerId,
        pendingHandshake: PendingEndToEndInitiatorHandshake,
        session: EndToEndSession,
    ): Boolean {
        return mutex.withLock {
            if (pendingInitiatorHandshakes[peerId.value] !== pendingHandshake) {
                return@withLock false
            }
            pendingInitiatorHandshakes.remove(peerId.value)
            sessions[peerId.value] = session
            true
        }
    }

    suspend fun failInitiatorHandshake(
        peerId: PeerId,
        pendingHandshake: PendingEndToEndInitiatorHandshake,
    ): Boolean {
        return mutex.withLock {
            if (pendingInitiatorHandshakes[peerId.value] !== pendingHandshake) {
                return@withLock false
            }
            pendingInitiatorHandshakes.remove(peerId.value)
            true
        }
    }

    suspend fun pendingResponderHandshake(peerId: PeerId): PendingEndToEndResponderHandshake? {
        return mutex.withLock { pendingResponderHandshakes[peerId.value] }
    }

    suspend fun storePendingResponderHandshake(
        peerId: PeerId,
        pendingHandshake: PendingEndToEndResponderHandshake,
    ): Unit {
        mutex.withLock { pendingResponderHandshakes[peerId.value] = pendingHandshake }
    }

    suspend fun completeResponderHandshake(
        peerId: PeerId,
        pendingHandshake: PendingEndToEndResponderHandshake,
        session: EndToEndSession,
    ): Boolean {
        return mutex.withLock {
            if (pendingResponderHandshakes[peerId.value] !== pendingHandshake) {
                return@withLock false
            }
            pendingResponderHandshakes.remove(peerId.value)
            sessions[peerId.value] = session
            true
        }
    }

    suspend fun removePendingResponderHandshake(
        peerId: PeerId,
        pendingHandshake: PendingEndToEndResponderHandshake? = null,
    ): PendingEndToEndResponderHandshake? {
        return mutex.withLock {
            val currentPendingHandshake =
                pendingResponderHandshakes[peerId.value] ?: return@withLock null
            if (pendingHandshake != null && currentPendingHandshake !== pendingHandshake) {
                return@withLock null
            }
            pendingResponderHandshakes.remove(peerId.value)
        }
    }

    suspend fun clearPeer(peerId: PeerId): PendingEndToEndInitiatorHandshake? {
        return mutex.withLock {
            sessions.remove(peerId.value)
            pendingResponderHandshakes.remove(peerId.value)
            pendingInitiatorHandshakes.remove(peerId.value)
        }
    }

    suspend fun clear(): List<PendingEndToEndInitiatorHandshake> {
        return mutex.withLock {
            val pendingHandshakes = pendingInitiatorHandshakes.values.toList()
            sessions.clear()
            pendingInitiatorHandshakes.clear()
            pendingResponderHandshakes.clear()
            pendingHandshakes
        }
    }
}
