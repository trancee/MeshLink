package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** An `unsecured` node's end of its messages and their delivery receipts (spec "Containers", "Delivery receipts"). */
@OptIn(ExperimentalCoroutinesApi::class)
class UnsecuredEndTest {
    private val sent = mutableListOf<Pair<NodeId, List<Tombstone>>>()
    private val delivered = mutableListOf<Pair<NodeId, List<ByteArray>>>()

    private class Node(
        val end: UnsecuredEnd,
        val inbox: Inbox,
        val nodeId: NodeId,
    )

    private fun TestScope.node(n: Int): Node {
        val inbox = Inbox()
        val send = { to: NodeId, tombstones: List<Tombstone>, _: UInt -> sent += to to tombstones }
        val arrived = { from: NodeId, ids: List<ByteArray> -> delivered += from to ids }
        val receipts = Receipts(SchedulerClock(testScheduler), backgroundScope, send, arrived)
        receipts.begin()
        val end = UnsecuredEnd(SodiumCryptoProvider, SchedulerClock(testScheduler), inbox, receipts, {}) {}
        end.begin(nodeId(n).toByteArray())
        return Node(end, inbox, nodeId(n))
    }

    private fun TestScope.nowSeconds() = SchedulerClock(testScheduler).wallClockMillis() / 1_000

    private fun TestScope.message(
        from: Node,
        to: Node,
    ): Pair<Draft, ByteArray> {
        val status = MutableStateFlow<DeliveryStatus>(DeliveryStatus.Queued)
        val entry = Outbox.Entry(to.nodeId, PAYLOAD, nowSeconds() + 60, status, 0, directOnly = false)
        val draft = from.end.draft(entry)
        return draft to from.end.frame(draft, hopLimit = 1)
    }

    @Test
    fun anUnsecuredEndCarriesOnlyTheUnsecuredContainer() =
        runTest {
            // Arrange
            val end = UnsecuredEnd(SodiumCryptoProvider, SchedulerClock(testScheduler), Inbox(), receipts(), {}) {}

            // Act
            val carried = listOf(Container.UNSECURED, Container.IK_INITIATOR, Container.TRANSPORT).map(end::carries)

            // Assert
            assertEquals(listOf(true, false, false), carried)
        }

    @Test
    fun aMessageTheAppTakesIsAcknowledgedToItsSenderAndEachLaterCopyAgain() =
        runTest {
            // Arrange
            val (alice, bob) = node(1) to node(2)
            val (draft, frame) = message(alice, bob)
            bob.end.deliver(frame, nowSeconds())
            bob.end.acknowledger.repeated(frame, nowSeconds())
            advanceTimeBy(1_000)
            val beforeTaken = sent.size

            // Act
            bob.inbox.messages.first()
            advanceTimeBy(1_000)
            bob.end.acknowledger.repeated(frame, nowSeconds())
            bob.end.acknowledger.repeated(byteArrayOf(MessageFrame.TYPE.toByte()), nowSeconds())
            advanceTimeBy(1_000)

            // Assert
            assertEquals(0, beforeTaken)
            assertEquals(listOf(alice.nodeId, alice.nodeId), sent.map { it.first })
            sent.forEach { assertContentEquals(draft.messageId, it.second.single().messageId) }
        }

    @Test
    fun aReceiptFromTheDestinationReachesTheOriginAndAnInvalidOneDoesNot() =
        runTest {
            // Arrange
            val (alice, bob) = node(1) to node(2)
            val tombstone = Tombstone(ByteArray(12) { 7 }, ByteArray(16))
            val receipt = bob.end.acknowledger.receipt(alice.nodeId, listOf(tombstone), (nowSeconds() + 60).toUInt())

            // Act
            val parcel = bob.end.acknowledger.parcel(receipt, hopLimit = 3)
            val bytes = parcel.render()
            alice.end.acknowledger.acknowledged(bytes, nowSeconds())
            alice.end.acknowledger.acknowledged(byteArrayOf(DeliveryReceipt.TYPE.toByte()), nowSeconds())
            runCurrent()

            // Assert
            assertEquals(listOf(bob.nodeId), delivered.map { it.first })
            assertContentEquals(tombstone.messageId, delivered.single().second.single())
            assertContentEquals(alice.end.tag(), bob.end.acknowledger.destination(receipt))
            assertEquals(false, parcel.relayed)
            assertEquals(3, (DeliveryReceipt.decode(bytes) as Decoded.Valid).value.header.hopLimit)
        }

    private companion object {
        val PAYLOAD = "hello".encodeToByteArray()
    }
}

/** Receipts on this test's scheduler that send and hand on nothing. */
internal fun <K : Any> TestScope.receipts(): Receipts<K> =
    Receipts<K>(SchedulerClock(testScheduler), backgroundScope, { _, _, _ -> }, { _, _ -> }).also { it.begin() }
