package io.meshlink.model

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PeerEventTest {

    private val peerId = byteArrayOf(0x01, 0x02, 0x03, 0x04)

    @Test
    fun foundHoldsPeerId() {
        val event = PeerEvent.Found(peerId)
        assertIs<PeerEvent.Found>(event)
        assertTrue(event.peerId.contentEquals(peerId))
    }

    @Test
    fun lostHoldsPeerId() {
        val event = PeerEvent.Lost(peerId)
        assertIs<PeerEvent.Lost>(event)
        assertTrue(event.peerId.contentEquals(peerId))
    }

    @Test
    fun foundAndLostAreDifferentSubtypes() {
        val found = PeerEvent.Found(peerId)
        val lost = PeerEvent.Lost(peerId)
        assertIs<PeerEvent.Found>(found)
        assertIs<PeerEvent.Lost>(lost)
    }

    @Test
    fun emptyPeerIdIsAllowed() {
        val event = PeerEvent.Found(byteArrayOf())
        assertTrue(event.peerId.isEmpty())
    }
}
