package ch.trancee.meshlink.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Unit tests for [PeerState] enum, [PeerEvent.StateChanged], and [PeerDetail.state]. */
class PeerStateTest {

    // ── PeerState enum ──────────────────────────────────────────────────────

    @Test
    fun `PeerState has CONNECTED and DISCONNECTED values`() {
        val values = PeerState.entries
        assertEquals(2, values.size)
        assertTrue(values.contains(PeerState.CONNECTED))
        assertTrue(values.contains(PeerState.DISCONNECTED))
    }

    @Test
    fun `PeerState valueOf round-trips`() {
        assertEquals(PeerState.CONNECTED, PeerState.valueOf("CONNECTED"))
        assertEquals(PeerState.DISCONNECTED, PeerState.valueOf("DISCONNECTED"))
    }

    // ── PeerEvent.StateChanged ──────────────────────────────────────────────

    @Test
    fun `StateChanged equals by id and state content`() {
        val id = byteArrayOf(1, 2, 3)
        val e1 = PeerEvent.StateChanged(id, PeerState.CONNECTED)
        val e2 = PeerEvent.StateChanged(id.copyOf(), PeerState.CONNECTED)
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    fun `StateChanged not equal with different state`() {
        val id = byteArrayOf(1, 2, 3)
        val e1 = PeerEvent.StateChanged(id, PeerState.CONNECTED)
        val e2 = PeerEvent.StateChanged(id.copyOf(), PeerState.DISCONNECTED)
        assertNotEquals(e1, e2)
    }

    @Test
    fun `StateChanged not equal with different id`() {
        val e1 = PeerEvent.StateChanged(byteArrayOf(1), PeerState.CONNECTED)
        val e2 = PeerEvent.StateChanged(byteArrayOf(2), PeerState.CONNECTED)
        assertNotEquals(e1, e2)
    }

    @Test
    fun `StateChanged not equal to null or other type`() {
        val e = PeerEvent.StateChanged(byteArrayOf(1), PeerState.CONNECTED)
        assertFalse(e.equals(null))
        assertFalse(e.equals("not a StateChanged"))
    }

    @Test
    fun `StateChanged is a PeerEvent`() {
        val e = PeerEvent.StateChanged(byteArrayOf(1), PeerState.CONNECTED)
        assertIs<PeerEvent>(e)
    }

    // ── PeerDetail.state ────────────────────────────────────────────────────

    @Test
    fun `PeerDetail state CONNECTED yields isConnected true`() {
        val detail =
            PeerDetail(
                id = byteArrayOf(1, 2, 3),
                staticPublicKey = ByteArray(32),
                fingerprint = "aabbcc",
                state = PeerState.CONNECTED,
                lastSeenTimestampMillis = 100L,
                trustMode = TrustMode.STRICT,
            )
        assertTrue(detail.isConnected)
        assertEquals(PeerState.CONNECTED, detail.state)
    }

    @Test
    fun `PeerDetail state DISCONNECTED yields isConnected false`() {
        val detail =
            PeerDetail(
                id = byteArrayOf(1, 2, 3),
                staticPublicKey = ByteArray(32),
                fingerprint = "aabbcc",
                state = PeerState.DISCONNECTED,
                lastSeenTimestampMillis = 100L,
                trustMode = TrustMode.STRICT,
            )
        assertFalse(detail.isConnected)
        assertEquals(PeerState.DISCONNECTED, detail.state)
    }

    @Test
    fun `PeerDetail toString includes state`() {
        val detail =
            PeerDetail(
                id = byteArrayOf(1, 2, 3),
                staticPublicKey = ByteArray(32),
                fingerprint = "aabbcc",
                state = PeerState.CONNECTED,
                lastSeenTimestampMillis = 100L,
                trustMode = TrustMode.STRICT,
            )
        assertTrue(detail.toString().contains("CONNECTED"))
    }

    // ── mapPeerEvent integration ────────────────────────────────────────────

    @Test
    fun `mapPeerEvent Gone produces Lost only`() {
        val peerId = ByteArray(12) { 0x0A }
        val event = ch.trancee.meshlink.routing.PeerEvent.Gone(peerId)
        val result = mapPeerEvent(event) { 0L }
        assertEquals(1, result.size)
        assertIs<PeerEvent.Lost>(result[0])
        assertTrue((result[0] as PeerEvent.Lost).id.contentEquals(peerId))
    }

    @Test
    fun `mapPeerEvent Connected produces Found and StateChanged CONNECTED`() {
        val peerId = ByteArray(12) { 0x0B }
        val event = ch.trancee.meshlink.routing.PeerEvent.Connected(peerId)
        val result = mapPeerEvent(event) { 99L }
        assertEquals(2, result.size)
        assertIs<PeerEvent.Found>(result[0])
        assertIs<PeerEvent.StateChanged>(result[1])
        assertEquals(PeerState.CONNECTED, (result[1] as PeerEvent.StateChanged).state)
    }

    @Test
    fun `mapPeerEvent Disconnected produces StateChanged DISCONNECTED`() {
        val peerId = ByteArray(12) { 0x0C }
        val event = ch.trancee.meshlink.routing.PeerEvent.Disconnected(peerId)
        val result = mapPeerEvent(event) { 0L }
        assertEquals(1, result.size)
        assertIs<PeerEvent.StateChanged>(result[0])
        assertEquals(PeerState.DISCONNECTED, (result[0] as PeerEvent.StateChanged).state)
    }
}
