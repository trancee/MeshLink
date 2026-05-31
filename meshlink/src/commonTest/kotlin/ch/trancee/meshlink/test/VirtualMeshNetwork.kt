package ch.trancee.meshlink.test

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.TransportMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private data class VirtualMeshNetworkState(
    val transports: Map<String, VirtualMeshTransport> = emptyMap(),
    val linkedPeers: Map<LinkKey, TransportMode> = emptyMap(),
    val heldDeliveries: Map<DirectedLinkKey, List<PendingDelivery>> = emptyMap(),
    val dropRules: Map<DirectedLinkKey, Int> = emptyMap(),
    val duplicateRules: Map<DirectedLinkKey, Int> = emptyMap(),
    val holdRules: Map<DirectedLinkKey, Int> = emptyMap(),
    val manualTopologyEnabled: Boolean = false,
    val maximumPayloadBytesPerDelivery: Int? = null,
)

internal class VirtualMeshNetwork {
    private val mutableState: MutableStateFlow<VirtualMeshNetworkState> =
        MutableStateFlow(VirtualMeshNetworkState())

    internal fun register(transport: VirtualMeshTransport): Unit {
        mutableState.update { state ->
            state.copy(transports = state.transports + (transport.localPeerId.value to transport))
        }
        val snapshot = mutableState.value
        snapshot.transports.values.forEach { other ->
            if (
                other !== transport && snapshot.areLinked(transport.localPeerId, other.localPeerId)
            ) {
                val mode = snapshot.linkMode(transport.localPeerId, other.localPeerId)
                other.connect(transport.localPeerId, mode)
                transport.connect(other.localPeerId, mode)
            }
        }
    }

    internal fun unregister(peerId: PeerId): Unit {
        mutableState.update { state -> state.copy(transports = state.transports - peerId.value) }
        mutableState.value.transports.values.forEach { other -> other.disconnect(peerId) }
    }

    internal fun linkPeers(
        first: PeerId,
        second: PeerId,
        mode: TransportMode = TransportMode.L2CAP,
    ): Unit {
        val linkKey = LinkKey.of(first, second)
        var added = false
        mutableState.update { state ->
            val existingMode = state.linkedPeers[linkKey]
            added = existingMode == null
            state.copy(
                manualTopologyEnabled = true,
                linkedPeers =
                    if (existingMode == null) {
                        state.linkedPeers + (linkKey to mode)
                    } else {
                        state.linkedPeers
                    },
            )
        }
        if (!added) {
            return
        }
        val snapshot = mutableState.value
        snapshot.transports[first.value]?.connect(second, mode)
        snapshot.transports[second.value]?.connect(first, mode)
    }

    internal fun unlinkPeers(first: PeerId, second: PeerId): Unit {
        val linkKey = LinkKey.of(first, second)
        var removed = false
        mutableState.update { state ->
            if (!state.linkedPeers.containsKey(linkKey)) {
                return@update state.copy(manualTopologyEnabled = true)
            }
            removed = true
            state.copy(manualTopologyEnabled = true, linkedPeers = state.linkedPeers - linkKey)
        }
        if (!removed) {
            return
        }
        val snapshot = mutableState.value
        snapshot.transports[first.value]?.disconnect(second)
        snapshot.transports[second.value]?.disconnect(first)
    }

    internal fun setMaximumPayloadBytesPerDelivery(limit: Int?): Unit {
        mutableState.update { state -> state.copy(maximumPayloadBytesPerDelivery = limit) }
    }

    internal fun maximumPayloadBytesPerDelivery(): Int? {
        return mutableState.value.maximumPayloadBytesPerDelivery
    }

    internal fun dropNextDeliveries(
        senderPeerId: PeerId,
        recipientPeerId: PeerId,
        count: Int = 1,
    ): Unit {
        val key = DirectedLinkKey.of(senderPeerId, recipientPeerId)
        mutableState.update { state ->
            state.copy(dropRules = state.dropRules.incremented(key, count))
        }
    }

    internal fun duplicateNextDeliveries(
        senderPeerId: PeerId,
        recipientPeerId: PeerId,
        count: Int = 1,
    ): Unit {
        val key = DirectedLinkKey.of(senderPeerId, recipientPeerId)
        mutableState.update { state ->
            state.copy(duplicateRules = state.duplicateRules.incremented(key, count))
        }
    }

