package io.meshlink.power

import io.meshlink.util.ByteArrayKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GracefulDrainManagerTest {

    private fun key(s: String) = ByteArrayKey(s.encodeToByteArray())

    @Test
    fun startDrainMarksPeerAsInGracePeriod() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 1000L })
        mgr.startDrain(key("aabb"))
        assertTrue(mgr.isInGracePeriod(key("aabb")))
    }

    @Test
    fun gracePeriodNotExpiredReturnsTrue() {
        var now = 0L
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { now })
        mgr.startDrain(key("aabb"))

        now = 29_999L
        assertTrue(mgr.isInGracePeriod(key("aabb")))
    }

    @Test
    fun gracePeriodExpiredReturnsFalseAndAppearsInExpired() {
        var now = 0L
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { now })
        mgr.startDrain(key("aabb"))

        now = 30_000L
        assertFalse(mgr.isInGracePeriod(key("aabb")))
        assertEquals(listOf(key("aabb")), mgr.expiredPeers())
    }

    @Test
    fun completeRemovesPeerFromTracking() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        mgr.startDrain(key("aabb"))
        mgr.complete(key("aabb"))

        assertFalse(mgr.isInGracePeriod(key("aabb")))
        assertEquals(0, mgr.size())
    }

    @Test
    fun multiplePeersTrackedIndependently() {
        var now = 0L
        val mgr = GracefulDrainManager(graceMillis = 10_000L, clock = { now })

        mgr.startDrain(key("aa"))
        now = 5_000L
        mgr.startDrain(key("bb"))

        now = 10_000L
        // aa started at 0, grace expired at 10_000 → expired
        assertFalse(mgr.isInGracePeriod(key("aa")))
        // bb started at 5_000, grace expires at 15_000 → still in grace
        assertTrue(mgr.isInGracePeriod(key("bb")))
        assertEquals(listOf(key("aa")), mgr.expiredPeers())
    }

    @Test
    fun clearRemovesAll() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        mgr.startDrain(key("aa"))
        mgr.startDrain(key("bb"))
        mgr.clear()

        assertEquals(0, mgr.size())
        assertTrue(mgr.drainingPeers().isEmpty())
    }

    @Test
    fun sizeTracksCorrectly() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        assertEquals(0, mgr.size())

        mgr.startDrain(key("aa"))
        assertEquals(1, mgr.size())

        mgr.startDrain(key("bb"))
        assertEquals(2, mgr.size())

        mgr.complete(key("aa"))
        assertEquals(1, mgr.size())
    }

    @Test
    fun duplicateStartDrainDoesNotResetTimer() {
        var now = 0L
        val mgr = GracefulDrainManager(graceMillis = 10_000L, clock = { now })
        mgr.startDrain(key("aabb"))

        now = 5_000L
        mgr.startDrain(key("aabb")) // should be ignored

        // original timer started at 0; at 10_000 it should be expired
        now = 10_000L
        assertFalse(mgr.isInGracePeriod(key("aabb")))

        // verify the entry still records the original start time
        assertEquals(0L, mgr.drainingPeers().single().startedAtMillis)
    }

    @Test
    fun expiredPeersReturnsEmptyWhenNoDrains() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 100_000L })
        assertTrue(mgr.expiredPeers().isEmpty())
    }

    @Test
    fun drainingPeersReturnsAllActiveDrains() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        mgr.startDrain(key("aa"), "mode_change")
        mgr.startDrain(key("bb"), "battery_low")

        val peers = mgr.drainingPeers()
        assertEquals(2, peers.size)
        assertEquals(setOf(key("aa"), key("bb")), peers.map { it.peerId }.toSet())
    }

    @Test
    fun customGracePeriod() {
        var now = 0L
        val mgr = GracefulDrainManager(graceMillis = 500L, clock = { now })
        mgr.startDrain(key("aabb"))

        now = 499L
        assertTrue(mgr.isInGracePeriod(key("aabb")))

        now = 500L
        assertFalse(mgr.isInGracePeriod(key("aabb")))
    }

    @Test
    fun reasonStringPreserved() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        mgr.startDrain(key("aabb"), "battery_critical")

        val entry = mgr.drainingPeers().single()
        assertEquals("battery_critical", entry.reason)
    }

    @Test
    fun defaultReasonIsPowerModeDowngrade() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        mgr.startDrain(key("aabb"))

        assertEquals("power_mode_downgrade", mgr.drainingPeers().single().reason)
    }

    @Test
    fun isInGracePeriodReturnsFalseForUnknownPeer() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        assertFalse(mgr.isInGracePeriod(key("unknown")))
    }

    @Test
    fun completeOnUnknownPeerIsNoOp() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        mgr.startDrain(key("aabb"))
        mgr.complete(key("unknown")) // should not throw or affect existing entries
        assertEquals(1, mgr.size())
    }
}
