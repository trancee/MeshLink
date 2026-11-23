package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReceivedMessage
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** A secured node's end of its messages (spec "First messages", "Sessions" and "Static-key rotation"). */
@OptIn(ExperimentalCoroutinesApi::class)
class SecuredEndTest {
    private val crypto = SodiumCryptoProvider
    private val tags = EndToEndTags(Derivations(crypto))
    private val neighbor = nodeId(1)

    // A secured node of identity seed [seed]: its end, the lanes and inbox it uses, whom it heard from,
    // the peers it opened sessions with, the receipts it sent, and those that arrived for its messages.
    private class Node(
        val keys: SecuredKeys,
    ) {
        lateinit var end: SecuredEnd
        lateinit var lookups: Lookups
        val lanes = Lanes()
        val inbox = Inbox()
        val heard = mutableListOf<NodeId>()
        val opened = mutableListOf<NodeId>()
        val replies = mutableListOf<Outgoing>()
        val arrived = mutableListOf<Pair<NodeId, List<List<Byte>>>>()
        val nodeId = NodeId(keys.nodeId)
    }

    private fun TestScope.node(
        seed: Int,
        certified: Boolean = true,
    ): Node {
        val clock = SchedulerClock(testScheduler)
        val node = Node(securedKeys(clock, seed))
        node.lanes.open(neighbor)
        node.lookups =
            Lookups(crypto, clock, backgroundScope, WireProfile.CLASSICAL, node.lanes) { DensityClass.MEDIUM }
        node.lookups.begin {}
        val receipts =
            Receipts<Reply>(clock, backgroundScope, { reply, tombstones, expiry ->
                node.end.acknowledger.reply(reply, tombstones, expiry)
            }, { from, ids -> node.arrived += from to ids.map { it.toList() } }) { it is Reply.First }
        receipts.begin()
        val hooks = SecuredHooks({ node.heard += it }, {}, { node.opened += it }) { node.replies += it }
        node.end = SecuredEnd(crypto, clock, node.inbox, node.lookups, receipts, hooks)
        if (certified) node.end.keys.certify(node.keys)
        return node
    }

    // [from] sends [to] a first message, which [to] opens and its app takes: [to]'s answer goes.
    private suspend fun TestScope.firstTaken(
        from: Node,
        to: Node,
    ): Pair<Draft, Outgoing> {
        knows(from, to)
        val draft = assertNotNull(from.end.draft(entry(to.nodeId)))
        assertTrue(to.end.deliver(from.end.frame(draft, hopLimit = 5), nowSeconds()))
        to.inbox.messages.first()
        runCurrent()
        return draft to to.replies.last()
    }

    // [from] and [to] open a session: [to] answers [from]'s first message and [from] adopts the answer.
    private suspend fun TestScope.session(
        from: Node,
        to: Node,
    ) {
        val (_, answer) = firstTaken(from, to)
        from.end.acknowledger.acknowledged(answer.frame(5), nowSeconds())
    }

    private fun TestScope.entry(
        to: NodeId,
        payload: ByteArray = PAYLOAD,
    ): Outbox.Entry {
        val now = SchedulerClock(testScheduler).wallClockMillis()
        val status = MutableStateFlow<DeliveryStatus>(DeliveryStatus.Queued)
        return Outbox.Entry(to, payload, now / 1_000 + LIFETIME, status, now + 600_000, directOnly = false)
    }

    private fun TestScope.nowSeconds() = SchedulerClock(testScheduler).wallClockMillis() / 1_000

    private fun TestScope.epoch() = Derivations.epoch(nowSeconds())

    // [from] knows [to]'s certificate, as a link or lookup taught it.
    private fun knows(
        from: Node,
        to: Node,
    ) = from.lookups.linked(to.nodeId, to.keys.certificate)

