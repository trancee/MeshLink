package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustStoreTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Constructs a 12-byte peer Key Hash whose every byte equals [id]. */
    private fun peerHash(id: Int): ByteArray = ByteArray(12) { id.toByte() }

    /** Constructs a 32-byte static public key whose every byte equals [id]. */
    private fun key(id: Int): ByteArray = ByteArray(32) { id.toByte() }

    // ── (a) first pinKey succeeds ─────────────────────────────────────────────

    @Test
    fun firstPinKeySucceeds() {
        val store = TrustStore(InMemorySecureStorage())
        assertTrue(store.pinKey(peerHash(1), key(1)))
    }

    // ── (b) pinKey with same key is no-op ─────────────────────────────────────

    @Test
    fun pinKeyWithSameKeyIsNoOp() {
        val store = TrustStore(InMemorySecureStorage())
        assertTrue(store.pinKey(peerHash(1), key(1)))
        assertTrue(store.pinKey(peerHash(1), key(1)))
    }

    // ── (c) STRICT: key change rejected without callback ──────────────────────

    @Test
    fun strictModeKeyChangeRejectedWithoutCallback() {
        val store = TrustStore(InMemorySecureStorage(), TrustMode.STRICT)
        assertTrue(store.pinKey(peerHash(1), key(1)))
        assertFalse(store.pinKey(peerHash(1), key(2)))
        // Original pin must still be in place.
        assertContentEquals(key(1), store.getPinnedKey(peerHash(1)))
    }

    // ── (d) STRICT: key change emits KeyChangeEvent via callback ──────────────

    @Test
    fun strictModeKeyChangeNotifiesCallback() {
        var capturedEvent: KeyChangeEvent? = null
        val store =
            TrustStore(
                InMemorySecureStorage(),
                TrustMode.STRICT,
                onKeyChange = { event ->
                    capturedEvent = event
                    true // return value is ignored in STRICT mode
                },
            )
        assertTrue(store.pinKey(peerHash(1), key(1)))
        // STRICT rejects even when the callback returns true.
        assertFalse(store.pinKey(peerHash(1), key(2)))
        val evt = capturedEvent
        assertNotNull(evt)
        assertContentEquals(peerHash(1), evt.peerKeyHash)
        assertContentEquals(key(1), evt.oldKey)
        assertContentEquals(key(2), evt.newKey)
        // Old pin still in place despite callback returning true.
        assertContentEquals(key(1), store.getPinnedKey(peerHash(1)))
    }

    // ── (e) PROMPT: callback returns true → key repinned ─────────────────────

    @Test
    fun promptModeCallbackReturnsTrueAcceptsKeyChange() {
        val store = TrustStore(InMemorySecureStorage(), TrustMode.PROMPT, onKeyChange = { true })
        assertTrue(store.pinKey(peerHash(1), key(1)))
        assertTrue(store.pinKey(peerHash(1), key(2)))
        assertContentEquals(key(2), store.getPinnedKey(peerHash(1)))
    }

    // ── (f) PROMPT: callback returns false → key NOT repinned ─────────────────

    @Test
    fun promptModeCallbackReturnsFalseRejectsKeyChange() {
        val store = TrustStore(InMemorySecureStorage(), TrustMode.PROMPT, onKeyChange = { false })
        assertTrue(store.pinKey(peerHash(1), key(1)))
        assertFalse(store.pinKey(peerHash(1), key(2)))
        assertContentEquals(key(1), store.getPinnedKey(peerHash(1)))
    }

    // ── (g) PROMPT: no callback → rejected ────────────────────────────────────

    @Test
    fun promptModeNoCallbackRejectsKeyChange() {
        val store = TrustStore(InMemorySecureStorage(), TrustMode.PROMPT)
        assertTrue(store.pinKey(peerHash(1), key(1)))
        assertFalse(store.pinKey(peerHash(1), key(2)))
        assertContentEquals(key(1), store.getPinnedKey(peerHash(1)))
    }

    // ── (h) getPinnedKey returns correct key ───────────────────────────────────

    @Test
    fun getPinnedKeyReturnsCorrectKey() {
        val store = TrustStore(InMemorySecureStorage())
        val k = key(42)
        store.pinKey(peerHash(1), k)
        assertContentEquals(k, store.getPinnedKey(peerHash(1)))
    }

    // ── (i) getPinnedKey for unknown peer returns null ─────────────────────────

    @Test
    fun getPinnedKeyForUnknownPeerReturnsNull() {
        val store = TrustStore(InMemorySecureStorage())
        assertNull(store.getPinnedKey(peerHash(99)))
    }

    // ── (j) repinKey overwrites existing pin ───────────────────────────────────

    @Test
    fun repinKeyOverwritesExistingPin() {
        val store = TrustStore(InMemorySecureStorage())
        store.pinKey(peerHash(1), key(1))
        store.repinKey(peerHash(1), key(2))
        assertContentEquals(key(2), store.getPinnedKey(peerHash(1)))
    }

    // ── (k) repinKey for unknown peer creates new pin ──────────────────────────

    @Test
    fun repinKeyForUnknownPeerCreatesNewPin() {
        val store = TrustStore(InMemorySecureStorage())
        assertNull(store.getPinnedKey(peerHash(1)))
        store.repinKey(peerHash(1), key(1))
        assertContentEquals(key(1), store.getPinnedKey(peerHash(1)))
    }

    // ── (l) removePinForPeer removes pin ───────────────────────────────────────

    @Test
    fun removePinForPeerRemovesPin() {
        val store = TrustStore(InMemorySecureStorage())
        store.pinKey(peerHash(1), key(1))
        store.removePinForPeer(peerHash(1))
        assertNull(store.getPinnedKey(peerHash(1)))
    }

    // ── (m) removePinForPeer for unknown peer is no-op ─────────────────────────

    @Test
    fun removePinForUnknownPeerIsNoOp() {
        val store = TrustStore(InMemorySecureStorage())
        store.removePinForPeer(peerHash(99)) // must not throw
        assertNull(store.getPinnedKey(peerHash(99)))
    }

    // ── (n) KeyChangeEvent holds correct fields ────────────────────────────────

    @Test
    fun keyChangeEventHoldsCorrectFields() {
        val hash = ByteArray(12) { (it + 1).toByte() }
        val old = ByteArray(32) { 10 }
        val new = ByteArray(32) { 20 }
        val event = KeyChangeEvent(hash, old, new)
        assertContentEquals(hash, event.peerKeyHash)
        assertContentEquals(old, event.oldKey)
        assertContentEquals(new, event.newKey)
    }

    // ── (o) storage key format is correct hex encoding ─────────────────────────

    @Test
    fun storageKeyFormatIsCorrectHexEncoding() {
        val store = TrustStore(InMemorySecureStorage())
        // bytes 0x01..0x0c → lowercase hex "0102030405060708090a0b0c"
        val hash = ByteArray(12) { (it + 1).toByte() }
        assertEquals("meshlink.trust.0102030405060708090a0b0c", store.storageKey(hash))
        // Boundary: 0x00 and 0xff must encode as "00" and "ff" (lower-case)
        val hash2 =
            ByteArray(12) { i ->
                when (i) {
                    0 -> 0x00.toByte()
                    1 -> 0xFF.toByte()
                    else -> i.toByte()
                }
            }
        assertTrue(
            store.storageKey(hash2).startsWith("meshlink.trust.00ff"),
            "Storage key must use lowercase hex encoding",
        )
    }

    // ── KeyChangeEvent equals / hashCode ──────────────────────────────────────

    /**
     * Covers: `other is KeyChangeEvent` → true AND `contentEquals` → true (equals returns true).
     * Also exercises hashCode.
     */
    @Test
    fun keyChangeEventEqualsSamePeerKeyHash() {
        val hash = ByteArray(12) { 1 }
        val event1 = KeyChangeEvent(hash, ByteArray(32) { 1 }, ByteArray(32) { 2 })
        // Same peerKeyHash but different old/new keys — equals must be true.
        val event2 = KeyChangeEvent(hash, ByteArray(32) { 3 }, ByteArray(32) { 4 })
        assertEquals(event1, event2)
        assertEquals(event1.hashCode(), event2.hashCode())
    }

    /**
     * Covers: `other is KeyChangeEvent` → true AND `contentEquals` → false (equals returns false).
     */
    @Test
    fun keyChangeEventNotEqualsDifferentPeerKeyHash() {
        val event1 = KeyChangeEvent(ByteArray(12) { 1 }, ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val event2 = KeyChangeEvent(ByteArray(12) { 2 }, ByteArray(32) { 1 }, ByteArray(32) { 2 })
        assertFalse(event1.equals(event2))
    }

    /** Covers: `other is KeyChangeEvent` → false (equals returns false immediately). */
    @Test
    fun keyChangeEventNotEqualsWrongType() {
        val event = KeyChangeEvent(ByteArray(12) { 1 }, ByteArray(32) { 1 }, ByteArray(32) { 2 })
        assertFalse(event.equals("not a KeyChangeEvent"))
    }
}
