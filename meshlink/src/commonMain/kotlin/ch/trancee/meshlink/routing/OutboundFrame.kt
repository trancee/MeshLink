package ch.trancee.meshlink.routing

import ch.trancee.meshlink.wire.WireMessage

data class OutboundFrame(val peerId: ByteArray?, val message: WireMessage) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboundFrame) return false
        val peerIdEqual = peerId?.contentEquals(other.peerId) ?: (other.peerId == null)
        return peerIdEqual && message == other.message
    }

    override fun hashCode(): Int {
        val peerHash = peerId?.contentHashCode() ?: 0
        return 31 * peerHash + message.hashCode()
    }
}
