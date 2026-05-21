package ch.trancee.meshlink.engine

import ch.trancee.meshlink.identity.LocalIdentity

internal class MeshEngineSequenceGenerator(
    private val localIdentity: LocalIdentity,
    private var nextSequenceNumber: Long = 1L,
) {
    fun createMessageId(): String {
        val current = nextSequenceNumber
        nextSequenceNumber += 1L
        return "${localIdentity.peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH)}-$current"
    }

    fun createTransferId(): String {
        val current = nextSequenceNumber
        nextSequenceNumber += 1L
        return "transfer-${localIdentity.peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH)}-$current"
    }
}
