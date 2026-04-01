package io.meshlink.crypto

import io.meshlink.storage.InMemorySecureStorage
import kotlin.test.*

class PersistedTrustStoreTest {

    private lateinit var storage: InMemorySecureStorage
    private val key1 = ByteArray(32) { it.toByte() }
    private val key2 = ByteArray(32) { (it + 100).toByte() }

    @BeforeTest
    fun setUp() {
        storage = InMemorySecureStorage()
    }

    // ── pin persistence ─────────────────────────────────────────────────

    @Test
    fun firstVerifyPersistsPinAsTOFU() {
        val ts = PersistedTrustStore(TrustMode.STRICT, storage)
        val result = ts.verify("peer1", key1)
        assertIs<VerifyResult.FirstSeen>(result)

        val data = storage.get("trust_pin_peer1")
        assertNotNull(data)
        assertEquals(0.toByte(), data[0], "pin type should be TOFU (0)")
        assertContentEquals(key1, data.copyOfRange(1, data.size))
    }

    @Test
    fun repinPersistsWithExplicitType() {
        val ts = PersistedTrustStore(TrustMode.STRICT, storage)
        ts.verify("peer1", key1)
        ts.repin("peer1", key2)

        val data = storage.get("trust_pin_peer1")
        assertNotNull(data)
        assertEquals(1.toByte(), data[0], "pin type should be EXPLICIT (1)")
        assertContentEquals(key2, data.copyOfRange(1, data.size))
    }

    // ── restart survival ────────────────────────────────────────────────

    @Test
    fun pinnedKeySurvivesRestart() {
        val ts1 = PersistedTrustStore(TrustMode.STRICT, storage)
        ts1.verify("peer1", key1)

        val ts2 = PersistedTrustStore(TrustMode.STRICT, storage)
        assertIs<VerifyResult.Trusted>(ts2.verify("peer1", key1))
    }

    @Test
    fun repinnedKeySurvivesRestart() {
        val ts = PersistedTrustStore(TrustMode.STRICT, storage)
        ts.verify("peer1", key1)
        ts.repin("peer1", key2)

        val ts2 = PersistedTrustStore(TrustMode.STRICT, storage)
        assertIs<VerifyResult.Trusted>(ts2.verify("peer1", key2))
    }

    @Test
    fun multiplePeersSurviveRestart() {
        val ts = PersistedTrustStore(TrustMode.STRICT, storage)
        ts.verify("peer1", key1)
        ts.verify("peer2", key2)

        val ts2 = PersistedTrustStore(TrustMode.STRICT, storage)
        assertIs<VerifyResult.Trusted>(ts2.verify("peer1", key1))
        assertIs<VerifyResult.Trusted>(ts2.verify("peer2", key2))
    }

    // ── removal ─────────────────────────────────────────────────────────

    @Test
    fun removePinDeletesFromStorage() {
        val ts = PersistedTrustStore(TrustMode.STRICT, storage)
        ts.verify("peer1", key1)
        ts.removePin("peer1")

        assertNull(storage.get("trust_pin_peer1"))

        // After restart the peer is unknown again
        val ts2 = PersistedTrustStore(TrustMode.STRICT, storage)
        assertIs<VerifyResult.FirstSeen>(ts2.verify("peer1", key1))
    }

    @Test
    fun removeOnePeerLeavesOthersIntact() {
        val ts = PersistedTrustStore(TrustMode.STRICT, storage)
        ts.verify("peer1", key1)
        ts.verify("peer2", key2)
        ts.removePin("peer1")

        val ts2 = PersistedTrustStore(TrustMode.STRICT, storage)
        assertIs<VerifyResult.FirstSeen>(ts2.verify("peer1", key1))
        assertIs<VerifyResult.Trusted>(ts2.verify("peer2", key2))
    }

    // ── clear ───────────────────────────────────────────────────────────

    @Test
    fun clearPinsRemovesAll() {
        val ts = PersistedTrustStore(TrustMode.STRICT, storage)
        ts.verify("peer1", key1)
        ts.verify("peer2", key2)
        ts.clearPins()

        assertNull(storage.get("trust_pin_peer1"))
        assertNull(storage.get("trust_pin_peer2"))
        assertNull(storage.get(PersistedTrustStore.INDEX_KEY))

        val ts2 = PersistedTrustStore(TrustMode.STRICT, storage)
        assertIs<VerifyResult.FirstSeen>(ts2.verify("peer1", key1))
        assertIs<VerifyResult.FirstSeen>(ts2.verify("peer2", key2))
    }

    // ── trust modes ─────────────────────────────────────────────────────

    @Test
    fun strictModeRejectsChangedKey() {
        val ts = PersistedTrustStore(TrustMode.STRICT, storage)
        ts.verify("peer1", key1)
        assertIs<VerifyResult.KeyChanged>(ts.verify("peer1", key2))
    }

    @Test
    fun softRepinModePersistsAutoRepin() {
        val ts = PersistedTrustStore(TrustMode.SOFT_REPIN, storage)
        ts.verify("peer1", key1)
        // Different key in SOFT_REPIN → auto-repin → FirstSeen
        assertIs<VerifyResult.FirstSeen>(ts.verify("peer1", key2))

        val ts2 = PersistedTrustStore(TrustMode.SOFT_REPIN, storage)
        assertIs<VerifyResult.Trusted>(ts2.verify("peer1", key2))
    }

    // ── snapshot ────────────────────────────────────────────────────────

    @Test
    fun emptyStoreHasNoPins() {
        val ts = PersistedTrustStore(TrustMode.STRICT, storage)
        assertTrue(ts.snapshot().pins.isEmpty())
    }

    @Test
    fun snapshotReflectsCurrentState() {
        val ts = PersistedTrustStore(TrustMode.STRICT, storage)
        ts.verify("peer1", key1)
        ts.verify("peer2", key2)

        val snap = ts.snapshot()
        assertEquals(2, snap.pins.size)
        assertContentEquals(key1, snap.pins["peer1"])
        assertContentEquals(key2, snap.pins["peer2"])
    }
}
