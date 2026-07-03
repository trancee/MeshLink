package ch.trancee.meshlink.engine

import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.RelayTransferSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Concentrates transfer-session mutation behind one runtime seam.
 *
 * The runtime still keeps separate inbound, outbound, and relay transfer lifecycles, but they no
 * longer own raw mutable maps directly. All mutations are serialized behind [registryMutex] because
 * the engine's default coroutine scope runs on [kotlinx.coroutines.Dispatchers.Default], a
 * genuinely multi-threaded pool, and inbound/outbound/relay transfer handling can run concurrently
 * for different peers.
 */
internal class MeshEngineTransferRegistry(
    private val outboundTransfers: MutableMap<String, OutboundTransferSession> = linkedMapOf(),
    private val inboundTransfers: MutableMap<String, InboundTransferSession> = linkedMapOf(),
    private val relayTransfers: MutableMap<String, RelayTransferSession> = linkedMapOf(),
) {
    private val registryMutex = Mutex()

    suspend fun outboundSession(transferId: String): OutboundTransferSession? {
        return registryMutex.withLock { outboundTransfers[transferId] }
    }

    suspend fun storeOutboundSession(session: OutboundTransferSession): Unit {
        registryMutex.withLock { outboundTransfers[session.transferId] = session }
    }

    suspend fun removeOutboundSession(transferId: String): OutboundTransferSession? {
        return registryMutex.withLock { outboundTransfers.remove(transferId) }
    }

    suspend fun takeAllOutboundSessions(): List<OutboundTransferSession> {
        return registryMutex.withLock {
            val sessions = outboundTransfers.values.toList()
            outboundTransfers.clear()
            sessions
        }
    }

    suspend fun clearOutboundSessions(): Unit {
        registryMutex.withLock { outboundTransfers.clear() }
    }

    suspend fun inboundSession(transferId: String): InboundTransferSession? {
        return registryMutex.withLock { inboundTransfers[transferId] }
    }

    suspend fun storeInboundSession(session: InboundTransferSession): Unit {
        registryMutex.withLock { inboundTransfers[session.transferId] = session }
    }

    suspend fun removeInboundSession(transferId: String): InboundTransferSession? {
        return registryMutex.withLock { inboundTransfers.remove(transferId) }
    }

    suspend fun takeAllInboundSessions(): List<InboundTransferSession> {
        return registryMutex.withLock {
            val sessions = inboundTransfers.values.toList()
            inboundTransfers.clear()
            sessions
        }
    }

    suspend fun clearInboundSessions(): Unit {
        registryMutex.withLock { inboundTransfers.clear() }
    }

    suspend fun relaySession(transferId: String): RelayTransferSession? {
        return registryMutex.withLock { relayTransfers[transferId] }
    }

    suspend fun storeRelaySession(session: RelayTransferSession): Unit {
        registryMutex.withLock { relayTransfers[session.transferId] = session }
    }

    suspend fun removeRelaySession(transferId: String): RelayTransferSession? {
        return registryMutex.withLock { relayTransfers.remove(transferId) }
    }

    suspend fun takeAllRelaySessions(): List<RelayTransferSession> {
        return registryMutex.withLock {
            val sessions = relayTransfers.values.toList()
            relayTransfers.clear()
            sessions
        }
    }

    suspend fun clearRelaySessions(): Unit {
        registryMutex.withLock { relayTransfers.clear() }
    }

    suspend fun outboundTransfersSnapshot(): Map<String, OutboundTransferSession> {
        return registryMutex.withLock { outboundTransfers.toMap() }
    }

    suspend fun inboundTransfersSnapshot(): Map<String, InboundTransferSession> {
        return registryMutex.withLock { inboundTransfers.toMap() }
    }

    suspend fun relayTransfersSnapshot(): Map<String, RelayTransferSession> {
        return registryMutex.withLock { relayTransfers.toMap() }
    }
}