    internal fun holdNextDeliveries(
        senderPeerId: PeerId,
        recipientPeerId: PeerId,
        count: Int = 1,
    ): Unit {
        val key = DirectedLinkKey.of(senderPeerId, recipientPeerId)
        mutableState.update { state ->
            state.copy(holdRules = state.holdRules.incremented(key, count))
        }
    }

    internal fun releaseHeldDeliveries(senderPeerId: PeerId, recipientPeerId: PeerId): Unit {
        val key = DirectedLinkKey.of(senderPeerId, recipientPeerId)
        var deliveries: List<PendingDelivery> = emptyList()
        mutableState.update { state ->
            deliveries = state.heldDeliveries[key].orEmpty()
            if (deliveries.isEmpty()) {
                return@update state
            }
            state.copy(heldDeliveries = state.heldDeliveries - key)
        }
        val transports = mutableState.value.transports
        deliveries.forEach { delivery ->
            transports[delivery.recipientPeerId.value]?.receive(
                senderPeerId = delivery.senderPeerId,
                payload = delivery.payload,
            )
        }
    }

    internal fun deliver(
        senderPeerId: PeerId,
        recipientPeerId: PeerId,
        payload: ByteArray,
    ): DeliveryOutcome {
        val snapshot = mutableState.value
        if (!snapshot.areLinked(senderPeerId, recipientPeerId)) {
            return DeliveryOutcome.RecipientUnavailable
        }
        val recipient =
            snapshot.transports[recipientPeerId.value]
                ?: return DeliveryOutcome.RecipientUnavailable

        val maximumPayloadBytesPerDelivery = snapshot.maximumPayloadBytesPerDelivery
        if (
            maximumPayloadBytesPerDelivery != null && payload.size > maximumPayloadBytesPerDelivery
        ) {
            return DeliveryOutcome.AcceptedButDropped
        }

        val key = DirectedLinkKey.of(senderPeerId, recipientPeerId)
        var holdDelivery = false
        var dropDelivery = false
        var duplicateDelivery = false
        mutableState.update { state ->
            when {
                (state.holdRules[key] ?: 0) > 0 -> {
                    holdDelivery = true
                    state.copy(
                        holdRules = state.holdRules.decremented(key),
                        heldDeliveries =
                            state.heldDeliveries.appended(
                                key,
                                PendingDelivery(senderPeerId, recipientPeerId, payload),
                            ),
                    )
                }
                (state.dropRules[key] ?: 0) > 0 -> {
                    dropDelivery = true
                    state.copy(dropRules = state.dropRules.decremented(key))
                }
                (state.duplicateRules[key] ?: 0) > 0 -> {
                    duplicateDelivery = true
                    state.copy(duplicateRules = state.duplicateRules.decremented(key))
                }
                else -> state
            }
        }

        if (holdDelivery) {
            return DeliveryOutcome.Delivered
        }
        if (dropDelivery) {
            return DeliveryOutcome.AcceptedButDropped
        }

        recipient.receive(senderPeerId = senderPeerId, payload = payload)
        if (duplicateDelivery) {
            recipient.receive(senderPeerId = senderPeerId, payload = payload.copyOf())
        }
        return DeliveryOutcome.Delivered
    }
}

private fun VirtualMeshNetworkState.areLinked(first: PeerId, second: PeerId): Boolean {
    if (!manualTopologyEnabled) {
        return true
    }
    return linkedPeers.containsKey(LinkKey.of(first, second))
}

private fun VirtualMeshNetworkState.linkMode(first: PeerId, second: PeerId): TransportMode {
    return linkedPeers[LinkKey.of(first, second)] ?: TransportMode.L2CAP
}

private fun <K> Map<K, Int>.incremented(key: K, count: Int): Map<K, Int> {
    return this + (key to ((this[key] ?: 0) + count))
}

private fun <K> Map<K, Int>.decremented(key: K): Map<K, Int> {
    val remaining = this[key] ?: return this
    return if (remaining <= 1) {
        this - key
    } else {
        this + (key to (remaining - 1))
    }
}

private fun <K, V> Map<K, List<V>>.appended(key: K, value: V): Map<K, List<V>> {
    return this + (key to (this[key].orEmpty() + value))
}

private class LinkKey private constructor(private val left: String, private val right: String) {
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

private class DirectedLinkKey
private constructor(
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
        return senderPeerIdValue == other.senderPeerIdValue &&
            recipientPeerIdValue == other.recipientPeerIdValue
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

private class PendingDelivery
internal constructor(
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
