package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A recipient's batching of read receipts, with a recording send.
@OptIn(ExperimentalCoroutinesApi::class)
class ReadsTest {
    private val sent = mutableListOf<Triple<Long, NodeId, List<Int>>>()
    private val arrived = mutableListOf<Pair<NodeId, Int>>()

    private fun TestScope.reads(allowed: Boolean = true): Reads {
        val reads =
            Reads(backgroundScope, allowed, { to, ids ->
                sent += Triple(testScheduler.currentTime, to, ids.map { it[0].toInt() })
            }) { from, ids -> arrived += from to ids.size }
        reads.begin()
        return reads
    }

    private fun delivery(
        id: Int,
        readReceipt: Boolean = true,
    ): Delivery = Delivery(PEER.toByteArray(), byteArrayOf(9), Tombstone(id(id), ByteArray(16)), 0u, readReceipt)

    @Test
    fun aSendersReadsGoInOneReceiptTwoSecondsAfterTheFirstAndEachSenderHasItsOwn() =
        runTest {
            // Arrange
            val reads = reads()
            val (first, second) = reads.message(PEER, delivery(1)) to reads.message(PEER, delivery(2))
            val other = reads.message(OTHER, delivery(3))

            // Act
            first.markRead()
            runCurrent()
            advanceTimeBy(500)
            second.markRead()
            other.markRead()
            advanceTimeBy(1_499)
            runCurrent()
            val early = sent.toList()
            advanceTimeBy(501)
            runCurrent()

            // Assert
            assertEquals(emptyList(), early)
            assertEquals(listOf(Triple(2_000L, PEER, listOf(1, 2)), Triple(2_500L, OTHER, listOf(3))), sent)
        }

    @Test
    fun sixtyFourReadsGoInOneReceiptAtOnceAndTheSixtyFifthInTheNext() =
        runTest {
            // Arrange
            val reads = reads()
            val messages = List(ReadReceipt.IDS_MAX + 1) { reads.message(PEER, delivery(it)) }

            // Act
            messages.forEach { it.markRead() }
            runCurrent()
            val full = sent.toList()
            advanceTimeBy(2_000)
            runCurrent()

            // Assert
            assertEquals(listOf(Triple(0L, PEER, List(ReadReceipt.IDS_MAX) { it })), full)
            assertEquals(Triple(2_000L, PEER, listOf(ReadReceipt.IDS_MAX)), sent.last())
            assertEquals(2, sent.size)
        }

    @Test
    fun aMessageMarkedReadTwiceIsListedOnce() =
        runTest {
            // Arrange
            val reads = reads()
            val message = reads.message(PEER, delivery(1))

            // Act
            message.markRead()
            message.markRead()
            advanceTimeBy(2_001)

            // Assert
            assertEquals(listOf(Triple(2_000L, PEER, listOf(1))), sent)
        }

    @Test
    fun anUnrequestedMessageOrOneWhoseRecipientDisallowsReadReceiptsSendsNone() =
        runTest {
            // Arrange
            val unrequested = reads().message(PEER, delivery(1, readReceipt = false))
            val disallowed = reads(allowed = false).message(PEER, delivery(2))

            // Act
            unrequested.markRead()
            disallowed.markRead()
            advanceTimeBy(2_001)

            // Assert
            assertEquals(emptyList(), sent)
            assertFalse(unrequested.readReceiptRequested)
            assertTrue(disallowed.readReceiptRequested)
            assertEquals(PEER, disallowed.sender)
            assertEquals(listOf<Byte>(9), disallowed.payload.toList())
            assertFalse(disallowed.broadcast)
        }

    @Test
    fun aReadMarkedAfterTheRunEndedIsDroppedAndOneMarkedInTheNextRunGoes() =
        runTest {
            // Arrange
            val reads = reads()
            val (dropped, kept) = reads.message(PEER, delivery(1)) to reads.message(PEER, delivery(2))
            dropped.markRead()
            reads.end()

            // Act
            dropped.markRead()
            runCurrent()
            reads.begin()
            kept.markRead()
            advanceTimeBy(2_001)

            // Assert
            assertEquals(listOf(listOf(2)), sent.map { it.third })
        }

    @Test
    fun aReadReceiptThatArrivedIsHandedOn() =
        runTest {
            // Arrange
            val reads = reads()

            // Act
            reads.arrived(PEER, listOf(id(1), id(2)))

            // Assert
            assertEquals(listOf(PEER to 2), arrived)
        }

    private companion object {
        val PEER = nodeId(1)
        val OTHER = nodeId(2)

        fun id(index: Int): ByteArray = ByteArray(12) { index.toByte() }
    }
}
