package ch.trancee.meshlink.power

import ch.trancee.meshlink.transfer.Priority

data class ManagedConnection(
    val peerId: ByteArray,
    val priority: Priority,
    val transferStatus: TransferStatus? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is ManagedConnection) return false
        return peerId.contentEquals(other.peerId) &&
            priority == other.priority &&
            transferStatus == other.transferStatus
    }

    override fun hashCode(): Int {
        var result = peerId.contentHashCode()
        result = 31 * result + priority.hashCode()
        result = 31 * result + (transferStatus?.hashCode() ?: 0)
        return result
    }
}
