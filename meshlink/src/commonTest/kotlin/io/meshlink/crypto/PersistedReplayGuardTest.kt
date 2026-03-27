package io.meshlink.crypto

import io.meshlink.crypto.PersistedReplayGuard.Companion.fromBytesBigEndian
import io.meshlink.crypto.PersistedReplayGuard.Companion.toBytesBigEndian
import io.meshlink.storage.InMemorySecureStorage
import kotlin.test.*

class PersistedReplayGuardTest {

    private lateinit var storage: InMemorySecureStorage

    @BeforeTest
    fun setUp() {
        storage = InMemorySecureStorage()
    }

    // ── outbound persistence ────────────────────────────────────────────

    @Test
    fun advancePersistsOutboundCounter() {
        val guard = PersistedReplayGuard("aabb", storage)
        val c1 = guard.advance()
        assertEquals(1uL, c1)

        val persisted = storage.get("replay_out_aabb")
        assertNotNull(persisted)
        assertEquals(1uL, persisted.fromBytesBigEndian())
    }

    @Test
    fun outboundCounterSurvivesRestart() {
        val g1 = PersistedReplayGuard("aabb", storage)
        g1.advance() // 1
        g1.advance() // 2
        g1.advance() // 3

        // Simulate restart
        val g2 = PersistedReplayGuard("aabb", storage)
        assertEquals(4uL, g2.advance())
    }

    // ── inbound persistence ─────────────────────────────────────────────

    @Test
    fun inboundCounterPersistedOnFlush() {
        val guard = PersistedReplayGuard("cc", storage)
        assertTrue(guard.check(5uL))
        guard.flush()

        val persisted = storage.get("replay_in_cc")
        assertNotNull(persisted)
        assertEquals(5uL, persisted.fromBytesBigEndian())
    }

    @Test
    fun inboundHighestSeenSurvivesRestart() {
        val g1 = PersistedReplayGuard("dd", storage)
        assertTrue(g1.check(10uL))
        g1.flush()

        // Simulate restart
        val g2 = PersistedReplayGuard("dd", storage)
        assertFalse(g2.check(10uL), "counter at highestSeen must be rejected after restart")
        assertFalse(g2.check(5uL), "counter below highestSeen must be rejected after restart")
        assertTrue(g2.check(11uL), "counter above highestSeen must be accepted")
    }

    @Test
    fun inboundPersistedPeriodicallyByMessageCount() {
        val guard = PersistedReplayGuard(
            peerIdHex = "ee",
            storage = storage,
            persistIntervalMs = Long.MAX_VALUE,   // disable time-based
            persistIntervalMessages = 3,
        )
        assertTrue(guard.check(1uL))
        assertTrue(guard.check(2uL))
        assertNull(storage.get("replay_in_ee"), "should not persist before threshold")
        assertTrue(guard.check(3uL)) // 3rd accepted → triggers persist

        val persisted = storage.get("replay_in_ee")
        assertNotNull(persisted)
        assertEquals(3uL, persisted.fromBytesBigEndian())
    }

    @Test
    fun rejectedMessagesDoNotCountTowardPersistThreshold() {
        val guard = PersistedReplayGuard(
            peerIdHex = "ff",
            storage = storage,
            persistIntervalMs = Long.MAX_VALUE,
            persistIntervalMessages = 2,
        )
        assertTrue(guard.check(1uL))
        assertFalse(guard.check(1uL))  // duplicate – rejected
        assertFalse(guard.check(0uL))  // sentinel – rejected
        assertNull(storage.get("replay_in_ff"), "rejected messages should not trigger persist")
        assertTrue(guard.check(2uL))   // 2nd accepted → persist

        assertNotNull(storage.get("replay_in_ff"))
    }

    // ── peer isolation ──────────────────────────────────────────────────

    @Test
    fun separatePeersAreIsolated() {
        val g1 = PersistedReplayGuard("aa", storage)
        val g2 = PersistedReplayGuard("bb", storage)

        g1.advance() // aa → 1
        g1.advance() // aa → 2
        g2.advance() // bb → 1

        // Simulate restart
        val g1r = PersistedReplayGuard("aa", storage)
        val g2r = PersistedReplayGuard("bb", storage)
        assertEquals(3uL, g1r.advance())
        assertEquals(2uL, g2r.advance())
    }

    // ── fresh guard ─────────────────────────────────────────────────────

    @Test
    fun freshGuardStartsAtZero() {
        val guard = PersistedReplayGuard("ff", storage)
        val snap = guard.snapshot()
        assertEquals(0uL, snap.outboundCounter)
        assertEquals(0uL, snap.highestSeen)
    }

    // ── serialisation helpers ───────────────────────────────────────────

    @Test
    fun bigEndianRoundTrip() {
        val values = listOf(0uL, 1uL, 255uL, 256uL, ULong.MAX_VALUE, 0x0102030405060708uL)
        for (v in values) {
            assertEquals(v, v.toBytesBigEndian().fromBytesBigEndian())
        }
    }

    @Test
    fun bigEndianEncodingLayout() {
        val bytes = 0x0102030405060708uL.toBytesBigEndian()
        assertEquals(8, bytes.size)
        assertEquals(0x01.toByte(), bytes[0])
        assertEquals(0x02.toByte(), bytes[1])
        assertEquals(0x07.toByte(), bytes[6])
        assertEquals(0x08.toByte(), bytes[7])
    }

    // ── error handling ──────────────────────────────────────────────────

    @Test
    fun corruptedStorageTreatedAsZero() {
        storage.put("replay_out_zz", byteArrayOf(1, 2, 3))   // wrong size
        storage.put("replay_in_zz", byteArrayOf())             // empty
        val guard = PersistedReplayGuard("zz", storage)
        assertEquals(1uL, guard.advance(), "corrupted storage should default to 0")
    }
}
