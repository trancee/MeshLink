package ch.trancee.meshlink.test

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.TransportMode

internal class VirtualMeshNetwork {
    private val transports: MutableMap<String, VirtualMeshTransport> = linkedMapOf()
    private val linkedPeers: MutableSet<LinkKey> = linkedSetOf()
    private var manualTopologyEnabled: Boolean = false

    internal fun register(transport: VirtualMeshTransport): Unit {
        transports[transport.localPeerId.value] = transport
        transports.values.forEach { other ->
            if (other !== transport && areLinked(transport.localPeerId, other.localPeerId)) {
                other.connect(transport.localPeerId, TransportMode.GATT)
                transport.connect(other.localPeerId, TransportMode.GATT)
            }
        }
    }

    internal fun unregister(peerId: PeerId): Unit {
        transports.remove(peerId.value)
        transports.values.forEach { other ->
            other.disconnect(peerId)
        }
    }

    internal fun linkPeers(first: PeerId, second: PeerId): Unit {
        manualTopologyEnabled = true
        val linkKey = LinkKey.of(first, second)
        if (!linkedPeers.add(linkKey)) {
            return
        }
        val firstTransport = transports[first.value]
        val secondTransport = transports[second.value]
        if (firstTransport != null && secondTransport != null) {
            firstTransport.connect(second, TransportMode.GATT)
            secondTransport.connect(first, TransportMode.GATT)
        }
    }

    internal fun unlinkPeers(first: PeerId, second: PeerId): Unit {
        manualTopologyEnabled = true
        val linkKey = LinkKey.of(first, second)
        if (!linkedPeers.remove(linkKey)) {
            return
        }
        val firstTransport = transports[first.value]
        val secondTransport = transports[second.value]
        if (firstTransport != null && secondTransport != null) {
            firstTransport.disconnect(second)
            secondTransport.disconnect(first)
        }
    }

    internal fun deliver(senderPeerId: PeerId, recipientPeerId: PeerId, payload: ByteArray): Boolean {
        if (!areLinked(senderPeerId, recipientPeerId)) {
            return false
        }
        val recipient = transports[recipientPeerId.value] ?: return false
        recipient.receive(senderPeerId = senderPeerId, payload = payload)
        return true
    }

    private fun areLinked(first: PeerId, second: PeerId): Boolean {
        if (!manualTopologyEnabled) {
            return true
        }
        return linkedPeers.contains(LinkKey.of(first, second))
    }
}

private class LinkKey private constructor(
    private val left: String,
    private val right: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is LinkKey) {
            return false
        }
        return left == other.left && right == other.right
    }

    override fun hashCode(): Int {
        var result = left.hashCode()
        result = 31 * result + right.hashCode()
        return result
    }

    internal companion object {
        internal fun of(first: PeerId, second: PeerId): LinkKey {
            return if (first.value <= second.value) {
                LinkKey(first.value, second.value)
            } else {
                LinkKey(second.value, first.value)
            }
        }
    }
}
