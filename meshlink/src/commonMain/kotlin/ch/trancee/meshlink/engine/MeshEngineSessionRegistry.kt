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
    ): Boolean {
        return sessionMutex.withLock {
            if (pendingInitiatorHandshakes[peerId.value] !== pendingHandshake) {
                return@withLock false
            }
            pendingInitiatorHandshakes.remove(peerId.value)
            hopSessions[peerId.value] = session
            true
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
            true
        }
    }

    suspend fun pendingResponderHandshake(peerId: PeerId): PendingResponderHandshake? {
        return sessionMutex.withLock { pendingResponderHandshakes[peerId.value] }
    }

    suspend fun storePendingResponderHandshake(
        peerId: PeerId,
        pendingHandshake: PendingResponderHandshake,
    ): Unit {
        sessionMutex.withLock { pendingResponderHandshakes[peerId.value] = pendingHandshake }
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
            pendingResponderHandshakes.remove(peerId.value)
        }
    }

    suspend fun clearPeer(peerId: PeerId): PendingInitiatorHandshake? {
        return sessionMutex.withLock {
            val resolvedPeerIdValue = resolvePeerIdValue(peerId.value)
            hopSessions.remove(resolvedPeerIdValue)
            val pendingHandshake = pendingInitiatorHandshakes.remove(resolvedPeerIdValue)
            pendingResponderHandshakes.remove(resolvedPeerIdValue)
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