    @Test
    fun aNodeWithoutKeysDraftsNothing() =
        runTest {
            // Arrange
            val alice = node(1, certified = false)
            val bob = node(2)
            knows(alice, bob)

            // Act
            val draft = alice.end.draft(entry(bob.nodeId))

            // Assert
            assertNull(draft)
        }

    @Test
    fun aCertificateWhoseKeyCannotBeSealedToDraftsNothing() =
        runTest {
            // Arrange
            val alice = node(1)
            val bob = node(2)
            val clock = SchedulerClock(testScheduler)
            val lowOrder = IdentityCertificate.issue(crypto, clock, identityKey(2), StaticKeys(ByteArray(KEY), null))
            alice.lookups.linked(bob.nodeId, lowOrder)

            // Act
            val draft = alice.end.draft(entry(bob.nodeId))

            // Assert
            assertNull(draft)
        }

    @Test
    fun aMessageToANodeWhoseCertificateIsNotHeldWaitsAndLooksItUp() =
        runTest {
            // Arrange
            val alice = node(1)
            val bob = node(2)

            // Act
            val unknown = alice.end.draft(entry(bob.nodeId))
            runCurrent()
            val requests = alice.lanes.drain(neighbor)
            knows(alice, bob)
            val known = alice.end.draft(entry(bob.nodeId))

            // Assert
            assertNull(unknown)
            assertEquals(1, requests.size)
            assertNotNull(known)
        }

    @Test
    fun aDraftIsAddressedToThePublicTagOfTheDestinationsCertificateFromAConversationTagTheNodeOwns() =
        runTest {
            // Arrange
            val alice = node(1)
            val bob = node(2)
            knows(alice, bob)

            // Act
            val draft = assertNotNull(alice.end.draft(entry(bob.nodeId)))

            // Assert
            assertContentEquals(tags.publicTag(bob.keys.static.publicKey, epoch()), alice.end.destination(draft))
            val origin = alice.end.origin(draft)
            assertTrue(alice.end.owns(origin, nowSeconds()))
            assertFalse(bob.end.owns(origin, nowSeconds()))
        }

    @Test
    fun onlyOneFirstMessageToADestinationIsOutstandingUntilItIsDone() =
        runTest {
            // Arrange
            val alice = node(1)
            val bob = node(2)
            val carol = node(3)
            knows(alice, bob)
            knows(alice, carol)
            val first = entry(bob.nodeId)
            alice.end.draft(first)

            // Act
            val second = alice.end.draft(entry(bob.nodeId))
            val other = alice.end.draft(entry(carol.nodeId))
            first.done = true
            val afterDone = alice.end.draft(entry(bob.nodeId))

            // Assert
            assertNull(second)
            assertNotNull(other)
            assertNotNull(afterDone)
        }

    @Test
    fun theDestinationOpensAFirstMessageHearsTheSenderAndHoldsItsCertificate() =
        runTest {
            // Arrange
            val alice = node(1)
            val bob = node(2)
            val carol = node(3)
            knows(alice, bob)
            val draft = assertNotNull(alice.end.draft(entry(bob.nodeId)))
            val frame = alice.end.frame(draft, hopLimit = 5)

            // Act
            val strayed = carol.end.deliver(frame, nowSeconds())
            val delivered = bob.end.deliver(frame, nowSeconds())

            // Assert
            assertFalse(strayed)
            assertTrue(delivered)
            val message: ReceivedMessage = bob.inbox.messages.first()
            assertEquals(alice.nodeId, message.sender)
            assertContentEquals(PAYLOAD, message.payload)
            assertFalse(message.broadcast)
            assertEquals(listOf(alice.nodeId), bob.heard)
            assertNotNull(bob.lookups.certificate(alice.nodeId))
            assertEquals(emptyList(), carol.heard)
        }

