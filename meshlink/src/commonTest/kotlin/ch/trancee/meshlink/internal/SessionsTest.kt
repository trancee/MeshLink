package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** A node's end-to-end session cache (spec "Sessions"). */
class SessionsTest {
    private val crypto = SodiumCryptoProvider
    private val epoch = Derivations.epoch(NOW)

    // Both ends of one session that [initiator] opened with [responder].
    private class Ends(
        val initiator: Conversation,
        val responder: Conversation,
    )

    private fun ends(
        initiator: NodeId,
        responder: NodeId,
    ): Ends {
        val forth = crypto.randomBytes(KEY)
        val back = crypto.randomBytes(KEY)
        val secret = crypto.randomBytes(KEY)
        val end = { sending: ByteArray, receiving: ByteArray, isInitiator: Boolean ->
            val session = EndToEndSession(ByteArray(KEY), ByteArray(KEY), cipher(sending), cipher(receiving))
            val (local, peer) = if (isInitiator) initiator to responder else responder to initiator
            Conversation(crypto, session, secret, CERTIFICATES.getValue(local), peer.toByteArray(), isInitiator)
        }
        return Ends(end(forth, back, true), end(back, forth, false))
    }

    private fun cipher(key: ByteArray) = CipherState(crypto, key)

    private fun Conversation.sealed() = assertNotNull(draft(PAYLOAD, EXPIRY))

    @Test
    fun anOpenedSessionIsCurrentForItsPeerAndOwnsItsIncomingTagsOnly() {
        // Arrange
        val sessions = Sessions()
        val session = ends(ALICE, BOB).initiator

        // Act
        sessions.open(session, NOW)

        // Assert
        assertSame(session, sessions.current(BOB, NOW))
        assertNull(sessions.current(CAROL, NOW))
        assertSame(session, sessions.owner(session.source(epoch + 1u), NOW))
        assertNull(sessions.owner(session.destination(epoch), NOW))
    }

    @Test
    fun crossedSessionsSettleOnTheLowerNodeIdsInitiatorInEitherOrder() {
        // Arrange
        val ours = ends(ALICE, BOB).initiator
        val theirs = ends(BOB, ALICE).responder
        val (first, second) = Sessions() to Sessions()

        // Act
        first.open(ours, NOW)
        first.open(theirs, NOW)
        second.open(theirs, NOW)
        second.open(ours, NOW)

        // Assert
        assertSame(ours, first.current(BOB, NOW))
        assertSame(ours, second.current(BOB, NOW))
        assertNull(first.owner(theirs.source(epoch), NOW))
    }

    @Test
    fun aDroppedSessionKeepsOpeningUntilItsSealedMessagesAreAnswered() {
        // Arrange
        val sessions = Sessions()
        val loser = ends(BOB, ALICE).responder
        sessions.open(loser, NOW)
        val (one, two) = loser.sealed() to loser.sealed()
        sessions.sealed(loser, one)
        sessions.sealed(loser, two)
        sessions.open(ends(ALICE, BOB).initiator, NOW)

        // Act
        sessions.answered(listOf(one.messageId))
        val halfAnswered = sessions.owner(loser.source(epoch), NOW)
        sessions.answered(listOf(two.messageId))
        val answered = sessions.owner(loser.source(epoch), NOW)

        // Assert
        assertSame(loser, halfAnswered)
        assertNull(answered)
    }

    @Test
    fun aDroppedSessionKeepsOpeningUntilItsSealedMessagesExpirePastTheClockSkew() {
        // Arrange
        val sessions = Sessions()
        val loser = ends(BOB, ALICE).responder
        sessions.open(loser, NOW)
        sessions.sealed(loser, loser.sealed())
        sessions.open(ends(ALICE, BOB).initiator, NOW)
        val lastSecond = EXPIRY.toLong() + UnsecuredMessaging.CLOCK_SKEW_SECONDS

        // Act
        val atLast = sessions.owner(loser.source(epoch), lastSecond)
        val past = sessions.owner(loser.source(epoch), lastSecond + 1)

        // Assert
        assertSame(loser, atLast)
        assertNull(past)
    }

    @Test
    fun aSessionOpenedBesideAnEndedOneSettlesOnlyWithTheCurrentOne() {
        // Arrange
        val sessions = Sessions()
        val loser = ends(BOB, ALICE).responder
        sessions.open(loser, NOW)
        sessions.sealed(loser, loser.sealed())
        val current = ends(ALICE, BOB).initiator
        sessions.open(current, NOW)

        // Act
        sessions.open(ends(BOB, ALICE).responder, NOW)

        // Assert
        assertSame(current, sessions.current(BOB, NOW))
        assertSame(loser, sessions.owner(loser.source(epoch), NOW))
    }

    @Test
    fun anUnheldSessionsSealsAnswersAndLapsesChangeNothing() {
        // Arrange
        val sessions = Sessions()
        val held = ends(ALICE, BOB).initiator
        sessions.open(held, NOW)
        val unheld = ends(ALICE, CAROL).initiator
        val draft = unheld.sealed()

        // Act
        sessions.sealed(unheld, draft)
        sessions.answered(listOf(draft.messageId))
        sessions.lapsed(unheld)

        // Assert
        assertSame(held, sessions.current(BOB, NOW))
        assertNull(sessions.current(CAROL, NOW))
    }

