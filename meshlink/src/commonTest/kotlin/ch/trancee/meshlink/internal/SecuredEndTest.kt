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

/** A secured node's end of its first messages (spec "First messages" and "Static-key rotation"). */
@OptIn(ExperimentalCoroutinesApi::class)
class SecuredEndTest {
    private val crypto = SodiumCryptoProvider
    private val tags = EndToEndTags(Derivations(crypto))
    private val neighbor = nodeId(1)

    // A secured node of identity seed [seed]: its end, the lanes and inbox it uses, and whom it heard from.
    private class Node(
        val end: SecuredEnd,
        val lookups: Lookups,
        val lanes: Lanes,
        val inbox: Inbox,
        val keys: SecuredKeys,
        val heard: MutableList<NodeId>,
    ) {
        val nodeId = NodeId(keys.nodeId)
    }

    private fun TestScope.node(
        seed: Int,
        certified: Boolean = true,
    ): Node {
        val clock = SchedulerClock(testScheduler)
        val lanes = Lanes().apply { open(neighbor) }
        val lookups = Lookups(crypto, clock, backgroundScope, WireProfile.CLASSICAL, lanes) { DensityClass.MEDIUM }
        lookups.begin {}
        val inbox = Inbox()
        val heard = mutableListOf<NodeId>()
        val end = SecuredEnd(crypto, clock, inbox, lookups, { heard += it }) {}
        val keys = securedKeys(clock, seed)
        if (certified) end.certify(keys)
        return Node(end, lookups, lanes, inbox, keys, heard)
    }

    private fun TestScope.entry(
        to: NodeId,
        payload: ByteArray = PAYLOAD,
    ): Outbox.Entry {
        val now = SchedulerClock(testScheduler).wallClockMillis()
        val status = MutableStateFlow<DeliveryStatus>(DeliveryStatus.Queued)
        return Outbox.Entry(to, payload, now / 1_000 + 60, status, now + 600_000, directOnly = false)
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
            alice.end.certify(earlier)
            val graceEnd = earlier.certificate.notAfter.toLong() + GRACE_SECONDS
            val publicTag = { tags.publicTag(earlier.static.publicKey, epoch()) }

            // Act
            advanceTimeBy((graceEnd - nowSeconds() - 1) * 1_000)
            alice.end.certify(securedKeys(clock, seed = 1))
            val inGrace = alice.end.owns(publicTag(), nowSeconds())
            advanceTimeBy(1_000)
            alice.end.certify(securedKeys(clock, seed = 1))
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
    fun aSecuredEndTakesNoRepeatOrReceiptBeforeItHasSessions() =
        runTest {
            // Arrange
            val (alice, bob) = node(1) to node(2)
            knows(alice, bob)
            val frame = alice.end.frame(assertNotNull(alice.end.draft(entry(bob.nodeId))), hopLimit = 1)
            bob.end.deliver(frame, nowSeconds())

            // Act
            bob.end.acknowledger.repeated(frame, nowSeconds())
            bob.end.acknowledger.acknowledged(frame, nowSeconds())

            // Assert
            assertEquals(1, bob.heard.size)
        }

    private companion object {
        val PAYLOAD = "hello".encodeToByteArray()
        const val GRACE_SECONDS = 3_630L
        const val KEY = 32
    }
}