    @Test
    fun theReplyToTagOfAConversationIsOwnedUntilItsMessageExpiresPastTheClockSkew() =
        runTest {
            // Arrange
            val alice = node(1)
            val bob = node(2)
            knows(alice, bob)
            val draft = assertNotNull(alice.end.draft(entry(bob.nodeId)))
            val origin = alice.end.origin(draft)
            val lastSecond = draft.expiry.toLong() + UnsecuredMessaging.CLOCK_SKEW_SECONDS

            // Act
            val atLast = alice.end.owns(origin, lastSecond)
            val past = alice.end.owns(origin, lastSecond + 1)

            // Assert
            assertTrue(atLast)
            assertFalse(past)
        }

    @Test
    fun anEarlierSessionsPublicTagIsOwnedThroughItsGraceAndThenForgotten() =
        runTest {
            // Arrange
            val alice = node(1, certified = false)
            val clock = SchedulerClock(testScheduler)
            val earlier = securedKeys(clock, seed = 1, validity = 1.hours)
            alice.end.keys.certify(earlier)
            val graceEnd = earlier.certificate.notAfter.toLong() + GRACE_SECONDS
            val publicTag = { tags.publicTag(earlier.static.publicKey, epoch()) }

            // Act
            advanceTimeBy((graceEnd - nowSeconds() - 1) * 1_000)
            alice.end.keys.certify(securedKeys(clock, seed = 1))
            val inGrace = alice.end.owns(publicTag(), nowSeconds())
            advanceTimeBy(1_000)
            alice.end.keys.certify(securedKeys(clock, seed = 1))
            val afterGrace = alice.end.owns(publicTag(), nowSeconds())

            // Assert
            assertTrue(inGrace)
            assertFalse(afterGrace)
        }

    @Test
    fun aSecuredEndCarriesEverySecuredContainerAndHasNoTagToAnswerFrom() =
        runTest {
            // Arrange
            val alice = node(1)

            // Act
            val containers =
                listOf(Container.IK_INITIATOR, Container.IK_RESPONDER, Container.TRANSPORT, Container.UNSECURED)
            val carried = containers.map(alice.end::carries)

            // Assert
            assertEquals(listOf(true, true, true, false), carried)
            assertNull(alice.end.tag())
        }

    @Test
    fun aTakenFirstMessagesAnswerGoesAtOnceFromTheSessionToTheReplyToTagAndOpensTheSession() =
        runTest {
            // Arrange
            val (alice, bob) = node(1) to node(2)

            // Act
            val (draft, answer) = firstTaken(alice, bob)

            // Assert
            assertContentEquals(alice.end.origin(draft), answer.destination)
            assertTrue(alice.end.owns(answer.destination, nowSeconds()))
            assertTrue(bob.end.owns(answer.origin, nowSeconds()))
            assertEquals(listOf(alice.nodeId), bob.opened)
            val reply = bob.end.draft(entry(alice.nodeId))
            assertTrue(assertNotNull(reply).sealer is Conversation)
        }

    @Test
    fun anAdoptedAnswerDeliversTheFirstMessageOpensTheSessionAndLaterCopiesAreIgnored() =
        runTest {
            // Arrange
            val (alice, bob) = node(1) to node(2)
            val (draft, answer) = firstTaken(alice, bob)
            val frame = answer.frame(5)

            // Act
            alice.end.acknowledger.acknowledged(frame, nowSeconds())
            alice.end.acknowledger.acknowledged(frame, nowSeconds())

            // Assert
            assertEquals(listOf(bob.nodeId to listOf(draft.messageId.toList())), alice.arrived)
            assertEquals(listOf(bob.nodeId), alice.opened)
        }

    @Test
    fun anAnswerToAnUnknownFirstMessageIsIgnored() =
        runTest {
            // Arrange
            val (alice, bob, carol) = Triple(node(1), node(2), node(3))
            knows(carol, bob)
            val (_, answer) = firstTaken(alice, bob)

            // Act
            carol.end.acknowledger.acknowledged(answer.frame(5), nowSeconds())

            // Assert
            assertEquals(emptyList(), carol.arrived)
            assertEquals(emptyList(), carol.opened)
        }