    @Test
    fun aLaterSessionOfTheSameInitiatorReplacesTheEarlierOne() {
        // Arrange
        val sessions = Sessions()
        val earlier = ends(BOB, ALICE).responder
        val later = ends(BOB, ALICE).responder
        sessions.open(earlier, NOW)

        // Act
        sessions.open(later, NOW)
        sessions.open(later, NOW)

        // Assert
        assertSame(later, sessions.current(BOB, NOW))
        assertNull(sessions.owner(earlier.source(epoch), NOW))
    }

    @Test
    fun aSessionEndsAtItsMaximumAge() {
        // Arrange
        val sessions = Sessions()
        val session = ends(ALICE, BOB).initiator
        sessions.open(session, NOW)

        // Act
        val young = sessions.current(BOB, NOW + MAX_AGE - 1)
        val old = sessions.current(BOB, NOW + MAX_AGE)

        // Assert
        assertSame(session, young)
        assertNull(old)
    }

    @Test
    fun aSessionEndsAfterItsMaximumFramesSentOrReceived() {
        // Arrange
        val (sending, receiving) = Sessions() to Sessions()
        val pair = ends(ALICE, BOB)
        sending.open(pair.initiator, NOW)
        receiving.open(pair.responder, NOW)

        // Act
        val frames = List(MAX_FRAMES - 1) { pair.initiator.frame(pair.initiator.sealed(), epoch, 1) }
        frames.forEach { assertNotNull(pair.responder.accept(it, NOW)) }
        val belowMax = sending.current(BOB, NOW) to receiving.current(ALICE, NOW)
        assertNotNull(pair.responder.accept(pair.initiator.frame(pair.initiator.sealed(), epoch, 1), NOW))

        // Assert
        assertEquals(pair.initiator to pair.responder, belowMax)
        assertNull(sending.current(BOB, NOW))
        assertNull(receiving.current(ALICE, NOW))
    }

    @Test
    fun aLapsedMessageEndsItsSessionWhichKeepsOpeningUntilTheMessageIsAnswered() {
        // Arrange
        val sessions = Sessions()
        val session = ends(ALICE, BOB).initiator
        sessions.open(session, NOW)
        val lapsed = session.sealed()
        sessions.sealed(session, lapsed)

        // Act
        sessions.lapsed(session)
        val current = sessions.current(BOB, NOW)
        val kept = sessions.owner(session.source(epoch), NOW)
        sessions.answered(listOf(lapsed.messageId))

        // Assert
        assertNull(current)
        assertSame(session, kept)
        assertNull(sessions.owner(session.source(epoch), NOW))
    }

    @Test
    fun aLapsedMessageAnsweredInTheNextSessionSettlesTheEndedOne() {
        // Arrange
        val sessions = Sessions()
        val ended = ends(ALICE, BOB).initiator
        sessions.open(ended, NOW)
        val lapsed = ended.sealed()
        sessions.sealed(ended, lapsed)
        sessions.lapsed(ended)
        val next = ends(ALICE, BOB).initiator
        sessions.open(next, NOW)

        // Act
        sessions.answered(listOf(lapsed.messageId))

        // Assert
        assertNull(sessions.owner(ended.source(epoch), NOW))
        assertSame(next, sessions.current(BOB, NOW))
    }

    @Test
    fun theLeastRecentlyUsedSessionIsEvictedBeyondTheCacheSize() {
        // Arrange
        val sessions = Sessions(capacity = 2)
        val (toBob, toCarol, toDave) = listOf(BOB, CAROL, DAVE).map { ends(ALICE, it).initiator }
        sessions.open(toBob, NOW)
        sessions.open(toCarol, NOW)
        sessions.current(BOB, NOW)

        // Act
        sessions.open(toDave, NOW)

        // Assert
        assertSame(toBob, sessions.current(BOB, NOW))
        assertNull(sessions.current(CAROL, NOW))
        assertSame(toDave, sessions.current(DAVE, NOW))
    }

    @Test
    fun clearedSessionsAreGone() {
        // Arrange
        val sessions = Sessions()
        val session = ends(ALICE, BOB).initiator
        sessions.open(session, NOW)

        // Act
        sessions.clear()

        // Assert
        assertNull(sessions.current(BOB, NOW))
        assertNull(sessions.owner(session.source(epoch), NOW))
    }

    private companion object {
        const val NOW = 1_798_762_200L
        const val KEY = 32
        const val MAX_AGE = 21_600L
        const val MAX_FRAMES = 4_096
        val EXPIRY = (NOW + 300).toUInt()
        val PAYLOAD = "hello".encodeToByteArray()

        // Four nodes' certificates by NodeId; ALICE to DAVE name them in ascending NodeId order.
        val CERTIFICATES =
            (1..4).associate { seed ->
                val identity = identityKey(seed)
                val keys = StaticKeys(ByteArray(KEY) { seed.toByte() }, null)
                val certificate = IdentityCertificate.issue(SodiumCryptoProvider, Fixed, identity, keys)
                NodeId(Derivations(SodiumCryptoProvider).nodeId(identity.publicKey)) to certificate
            }
        private val ORDERED =
            CERTIFICATES.keys.sortedWith { a, b -> LinkPolicy.compareNodeIds(a.toByteArray(), b.toByteArray()) }
        val ALICE = ORDERED[0]
        val BOB = ORDERED[1]
        val CAROL = ORDERED[2]
        val DAVE = ORDERED[3]
    }

    private object Fixed : Clock {
        override fun monotonicMillis(): Long = 0

        override fun wallClockMillis(): Long = NOW * 1_000
    }
}
