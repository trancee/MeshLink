package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// A destination's batching of delivery receipts, with a recording send.
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptsTest {
    private val sent = mutableListOf<Sent>()
    private val delivered = mutableListOf<Pair<NodeId, Int>>()

    private class Sent(
        val at: Long,
        val to: NodeId,
        val ids: List<Int>,
        val expiry: UInt,
    )

    private fun TestScope.receipts(prompt: (NodeId) -> Boolean = { false }): Receipts<NodeId> {
        val receipts =
            Receipts(SchedulerClock(testScheduler), backgroundScope, { to, tombstones, expiry ->
                sent += Sent(testScheduler.currentTime, to, tombstones.map { it.messageId[0].toInt() }, expiry)
            }, { from, ids -> delivered += from to ids.size }, prompt)
        receipts.begin()
        return receipts
    }

    private fun TestScope.expiry(seconds: Long = 60): UInt {
        val nowSeconds = SchedulerClock(testScheduler).wallClockMillis() / 1_000
        return (nowSeconds + seconds).toUInt()
    }

    private fun tombstone(id: Int) = Tombstone(ByteArray(12) { id.toByte() }, ByteArray(16))

    @Test
    fun aSendersTakenMessagesGoInOneReceiptAtMostHalfASecondAfterTheFirstWithTheLatestExpiry() =
        runTest {
            // Arrange
            val receipts = receipts()

            // Act
            receipts.taken(PEER, tombstone(1), expiry(60))
            runCurrent()
            advanceTimeBy(200)
            val latest = expiry(90)
            receipts.taken(PEER, tombstone(2), latest)
            receipts.taken(PEER, tombstone(4), expiry(45))
            receipts.taken(OTHER, tombstone(3), expiry(30))
            advanceTimeBy(299)
            runCurrent()
            val early = sent.size
            advanceTimeBy(1)
            runCurrent()

            // Assert
            assertEquals(0, early)
            assertEquals(listOf(PEER to listOf(1, 2, 4)), sent.map { it.to to it.ids })
            assertEquals(latest, sent.single().expiry)
            advanceTimeBy(200)
            runCurrent()
            assertEquals(listOf(OTHER to listOf(3)), sent.drop(1).map { it.to to it.ids })
        }

    @Test
    fun aFullBatchGoesAtOnceAndTheNextStartsAnew() =
        runTest {
            // Arrange
            val receipts = receipts()

            // Act
            repeat(DeliveryReceipt.TOMBSTONES_MAX + 1) { receipts.taken(PEER, tombstone(it), expiry()) }
            runCurrent()
            val full = sent.map { it.ids.size to it.at }
            advanceTimeBy(500)
            runCurrent()

            // Assert
            assertEquals(listOf(DeliveryReceipt.TOMBSTONES_MAX to 0L), full)
            assertEquals(listOf(1 to 500L), sent.drop(1).map { it.ids.size to it.at })
        }

    @Test
    fun aLaterCopyIsAcknowledgedAgainOnlyOnceItsMessageWasTakenAndUntilItIsForgotten() =
        runTest {
            // Arrange
            val receipts = receipts()
            receipts.again(PEER, tombstone(1), expiry())
            receipts.taken(PEER, tombstone(2), expiry(seconds = 1))
            advanceTimeBy(501)

            // Act
            receipts.again(PEER, tombstone(2), expiry(seconds = 1))
            advanceTimeBy(501)
            advanceTimeBy(31_000)
            receipts.again(PEER, tombstone(2), expiry(seconds = 1))
            advanceTimeBy(501)

            // Assert
            assertEquals(listOf(listOf(2), listOf(2)), sent.map { it.ids })
        }

    @Test
    fun aPromptReceiptGoesAloneAtOnceEachTime() =
        runTest {
            // Arrange
            val receipts = receipts { it == PEER }
            receipts.taken(OTHER, tombstone(3), expiry())

            // Act
            receipts.taken(PEER, tombstone(1), expiry())
            receipts.taken(PEER, tombstone(2), expiry())
            runCurrent()
            receipts.again(PEER, tombstone(1), expiry())

            // Assert
            assertEquals(listOf(listOf(1), listOf(2), listOf(1)), sent.map { it.ids })
            assertEquals(List(3) { 0L }, sent.map { it.at })
            advanceTimeBy(501)
            assertEquals(listOf(OTHER to listOf(3)), sent.drop(3).map { it.to to it.ids })
        }

    @Test
    fun aStoppedRunSendsNothingAndAReceiptThatArrivesIsHandedOn() =
        runTest {
            // Arrange
            val receipts = receipts()
            receipts.taken(PEER, tombstone(1), expiry())
            runCurrent()

            // Act
            receipts.end()
            repeat(DeliveryReceipt.TOMBSTONES_MAX) { receipts.taken(PEER, tombstone(it + 2), expiry()) }
            advanceTimeBy(1_000)
            receipts.arrived(PEER, listOf(ByteArray(12)))

            // Assert
            assertEquals(emptyList(), sent.map { it.ids })
            assertEquals(listOf(PEER to 1), delivered)
        }

    @Test
    fun aRestartedRunForgetsTheBatchesOfTheLast() =
        runTest {
            // Arrange
            val receipts = receipts()
            receipts.taken(PEER, tombstone(1), expiry())
            runCurrent()
            receipts.end()

            // Act
            receipts.begin()
            receipts.taken(PEER, tombstone(2), expiry())
            advanceTimeBy(501)

            // Assert
            assertEquals(listOf(listOf(2)), sent.map { it.ids })
        }

    private companion object {
        val PEER = nodeId(1)
        val OTHER = nodeId(2)
    }
}
