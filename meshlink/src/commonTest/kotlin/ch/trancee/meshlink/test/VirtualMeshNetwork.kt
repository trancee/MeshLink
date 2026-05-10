package ch.trancee.meshlink.test

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.TransportMode

internal class VirtualMeshNetwork {
    private val transports: MutableMap<String, VirtualMeshTransport> = linkedMapOf()

    internal fun register(transport: VirtualMeshTransport): Unit {
        transports[transport.localPeerId.value] = transport
        transports.values.forEach { other ->
            if (other !== transport) {
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

    internal fun deliver(senderPeerId: PeerId, recipientPeerId: PeerId, payload: ByteArray): Boolean {
        val recipient = transports[recipientPeerId.value] ?: return false
        recipient.receive(senderPeerId = senderPeerId, payload = payload)
        return true
    }
}