    @Test
    fun aSessionCarriesLaterMessagesBothWaysToConversationTagsAndTheirReceiptsInIt() =
        runTest {
            // Arrange
            val (alice, bob) = node(1) to node(2)
            session(alice, bob)
            val draft = assertNotNull(alice.end.draft(entry(bob.nodeId, SECOND)))
            val destination = alice.end.destination(draft)

            // Act
            val delivered = bob.end.deliver(alice.end.frame(draft, hopLimit = 5), nowSeconds())
            bob.inbox.messages.first()
            advanceTimeBy(501)
            val receipt = bob.replies.last()
            alice.end.acknowledger.acknowledged(receipt.frame(5), nowSeconds())

            // Assert
            assertTrue(delivered)
            assertFalse(destination.contentEquals(tags.publicTag(bob.keys.static.publicKey, epoch())))
            assertTrue(bob.end.owns(destination, nowSeconds()))
            assertContentEquals(alice.end.origin(draft), receipt.destination)
            assertEquals(bob.nodeId to listOf(draft.messageId.toList()), alice.arrived.last())
            assertEquals(listOf(alice.nodeId, alice.nodeId), bob.heard)
        }

    @Test
    fun aLaterCopyOfATakenFirstMessageGetsTheSameAnswerAndOfASessionMessageAnotherReceipt() =
        runTest {
            // Arrange
            val (alice, bob) = node(1) to node(2)
            val (first, answer) = firstTaken(alice, bob)
            alice.end.acknowledger.acknowledged(answer.frame(5), nowSeconds())
            val second = assertNotNull(alice.end.draft(entry(bob.nodeId, SECOND)))
            bob.end.deliver(alice.end.frame(second, hopLimit = 5), nowSeconds())
            bob.inbox.messages.first()
            advanceTimeBy(501)

            // Act
            bob.end.acknowledger.repeated(alice.end.frame(first, hopLimit = 5), nowSeconds())
            bob.end.acknowledger.repeated(alice.end.frame(second, hopLimit = 5), nowSeconds())
            advanceTimeBy(501)

            // Assert
            assertEquals(4, bob.replies.size)
            assertContentEquals(answer.frame(5), bob.replies[2].frame(5))
            assertFalse(bob.replies[1].messageId.contentEquals(bob.replies[3].messageId))
            assertEquals(listOf(alice.nodeId), bob.opened)
        }

    @Test
    fun aCopyOfAnUntakenOrForgottenMessageOrAnUndecodableFrameGetsNoReceipt() =
        runTest {
            // Arrange
            val (alice, bob) = node(1) to node(2)
            knows(alice, bob)
            val frame = alice.end.frame(assertNotNull(alice.end.draft(entry(bob.nodeId))), hopLimit = 5)
            bob.end.deliver(frame, nowSeconds())

            // Act
            bob.end.acknowledger.repeated(frame, nowSeconds())
            bob.end.acknowledger.repeated(byteArrayOf(MessageFrame.TYPE.toByte()), nowSeconds())
            bob.end.acknowledger.repeated(frame, nowSeconds() + LIFETIME + UnsecuredMessaging.CLOCK_SKEW_SECONDS + 1)
            runCurrent()

            // Assert
            assertEquals(emptyList(), bob.replies)
        }

    @Test
    fun anUndecodableOrForeignFrameIsNeitherDeliveredNorTakenAsAReceipt() =
        runTest {
            // Arrange
            val (alice, bob, carol) = Triple(node(1), node(2), node(3))
            session(alice, bob)
            val draft = assertNotNull(alice.end.draft(entry(bob.nodeId, SECOND)))
            val frame = alice.end.frame(draft, hopLimit = 5)

            // Act
            val undecodable = bob.end.deliver(byteArrayOf(MessageFrame.TYPE.toByte()), nowSeconds())
            val foreign = carol.end.deliver(frame, nowSeconds())
            alice.end.acknowledger.acknowledged(byteArrayOf(DeliveryReceipt.TYPE.toByte()), nowSeconds())
            carol.end.acknowledger.acknowledged(frame, nowSeconds())

            // Assert
            assertFalse(undecodable)
            assertFalse(foreign)
            assertEquals(1, alice.arrived.size)
        }

