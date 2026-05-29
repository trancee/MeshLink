package ch.trancee.meshlink.engine

import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.RelayTransferSession

/**
 * Concentrates transfer-session mutation behind one runtime seam.
 *
 * The runtime still keeps separate inbound, outbound, and relay transfer lifecycles, but they no
 * longer own raw mutable maps directly.
 */
internal class MeshEngineTransferRegistry(
    private val outboundTransfers: MutableMap<String, OutboundTransferSession> = linkedMapOf(),
    private val inboundTransfers: MutableMap<String, InboundTransferSession> = linkedMapOf(),
    private val relayTransfers: MutableMap<String, RelayTransferSession> = linkedMapOf(),
) {
    fun outboundSession(transferId: String): OutboundTransferSession? {
        return outboundTransfers[transferId]
    }

    fun storeOutboundSession(session: OutboundTransferSession): Unit {
        outboundTransfers[session.transferId] = session
    }

    fun removeOutboundSession(transferId: String): OutboundTransferSession? {
        return outboundTransfers.remove(transferId)
    }

    fun takeAllOutboundSessions(): List<OutboundTransferSession> {
        val sessions = outboundTransfers.values.toList()
        outboundTransfers.clear()
        return sessions
    }

    fun clearOutboundSessions(): Unit {
        outboundTransfers.clear()
    }

    fun inboundSession(transferId: String): InboundTransferSession? {
        return inboundTransfers[transferId]
    }

    fun storeInboundSession(session: InboundTransferSession): Unit {
        inboundTransfers[session.transferId] = session
    }

    fun removeInboundSession(transferId: String): InboundTransferSession? {
        return inboundTransfers.remove(transferId)
    }

    fun takeAllInboundSessions(): List<InboundTransferSession> {
        val sessions = inboundTransfers.values.toList()
        inboundTransfers.clear()
        return sessions
    }

    fun clearInboundSessions(): Unit {
        inboundTransfers.clear()
    }

    fun relaySession(transferId: String): RelayTransferSession? {
        return relayTransfers[transferId]
    }

    fun storeRelaySession(session: RelayTransferSession): Unit {
        relayTransfers[session.transferId] = session
    }

    fun removeRelaySession(transferId: String): RelayTransferSession? {
        return relayTransfers.remove(transferId)
    }

    fun takeAllRelaySessions(): List<RelayTransferSession> {
        val sessions = relayTransfers.values.toList()
        relayTransfers.clear()
        return sessions
    }

    fun clearRelaySessions(): Unit {
        relayTransfers.clear()
    }

    fun outboundTransfersSnapshot(): Map<String, OutboundTransferSession> {
        return outboundTransfers.toMap()
    }

    fun inboundTransfersSnapshot(): Map<String, InboundTransferSession> {
        return inboundTransfers.toMap()
    }

    fun relayTransfersSnapshot(): Map<String, RelayTransferSession> {
        return relayTransfers.toMap()
    }
}
