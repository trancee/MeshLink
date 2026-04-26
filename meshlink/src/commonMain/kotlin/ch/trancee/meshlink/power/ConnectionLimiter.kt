package ch.trancee.meshlink.power

import ch.trancee.meshlink.transfer.Priority

internal class ConnectionLimiter(private var maxConnections: Int) {
    private val connections = mutableMapOf<PeerKey, ManagedConnection>()

    /**
     * Tries to acquire a slot for [peerId]. Returns true if the peer already has a slot or a new
     * slot is available (in which case the connection is registered). Returns false if the limit is
     * already reached.
     */
    fun tryAcquire(peerId: ByteArray, priority: Priority): Boolean {
        val key = PeerKey(peerId)
        if (connections.containsKey(key)) return true
        if (connections.size >= maxConnections) return false
        connections[key] = ManagedConnection(peerId = peerId, priority = priority)
        return true
    }

    /** Removes the connection record for [peerId]. No-op if not present. */
    fun release(peerId: ByteArray) {
        connections.remove(PeerKey(peerId))
    }

    /** Updates the maximum number of connections allowed. */
    fun updateMaxConnections(newMax: Int) {
        maxConnections = newMax
    }

    /** Returns a snapshot of all currently registered connections. */
    fun currentConnections(): List<ManagedConnection> = connections.values.toList()

    /**
     * Updates the [TransferStatus] for an existing connection. If [status] is null the transfer
     * field is cleared (idle). No-op if the peer is not registered.
     */
    fun updateTransferStatus(peerId: ByteArray, status: TransferStatus?) {
        val key = PeerKey(peerId)
        val existing = connections[key] ?: return
        connections[key] = existing.copy(transferStatus = status)
    }

    /** Returns the current number of registered connections. */
    fun connectionCount(): Int = connections.size
}
