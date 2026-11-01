package bakeoff

import kotlin.time.TimeSource

/**
 * Tracks links per peer node ID in both roles. Keep-one rule for crossed links between the same pair: keep the
 * link whose central has the lower node ID (unsigned compare); the higher-ID node drops its own central link.
 */
class LinkRegistry(private val localNodeId: Long) {
    class PeerLinks {
        var central: CentralConnection? = null
        var peripheral: ByteLink? = null
        var peripheralSeen: Boolean = false
        var peripheralClosedAt: TimeSource.Monotonic.ValueTimeMark? = null
    }

    val peers: MutableMap<Long, PeerLinks> = mutableMapOf()

    fun keepsOwnCentral(peerNodeId: Long): Boolean = localNodeId.toULong() < peerNodeId.toULong()

    fun onCentralLink(peerNodeId: Long, connection: CentralConnection) {
        peers.getOrPut(peerNodeId) { PeerLinks() }.central = connection
    }

    fun onPeripheralLink(peer: PeerIdentity, link: ByteLink) {
        peers.getOrPut(peer.nodeId) { PeerLinks() }.apply {
            peripheral = link
            peripheralSeen = true
            peripheralClosedAt = null
        }
    }

    fun onPeripheralLinkClosed(peer: PeerIdentity, link: ByteLink) {
        val links = peers[peer.nodeId] ?: return
        if (links.peripheral === link) {
            links.peripheral = null
            links.peripheralClosedAt = TimeSource.Monotonic.markNow()
        }
    }

    fun resetRound() {
        peers.values.forEach {
            it.central = null
            it.peripheralSeen = it.peripheral != null
        }
    }
}