    @Test
    fun aTamperedSessionFrameOrReceiptOrAnswerOpensNothingAndAReceiptToAnotherNodeIsIgnored() =
        runTest {
            // Arrange
            val (alice, bob, carol) = Triple(node(1), node(2), node(3))
            val (_, answer) = firstTaken(alice, bob)
            alice.end.acknowledger.acknowledged(answer.frame(5).tampered(), nowSeconds())
            val adopted = alice.arrived.size
            alice.end.acknowledger.acknowledged(answer.frame(5), nowSeconds())
            val draft = assertNotNull(alice.end.draft(entry(bob.nodeId, SECOND)))
            bob.end.deliver(alice.end.frame(draft, hopLimit = 5), nowSeconds())
            bob.inbox.messages.first()
            advanceTimeBy(501)
            val receipt = bob.replies.last().frame(5)

            // Act
            val tampered = bob.end.deliver(alice.end.frame(draft, hopLimit = 5).tampered(), nowSeconds())
            alice.end.acknowledger.acknowledged(receipt.tampered(), nowSeconds())
            carol.end.acknowledger.acknowledged(receipt, nowSeconds())

            // Assert
            assertEquals(0, adopted)
            assertFalse(tampered)
            assertEquals(1, alice.arrived.size)
            assertEquals(emptyList(), carol.arrived)
        }

    @Test
    fun aSessionMessageLapsesAtItsSecondRetryAndTheNextIsAFirstMessage() =
        runTest {
            // Arrange
            val (alice, bob) = node(1) to node(2)
            session(alice, bob)
            val first = alice.end.draft(entry(bob.nodeId)).let { assertNotNull(it) }
            alice.arrived.clear()
            val draft = assertNotNull(alice.end.draft(entry(bob.nodeId, SECOND)))

            // Act
            val once = alice.end.lapsed(draft, retries = 1)
            val twice = alice.end.lapsed(draft, retries = 2)
            val next = assertNotNull(alice.end.draft(entry(bob.nodeId)))

            // Assert
            assertFalse(once)
            assertTrue(twice)
            assertTrue(first.sealer is Conversation)
            assertFalse(next.sealer is Conversation)
            assertFalse(alice.end.lapsed(next, retries = 2))
        }

    @Test
    fun aSessionThatCanSealNoMoreFallsBackToAFirstMessageAndSendsNoReceipt() =
        runTest {
            // Arrange
            val (alice, bob) = node(1) to node(2)
            knows(alice, bob)
            val key = ByteArray(KEY) { 1 }
            val spent = EndToEndSession(key, key, CipherState(crypto, key, ULong.MAX_VALUE), CipherState(crypto, key))
            val conversation = Conversation(crypto, spent, key, alice.keys.nodeId, bob.keys.nodeId, initiator = true)
            alice.end.sessions.open(conversation, nowSeconds())

            // Act
            val draft = assertNotNull(alice.end.draft(entry(bob.nodeId)))
            alice.end.acknowledger.reply(Reply.Session(conversation), listOf(TOMBSTONE), EXPIRY)

            // Assert
            assertFalse(draft.sealer is Conversation)
            assertEquals(emptyList(), alice.replies)
        }

    // A copy of this frame whose last byte, in the AEAD tag, is flipped.
    private fun ByteArray.tampered() = copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }

    private companion object {
        val PAYLOAD = "hello".encodeToByteArray()
        const val GRACE_SECONDS = 3_630L
        const val KEY = 32
        const val LIFETIME = 60L
        val SECOND = "second".encodeToByteArray()
        val TOMBSTONE = Tombstone(ByteArray(12), ByteArray(16))
        const val EXPIRY = 4_000_000_000u
    }
}
