package io.meshlink.power

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GracefulDrainManagerTest {

    @Test
    fun startDrainMarksPeerAsInGracePeriod() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 1000L })
        mgr.startDrain("aabb")
        assertTrue(mgr.isInGracePeriod("aabb"))
    }

    @Test
    fun gracePeriodNotExpiredReturnsTrue() {
        var now = 0L
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { now })
        mgr.startDrain("aabb")

        now = 29_999L
        assertTrue(mgr.isInGracePeriod("aabb"))
    }

    @Test
    fun gracePeriodExpiredReturnsFalseAndAppearsInExpired() {
        var now = 0L
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { now })
        mgr.startDrain("aabb")

        now = 30_000L
        assertFalse(mgr.isInGracePeriod("aabb"))
        assertEquals(listOf("aabb"), mgr.expiredPeers())
    }

    @Test
    fun completeRemovesPeerFromTracking() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        mgr.startDrain("aabb")
        mgr.complete("aabb")

        assertFalse(mgr.isInGracePeriod("aabb"))
        assertEquals(0, mgr.size())
    }

    @Test
    fun multiplePeersTrackedIndependently() {
        var now = 0L
        val mgr = GracefulDrainManager(graceMillis = 10_000L, clock = { now })

        mgr.startDrain("aa")
        now = 5_000L
        mgr.startDrain("bb")

        now = 10_000L
        // aa started at 0, grace expired at 10_000 → expired
        assertFalse(mgr.isInGracePeriod("aa"))
        // bb started at 5_000, grace expires at 15_000 → still in grace
        assertTrue(mgr.isInGracePeriod("bb"))
        assertEquals(listOf("aa"), mgr.expiredPeers())
    }

    @Test
    fun clearRemovesAll() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        mgr.startDrain("aa")
        mgr.startDrain("bb")
        mgr.clear()

        assertEquals(0, mgr.size())
        assertTrue(mgr.drainingPeers().isEmpty())
    }

    @Test
    fun sizeTracksCorrectly() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        assertEquals(0, mgr.size())

        mgr.startDrain("aa")
        assertEquals(1, mgr.size())

        mgr.startDrain("bb")
        assertEquals(2, mgr.size())

        mgr.complete("aa")
        assertEquals(1, mgr.size())
    }

    @Test
    fun duplicateStartDrainDoesNotResetTimer() {
        var now = 0L
        val mgr = GracefulDrainManager(graceMillis = 10_000L, clock = { now })
        mgr.startDrain("aabb")

        now = 5_000L
        mgr.startDrain("aabb") // should be ignored

        // original timer started at 0; at 10_000 it should be expired
        now = 10_000L
        assertFalse(mgr.isInGracePeriod("aabb"))

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
        mgr.startDrain("aa", "mode_change")
        mgr.startDrain("bb", "battery_low")

        val peers = mgr.drainingPeers()
        assertEquals(2, peers.size)
        assertEquals(setOf("aa", "bb"), peers.map { it.peerIdHex }.toSet())
    }

    @Test
    fun customGracePeriod() {
        var now = 0L
        val mgr = GracefulDrainManager(graceMillis = 500L, clock = { now })
        mgr.startDrain("aabb")

        now = 499L
        assertTrue(mgr.isInGracePeriod("aabb"))

        now = 500L
        assertFalse(mgr.isInGracePeriod("aabb"))
    }

    @Test
    fun reasonStringPreserved() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        mgr.startDrain("aabb", "battery_critical")

        val entry = mgr.drainingPeers().single()
        assertEquals("battery_critical", entry.reason)
    }

    @Test
    fun defaultReasonIsPowerModeDowngrade() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        mgr.startDrain("aabb")

        assertEquals("power_mode_downgrade", mgr.drainingPeers().single().reason)
    }

    @Test
    fun isInGracePeriodReturnsFalseForUnknownPeer() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        assertFalse(mgr.isInGracePeriod("unknown"))
    }

    @Test
    fun completeOnUnknownPeerIsNoOp() {
        val mgr = GracefulDrainManager(graceMillis = 30_000L, clock = { 0L })
        mgr.startDrain("aabb")
        mgr.complete("unknown") // should not throw or affect existing entries
        assertEquals(1, mgr.size())
    }
}
