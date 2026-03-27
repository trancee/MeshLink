package io.meshlink.model

sealed class PeerEvent {
    data class Discovered(val peerId: ByteArray) : PeerEvent()
    data class Lost(val peerId: ByteArray) : PeerEvent()
}
