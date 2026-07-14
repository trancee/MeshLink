package ch.trancee.meshlink.engine.internal

import ch.trancee.meshlink.identity.LocalIdentity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MeshEngineSequenceGenerator(
    private val localIdentity: LocalIdentity,
    private var nextSequenceNumber: Long = 1L,
) {
    private val sequenceMutex = Mutex()

    suspend fun createMessageId(): String {
        val current = nextSequence()
        return "${localIdentity.peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH)}-$current"
    }

    suspend fun createTransferId(): String {
        val current = nextSequence()
        return "transfer-${localIdentity.peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH)}-$current"
    }

    suspend fun createHandshakeId(): String {
        val current = nextSequence()
        return "e2e-handshake-${localIdentity.peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH)}-$current"
    }

    private suspend fun nextSequence(): Long {
        return sequenceMutex.withLock {
            val current = nextSequenceNumber
            nextSequenceNumber += 1L
            current
        }
    }
}
