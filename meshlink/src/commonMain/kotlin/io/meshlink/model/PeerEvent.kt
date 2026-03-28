package io.meshlink.model

sealed class PeerEvent {
    data class Found(val peerId: ByteArray) : PeerEvent()
    data class Lost(val peerId: ByteArray) : PeerEvent()
}
