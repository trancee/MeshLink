package io.meshlink.model

import io.meshlink.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue


class IdentifiersTest {

    // ── PeerId ────────────────────────────────────────────────────

    @Test
    fun peerIdEqualityBySameHex() {
        val a = PeerId("0a0b0c0d")
        val b = PeerId("0a0b0c0d")
        assertEquals(a, b)
    }

    @Test
    fun peerIdInequalityByDifferentHex() {
        val a = PeerId("0a0b0c0d")
        val b = PeerId("0e0f1011")
        assertNotEquals(a, b)
    }

    @Test
    fun peerIdFromBytesRoundTrip() {
        val bytes = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)
        val peerId = PeerId.fromBytes(bytes)
        assertEquals(bytes.toHex(), peerId.hex)
        assertTrue(bytes.contentEquals(peerId.bytes))
    }

    @Test
    fun peerIdWorksAsMapKey() {
        val map = mutableMapOf<PeerId, String>()
        val id = PeerId("abcdef01")
        map[id] = "peer-one"
        assertEquals("peer-one", map[PeerId("abcdef01")])
    }

    @Test
    fun peerIdToStringReturnsHex() {
        val peerId = PeerId("deadbeef")
        assertEquals("deadbeef", peerId.toString())
        assertEquals("deadbeef", "$peerId")
    }

    // ── MessageId ─────────────────────────────────────────────────

    @Test
    fun messageIdEqualityBySameHex() {
        val a = MessageId("aabbccdd")
        val b = MessageId("aabbccdd")
        assertEquals(a, b)
    }

    @Test
    fun messageIdInequalityByDifferentHex() {
        val a = MessageId("aabbccdd")
        val b = MessageId("11223344")
        assertNotEquals(a, b)
    }

    @Test
    fun messageIdFromBytesRoundTrip() {
        val bytes = ByteArray(12) { it.toByte() }
        val msgId = MessageId.fromBytes(bytes)
        assertEquals(bytes.toHex(), msgId.hex)
        assertTrue(bytes.contentEquals(msgId.bytes))
    }

    @Test
    fun messageIdRandom() {
        val a = MessageId.random()
        val b = MessageId.random()
        assertNotEquals(a, b, "random IDs should differ")
        assertEquals(24, a.hex.length, "12 bytes → 24 hex chars")
    }

    @Test
    fun messageIdWorksAsMapKey() {
        val map = mutableMapOf<MessageId, Int>()
        val id = MessageId("00112233445566778899aabb")
        map[id] = 42
        assertEquals(42, map[MessageId("00112233445566778899aabb")])
    }

    // ── Type distinction ──────────────────────────────────────────

    @Test
    fun peerIdAndMessageIdAreDistinctTypes() {
        // This test verifies compile-time type safety:
        // PeerId and MessageId cannot be interchanged.
        val peerId: PeerId = PeerId("aabb")
        val msgId: MessageId = MessageId("aabb")
        // They have the same hex but different types — no compile-time confusion
        assertEquals(peerId.hex, msgId.hex)
        // If these were both String, the compiler would allow swapping them
    }
}
