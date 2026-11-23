package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReadReceiptRequest
import ch.trancee.meshlink.SendOptions
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

/**
 * An `unsecured` node's end of its messages and their delivery and read receipts (spec "Containers",
 * "Delivery receipts", "Read receipts").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnsecuredEndTest {
    private val sent = mutableListOf<Pair<NodeId, List<Tombstone>>>()
    private val delivered = mutableListOf<Pair<NodeId, List<ByteArray>>>()
    private val readSent = mutableListOf<Pair<NodeId, List<ByteArray>>>()
    private val read = mutableListOf<Pair<NodeId, List<ByteArray>>>()

    private class Node(
        val end: UnsecuredEnd,
        val inbox: Inbox,
        val nodeId: NodeId,
    )

    private fun TestScope.node(n: Int): Node {
        val inbox = Inbox()
        val send = { to: NodeId, tombstones: List<Tombstone>, _: UInt -> sent += to to tombstones }
        val arrived = { from: NodeId, ids: List<ByteArray> -> delivered += from to ids }
        val reads = reads({ to, ids -> readSent += to to ids }) { from, ids -> read += from to ids }
        val receipts = Receipts(SchedulerClock(testScheduler), backgroundScope, send, arrived, reads)
        receipts.begin()
        val end = UnsecuredEnd(SodiumCryptoProvider, SchedulerClock(testScheduler), inbox, receipts, {}) {}
        end.begin(nodeId(n).toByteArray())
        return Node(end, inbox, nodeId(n))
    }

    private fun TestScope.nowSeconds() = SchedulerClock(testScheduler).wallClockMillis() / 1_000

    private fun TestScope.message(
        from: Node,
        to: Node,
        readReceipt: ReadReceiptRequest = ReadReceiptRequest.DoNotRequest,
    ): Pair<Draft, ByteArray> {
        val status = MutableStateFlow<DeliveryStatus>(DeliveryStatus.Queued)
        val options = SendOptions(readReceipt = readReceipt)
        val entry = Outbox.Entry(to.nodeId, PAYLOAD, nowSeconds() + 60, status, 0, options)
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

    @Test
    fun aMessageMarkedReadThatAskedIsListedInItsSendersReadReceiptAndOneThatDidNotIsNot() =
        runTest {
            // Arrange
            val (alice, bob) = node(1) to node(2)
            val (asked, askedFrame) = message(alice, bob, ReadReceiptRequest.Request)
            val (_, plainFrame) = message(alice, bob)
            bob.end.deliver(askedFrame, nowSeconds())
            bob.end.deliver(plainFrame, nowSeconds())
            val first = bob.inbox.messages.first()
            val second = bob.inbox.messages.first()

            // Act
            first.markRead()
            second.markRead()
            advanceTimeBy(2_001)

            // Assert
            assertEquals(listOf(true, false), listOf(first.readReceiptRequested, second.readReceiptRequested))
            assertEquals(listOf(alice.nodeId), readSent.map { it.first })
            assertContentEquals(asked.messageId, readSent.single().second.single())
        }

    @Test
    fun aReadReceiptFromTheDestinationReachesTheOriginAndAnInvalidOneDoesNot() =
        runTest {
            // Arrange
            val (alice, bob) = node(1) to node(2)
            val id = ByteArray(12) { 7 }
            val outgoing = bob.end.acknowledger.readReceipt(alice.nodeId, listOf(id), (nowSeconds() + 60).toUInt())

            // Act
            alice.end.acknowledger.read(outgoing.frame(3), nowSeconds())
            alice.end.acknowledger.read(byteArrayOf(ReadReceipt.TYPE.toByte()), nowSeconds())
            bob.end.acknowledger.read(outgoing.frame(3), nowSeconds())

            // Assert
            assertEquals(listOf(bob.nodeId), read.map { it.first })
            assertContentEquals(id, read.single().second.single())
            assertContentEquals(alice.end.tag(), outgoing.destination)
            assertContentEquals(bob.end.tag(), outgoing.origin)
            assertEquals(3, (ReadReceipt.decode(outgoing.frame(3)) as Decoded.Valid).value.header.hopLimit)
        }

    @Test
    fun aReadReceiptListingNoMessagesExpiredOrExpiringTooLateIsIgnored() =
        runTest {
            // Arrange
            val (alice, bob) = node(1) to node(2)
            val id = ByteArray(12) { 7 }
            val expiry = (nowSeconds() + 60).toUInt()
            val expired =
                bob.end.acknowledger
                    .readReceipt(alice.nodeId, listOf(id), expiry)
                    .frame(3)
            val skew = UnsecuredMessaging.CLOCK_SKEW_SECONDS
            val late = (nowSeconds() + UnsecuredMessaging.LIFETIME_MAX_SECONDS + skew + 1).toUInt()
            val frame = { at: UInt, extensions: List<Tlv> ->
                val header = UnicastHeader(3, id, at, alice.end.tag(), bob.end.tag())
                val content = ReceiptContent(id, at, extensions).encode(secured = false)
                ReadReceipt(header, Container.Unsecured(bob.nodeId.toByteArray(), content)).encode()
            }

            // Act
            alice.end.acknowledger.read(frame(expiry, emptyList()), nowSeconds())
            alice.end.acknowledger.read(frame(late, listOf(Tlv(MESSAGE_IDS, id))), nowSeconds())
            alice.end.acknowledger.read(expired, expiry.toLong() + skew + 1)

            // Assert
            assertEquals(emptyList(), read)
        }

    private companion object {
        // spec "Read receipts": the `MESSAGE_IDS` extension.
        const val MESSAGE_IDS = 0x83

        val PAYLOAD = "hello".encodeToByteArray()
    }
}

/** Receipts on this test's scheduler that send and hand on nothing. */
internal fun <K : Any> TestScope.receipts(): Receipts<K> =
    Receipts<K>(SchedulerClock(testScheduler), backgroundScope, { _, _, _ -> }, { _, _ -> }, reads()).also {
        it.begin()
    }

/** Read receipts on this test's scheduler, allowed, that go to [send] and arrive at [arrived]. */
internal fun TestScope.reads(
    send: (NodeId, List<ByteArray>) -> Unit = { _, _ -> },
    arrived: (NodeId, List<ByteArray>) -> Unit = { _, _ -> },
): Reads = Reads(backgroundScope, allowed = true, send, arrived)
