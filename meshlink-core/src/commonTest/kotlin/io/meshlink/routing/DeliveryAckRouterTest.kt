package io.meshlink.routing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryAckRouterTest {

    private val peerA = byteArrayOf(0x0A, 0x0B, 0x0C)
    private val peerB = byteArrayOf(0x1A, 0x1B, 0x1C)
    private val ackPayload = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    @Test
    fun recordAndRetrieveSourcePeer() {
        val router = DeliveryAckRouter()
        router.recordSource("msg1", peerA)
        assertContentEquals(peerA, router.getSource("msg1"))
    }

    @Test
    fun getSourceReturnsNullForUnknownMessage() {
        val router = DeliveryAckRouter()
        assertNull(router.getSource("unknown"))
    }

    @Test
    fun routeAckSucceedsOnFirstAttempt() = runTest {
        val router = DeliveryAckRouter()
        router.recordSource("msg1", peerA)

        var sendCount = 0
        val result = router.routeAck(this, "msg1", ackPayload) { _, _ ->
            sendCount++
        }

        advanceUntilIdle()
        assertTrue(result)
        assertEquals(1, sendCount)
    }

    @Test
    fun routeAckRetriesOnFailureThenSucceeds() = runTest {
        val router = DeliveryAckRouter()
        router.recordSource("msg1", peerA)

        var sendCount = 0
        val result = router.routeAck(this, "msg1", ackPayload) { _, _ ->
            sendCount++
            if (sendCount == 1) throw RuntimeException("peer offline")
        }

        assertTrue(result)

        // First attempt fires immediately and fails
        advanceTimeBy(1L)
        assertEquals(1, sendCount)

        // After retry delay, second attempt succeeds
        advanceTimeBy(2_000L)
        advanceUntilIdle()
        assertEquals(2, sendCount)
    }

    @Test
    fun routeAckFailsAllAttempts() = runTest {
        val router = DeliveryAckRouter()
        router.recordSource("msg1", peerA)

        var sendCount = 0
        val result = router.routeAck(this, "msg1", ackPayload) { _, _ ->
            sendCount++
            throw RuntimeException("peer offline")
        }

        assertTrue(result)
        advanceTimeBy(5_000L)
        advanceUntilIdle()
        assertEquals(2, sendCount)
    }

    @Test
    fun routeAckReturnsFalseWhenNoReversePath() = runTest {
        val router = DeliveryAckRouter()

        var sendCount = 0
        val result = router.routeAck(this, "unknown", ackPayload) { _, _ ->
            sendCount++
        }

        advanceUntilIdle()
        assertFalse(result)
        assertEquals(0, sendCount)
    }

    @Test
    fun removeDeletesEntry() {
        val router = DeliveryAckRouter()
        router.recordSource("msg1", peerA)
        router.remove("msg1")
        assertNull(router.getSource("msg1"))
    }

    @Test
    fun clearRemovesAllEntries() {
        val router = DeliveryAckRouter()
        router.recordSource("msg1", peerA)
        router.recordSource("msg2", peerB)
        router.clear()
        assertEquals(0, router.size())
        assertNull(router.getSource("msg1"))
        assertNull(router.getSource("msg2"))
    }

    @Test
    fun sizeTracksCorrectly() {
        val router = DeliveryAckRouter()
        assertEquals(0, router.size())
        router.recordSource("msg1", peerA)
        assertEquals(1, router.size())
        router.recordSource("msg2", peerB)
        assertEquals(2, router.size())
        router.remove("msg1")
        assertEquals(1, router.size())
    }

    @Test
    fun multipleMessagesTrackedIndependently() {
        val router = DeliveryAckRouter()
        router.recordSource("msg1", peerA)
        router.recordSource("msg2", peerB)
        assertContentEquals(peerA, router.getSource("msg1"))
        assertContentEquals(peerB, router.getSource("msg2"))
    }

    @Test
    fun overwriteSourceForSameMessageId() {
        val router = DeliveryAckRouter()
        router.recordSource("msg1", peerA)
        router.recordSource("msg1", peerB)
        assertContentEquals(peerB, router.getSource("msg1"))
        assertEquals(1, router.size())
    }

    @Test
    fun retryDelayIsRespected() = runTest {
        val router = DeliveryAckRouter(maxAttempts = 2, retryDelayMs = 2_000L)
        router.recordSource("msg1", peerA)

        var sendCount = 0
        router.routeAck(this, "msg1", ackPayload) { _, _ ->
            sendCount++
            if (sendCount == 1) throw RuntimeException("fail first")
        }

        // First attempt fires immediately
        advanceTimeBy(1L)
        assertEquals(1, sendCount)

        // Just before retry delay: still only 1 attempt
        advanceTimeBy(1_998L)
        assertEquals(1, sendCount)

        // After full retry delay: second attempt fires
        advanceTimeBy(2L)
        advanceUntilIdle()
        assertEquals(2, sendCount)
    }

    @Test
    fun routeAckReturnsTrueEvenWhenSendFails() = runTest {
        val router = DeliveryAckRouter()
        router.recordSource("msg1", peerA)

        val result = router.routeAck(this, "msg1", ackPayload) { _, _ ->
            throw RuntimeException("always fails")
        }

        advanceUntilIdle()
        // Returns true because a reverse path was found and send was attempted
        assertTrue(result)
    }

    @Test
    fun routeAckPassesCorrectPeerIdAndData() = runTest {
        val router = DeliveryAckRouter()
        router.recordSource("msg1", peerA)

        var receivedPeer: ByteArray? = null
        var receivedData: ByteArray? = null
        router.routeAck(this, "msg1", ackPayload) { peer, data ->
            receivedPeer = peer
            receivedData = data
        }

        advanceUntilIdle()
        assertContentEquals(peerA, receivedPeer)
        assertContentEquals(ackPayload, receivedData)
    }

    @Test
    fun customMaxAttemptsAndDelay() = runTest {
        val router = DeliveryAckRouter(maxAttempts = 3, retryDelayMs = 500L)
        router.recordSource("msg1", peerA)

        var sendCount = 0
        router.routeAck(this, "msg1", ackPayload) { _, _ ->
            sendCount++
            throw RuntimeException("always fails")
        }

        advanceTimeBy(1L) // first attempt
        assertEquals(1, sendCount)
        advanceTimeBy(500L) // second attempt
        assertEquals(2, sendCount)
        advanceTimeBy(500L) // third attempt
        advanceUntilIdle()
        assertEquals(3, sendCount)
    }
}
