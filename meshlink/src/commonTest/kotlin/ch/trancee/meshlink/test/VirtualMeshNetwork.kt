package ch.trancee.meshlink.test

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.TransportMode

internal class VirtualMeshNetwork {
    private val transports: MutableMap<String, VirtualMeshTransport> = linkedMapOf()
    private val linkedPeers: MutableSet<LinkKey> = linkedSetOf()
    private val heldDeliveries: MutableMap<DirectedLinkKey, MutableList<PendingDelivery>> = linkedMapOf()
    private val dropRules: MutableMap<DirectedLinkKey, Int> = linkedMapOf()
    private val duplicateRules: MutableMap<DirectedLinkKey, Int> = linkedMapOf()
    private val holdRules: MutableMap<DirectedLinkKey, Int> = linkedMapOf()
    private var manualTopologyEnabled: Boolean = false
    private var maximumPayloadBytesPerDelivery: Int? = null

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

    internal fun setMaximumPayloadBytesPerDelivery(limit: Int?): Unit {
        maximumPayloadBytesPerDelivery = limit
    }

    internal fun dropNextDeliveries(senderPeerId: PeerId, recipientPeerId: PeerId, count: Int = 1): Unit {
        val key = DirectedLinkKey.of(senderPeerId, recipientPeerId)
        dropRules[key] = (dropRules[key] ?: 0) + count
    }

    internal fun duplicateNextDeliveries(senderPeerId: PeerId, recipientPeerId: PeerId, count: Int = 1): Unit {
        val key = DirectedLinkKey.of(senderPeerId, recipientPeerId)
        duplicateRules[key] = (duplicateRules[key] ?: 0) + count
    }

    internal fun holdNextDeliveries(senderPeerId: PeerId, recipientPeerId: PeerId, count: Int = 1): Unit {
        val key = DirectedLinkKey.of(senderPeerId, recipientPeerId)
        holdRules[key] = (holdRules[key] ?: 0) + count
    }

    internal fun releaseHeldDeliveries(senderPeerId: PeerId, recipientPeerId: PeerId): Unit {
        val key = DirectedLinkKey.of(senderPeerId, recipientPeerId)
        val deliveries = heldDeliveries.remove(key).orEmpty()
        deliveries.forEach { delivery ->
            val recipient = transports[delivery.recipientPeerId.value] ?: return@forEach
            recipient.receive(senderPeerId = delivery.senderPeerId, payload = delivery.payload)
        }
    }

    internal fun deliver(senderPeerId: PeerId, recipientPeerId: PeerId, payload: ByteArray): DeliveryOutcome {
        if (!areLinked(senderPeerId, recipientPeerId)) {
            return DeliveryOutcome.RecipientUnavailable
        }
        val recipient = transports[recipientPeerId.value] ?: return DeliveryOutcome.RecipientUnavailable
        val key = DirectedLinkKey.of(senderPeerId, recipientPeerId)

        if (maximumPayloadBytesPerDelivery != null && payload.size > maximumPayloadBytesPerDelivery!!) {
            return DeliveryOutcome.AcceptedButDropped
        }
        if (holdRules.consume(key)) {
            heldDeliveries.getOrPut(key) { mutableListOf() } += PendingDelivery(senderPeerId, recipientPeerId, payload.copyOf())
            return DeliveryOutcome.Delivered
        }
        if (dropRules.consume(key)) {
            return DeliveryOutcome.AcceptedButDropped
        }

        recipient.receive(senderPeerId = senderPeerId, payload = payload)
        if (duplicateRules.consume(key)) {
            recipient.receive(senderPeerId = senderPeerId, payload = payload.copyOf())
        }
        return DeliveryOutcome.Delivered
    }

    private fun areLinked(first: PeerId, second: PeerId): Boolean {
        if (!manualTopologyEnabled) {
            return true
        }
        return linkedPeers.contains(LinkKey.of(first, second))
    }

    private fun MutableMap<DirectedLinkKey, Int>.consume(key: DirectedLinkKey): Boolean {
        val remaining = this[key] ?: return false
        if (remaining <= 1) {
            remove(key)
        } else {
            this[key] = remaining - 1
        }
        return true
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

private class DirectedLinkKey private constructor(
    private val senderPeerIdValue: String,
    private val recipientPeerIdValue: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is DirectedLinkKey) {
            return false
        }
        return senderPeerIdValue == other.senderPeerIdValue && recipientPeerIdValue == other.recipientPeerIdValue
    }

    override fun hashCode(): Int {
        var result = senderPeerIdValue.hashCode()
        result = 31 * result + recipientPeerIdValue.hashCode()
        return result
    }

    internal companion object {
        internal fun of(senderPeerId: PeerId, recipientPeerId: PeerId): DirectedLinkKey {
            return DirectedLinkKey(senderPeerId.value, recipientPeerId.value)
        }
    }
}

private class PendingDelivery internal constructor(
    internal val senderPeerId: PeerId,
    internal val recipientPeerId: PeerId,
    payload: ByteArray,
) {
    internal val payload: ByteArray = payload.copyOf()
}

internal enum class DeliveryOutcome {
    Delivered,
    AcceptedButDropped,
    RecipientUnavailable,
}
