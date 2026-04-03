package io.meshlink.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PauseManagerTest {

    // ── 1. Pause/resume state ─────────────────────────────────────

    @Test
    fun initiallyNotPaused() {
        val pm = PauseManager()
        assertFalse(pm.isPaused)
    }

    @Test
    fun pauseSetsState() {
        val pm = PauseManager()
        pm.pause()
        assertTrue(pm.isPaused)
    }

    @Test
    fun resumeClearsState() {
        val pm = PauseManager()
        pm.pause()
        pm.resume()
        assertFalse(pm.isPaused)
    }

    // ── 2. Send queue ─────────────────────────────────────────────

    @Test
    fun queueSendAccumulates() {
        val pm = PauseManager()
        pm.pause()
        assertIs<QueueResult.Queued>(pm.queueSend(byteArrayOf(1), byteArrayOf(10)))
        assertIs<QueueResult.Queued>(pm.queueSend(byteArrayOf(2), byteArrayOf(20)))
        assertEquals(2, pm.sendQueueSize)
    }

    @Test
    fun sendQueueEvictsWhenOverCapacity() {
        val pm = PauseManager(sendQueueCapacity = 2)
        pm.queueSend(byteArrayOf(1), byteArrayOf(10))
        pm.queueSend(byteArrayOf(2), byteArrayOf(20))
        val result = pm.queueSend(byteArrayOf(3), byteArrayOf(30))
        assertIs<QueueResult.Evicted>(result)
        assertEquals(2, pm.sendQueueSize)
    }

    // ── 3. Relay queue ────────────────────────────────────────────

    @Test
    fun queueRelayAccumulates() {
        val pm = PauseManager()
        pm.pause()
        assertIs<QueueResult.Queued>(pm.queueRelay(byteArrayOf(1), byteArrayOf(10)))
        assertEquals(1, pm.relayQueueSize)
    }

    @Test
    fun relayQueueEvictsWhenOverCapacity() {
        val pm = PauseManager(relayQueueCapacity = 1)
        pm.queueRelay(byteArrayOf(1), byteArrayOf(10))
        val result = pm.queueRelay(byteArrayOf(2), byteArrayOf(20))
        assertIs<QueueResult.Evicted>(result)
        assertEquals(1, pm.relayQueueSize)
    }

    // ── 4. Resume returns snapshot ────────────────────────────────

    @Test
    fun resumeReturnsBothQueues() {
        val pm = PauseManager()
        pm.pause()
        pm.queueSend(byteArrayOf(1), byteArrayOf(10))
        pm.queueSend(byteArrayOf(2), byteArrayOf(20))
        pm.queueRelay(byteArrayOf(3), byteArrayOf(30))

        val snapshot = pm.resume()
        assertEquals(2, snapshot.pendingSends.size)
        assertEquals(1, snapshot.pendingRelays.size)
        assertTrue(byteArrayOf(10).contentEquals(snapshot.pendingSends[0].second))
        assertTrue(byteArrayOf(30).contentEquals(snapshot.pendingRelays[0].frame))

        // Queues are cleared after resume
        assertEquals(0, pm.sendQueueSize)
        assertEquals(0, pm.relayQueueSize)
    }

    @Test
    fun resumeWithEmptyQueues() {
        val pm = PauseManager()
        pm.pause()
        val snapshot = pm.resume()
        assertTrue(snapshot.pendingSends.isEmpty())
        assertTrue(snapshot.pendingRelays.isEmpty())
    }

    // ── 5. Clear ──────────────────────────────────────────────────

    @Test
    fun clearResetsQueues() {
        val pm = PauseManager()
        pm.queueSend(byteArrayOf(1), byteArrayOf(10))
        pm.queueRelay(byteArrayOf(2), byteArrayOf(20))
        pm.clear()
        assertEquals(0, pm.sendQueueSize)
        assertEquals(0, pm.relayQueueSize)
    }

    // ── 6. Drain send queue ───────────────────────────────────────

    @Test
    fun drainSendQueueReturnsAndClears() {
        val pm = PauseManager()
        pm.queueSend(byteArrayOf(1), byteArrayOf(10))
        pm.queueSend(byteArrayOf(2), byteArrayOf(20))
        val drained = pm.drainSendQueue()
        assertEquals(2, drained.size)
        assertEquals(0, pm.sendQueueSize)
    }
}
