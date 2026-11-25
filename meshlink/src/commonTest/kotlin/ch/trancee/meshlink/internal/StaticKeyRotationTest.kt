package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.SendOptions
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** A secured node's static-key rotation (spec "Static-key rotation"; ADR 0014 decision 9). */
@OptIn(ExperimentalCoroutinesApi::class)
class StaticKeyRotationTest {
    private val crypto = SodiumCryptoProvider
    private val tags = EndToEndTags(Derivations(crypto))

    @Test
    fun earlierKeysAreDeletedExactlyAtTheirGraceEndWithoutAnotherRotation() =
        runTest {
            // Arrange
            val clock = SchedulerClock(testScheduler)
            val ring = KeyRing(crypto, clock)
            val earlier = securedKeys(clock, seed = 1, validity = 1.hours)
            ring.certify(earlier)
            advanceTimeBy(30.minutes)
            ring.certify(securedKeys(clock, seed = 1))
            val graceEnd = earlier.certificate.notAfter.toLong() + GRACE_SECONDS
            val publicTag = { tags.publicTag(earlier.static.publicKey, Derivations.epoch(nowSeconds())) }

            // Act
            advanceTimeBy((graceEnd - nowSeconds() - 1) * MILLIS)
            val lastSecond = ring.owns(publicTag(), nowSeconds())
            advanceTimeBy(MILLIS)
            val atGraceEnd = ring.owns(publicTag(), nowSeconds())

            // Assert
            assertTrue(lastSecond)
            assertFalse(atGraceEnd)
            assertTrue(earlier.static.secretKey.all { it == ZERO })
        }

    @Test
    fun deletingPostQuantumKeysWipesBothTheX25519AndTheXWingSecretKey() =
        runTest {
            // Arrange
            val clock = SchedulerClock(testScheduler)
            val ring = KeyRing(crypto, clock)
            val earlier = securedKeys(clock, seed = 1, validity = 1.hours, postQuantum = true)
            ring.certify(earlier)
            ring.certify(securedKeys(clock, seed = 1, postQuantum = true))
            val graceEnd = earlier.certificate.notAfter.toLong() + GRACE_SECONDS

            // Act
            ring.prune(graceEnd)

            // Assert
            assertTrue(earlier.static.secretKey.all { it == ZERO })
            assertTrue(assertNotNull(earlier.xWing).secretKey.all { it == ZERO })
        }

    @Test
    fun aRotatingNodeIssuesACertificateEveryTwelveHoursEachValidForADay() =
        runTest {
            // Arrange
            val clock = SchedulerClock(testScheduler)
            val ring = KeyRing(crypto, clock)
            val issued = mutableListOf<IdentityCertificate>()
            val issue = {
                val credentials = LinkCredentials.issue(crypto, clock, identityKey(1), WireProfile.CLASSICAL, DIGEST)
                issued += credentials.certificate
                ring.certify(SecuredKeys(NODE_ID, credentials.static, null, credentials.certificate))
            }
            issue()

            // Act
            backgroundScope.launch { ring.rotate(issue) }
            advanceTimeBy(36.hours - 1.minutes)

            // Assert
            assertEquals(3, issued.size)
            assertEquals(listOf(RENEWAL, RENEWAL), issued.zipWithNext { a, b -> (b.notBefore - a.notBefore).toLong() })
            assertEquals(setOf(VALIDITY), issued.map { (it.notAfter - it.notBefore).toLong() }.toSet())
        }

    @Test
    fun aRotatingNodeDeletesEachKeyExactlyAtItsGraceEndAndNotBefore() =
        runTest {
            // Arrange
            val clock = SchedulerClock(testScheduler)
            val ring = KeyRing(crypto, clock)
            val issued = mutableListOf<SecuredKeys>()
            val issue = { securedKeys(clock, seed = 1).also { issued += it }.let(ring::certify) }
            issue()
            val graceEnd =
                (
                    issued
                        .first()
                        .certificate.notAfter
                        .toLong() + GRACE_SECONDS
                ) * MILLIS
            backgroundScope.launch { ring.rotate(issue) }

            // Act
            advanceTimeBy(graceEnd - currentWallMillis() - 1)
            val before =
                issued
                    .first()
                    .static.secretKey
                    .copyOf()
            advanceTimeBy(1)
            runCurrent()
            val after =
                issued
                    .first()
                    .static.secretKey
                    .copyOf()

            // Assert
            assertFalse(before.all { it == ZERO })
            assertTrue(after.all { it == ZERO })
            assertFalse(issued[1].static.secretKey.all { it == ZERO })
        }

    // Senders seal to Bob's earlier certificate in its last second, with an expiry past the 1 h maximum so that
    // only the deleted keys can stop the later copy; `not_after` falls mid-epoch so both copies' tags are recognized.
    @Test
    fun aFirstMessageSealedToAnEarlierCertificateOpensUntilItsKeysAreDeletedAndNeverAfter() =
        runTest {
            // Arrange
            val clock = SchedulerClock(testScheduler)
            advanceTimeBy((MID_EPOCH - nowSeconds() - HOUR).mod(EPOCH) * MILLIS)
            val earlier = securedKeys(clock, seed = 2, validity = 1.hours)
            val bob = securedNode(earlier, NEIGHBOR)
            advanceTimeBy(30.minutes)
            bob.end.keys.certify(securedKeys(clock, seed = 2))
            advanceTimeBy((earlier.certificate.notAfter.toLong() - nowSeconds() - 1) * MILLIS)
            val frames =
                listOf(1, 3).map { seed ->
                    val sender = securedNode(securedKeys(clock, seed), NEIGHBOR)
                    sender.lookups.linked(bob.nodeId, earlier.certificate)
                    val draft = assertNotNull(sender.end.draft(entry(bob.nodeId, lifetime = 2 * HOUR)))
                    sender.end.frame(draft, hopLimit = 5)
                }
            val graceEnd = earlier.certificate.notAfter.toLong() + GRACE_SECONDS

            // Act
            advanceTimeBy((graceEnd - nowSeconds() - 1) * MILLIS)
            val lastSecond = bob.end.deliver(frames[0], nowSeconds())
            advanceTimeBy(MILLIS)
            val atGraceEnd = bob.end.deliver(frames[1], nowSeconds())

            // Assert
            assertTrue(lastSecond)
            assertFalse(atGraceEnd)
            assertEquals(1, bob.heard.size)
            assertEquals(
                "hello",
                bob.inbox.messages
                    .first()
                    .payload
                    .decodeToString(),
            )
        }

    // Carol's first message goes before half of Bob's first certificate's validity, Alice's after; both seal to it.
    @Test
    fun anIkReplyCarriesTheCurrentCertificateOnlyOnceTheSendersCopyIsPastHalfItsValidity() =
        runTest {
            // Arrange
            val clock = SchedulerClock(testScheduler)
            val earlier = securedKeys(clock, seed = 2)
            val bob = securedNode(earlier, NEIGHBOR)
            val (alice, carol) = listOf(1, 3).map { securedNode(securedKeys(clock, it), NEIGHBOR) }
            advanceTimeBy(HALF - 1.hours)
            val current = securedKeys(clock, seed = 2)
            bob.end.keys.certify(current)

            // Act
            val (_, early) = firstTaken(carol, bob)
            carol.end.acknowledger.acknowledged(early.frame(5), nowSeconds())
            advanceTimeBy(1.hours)
            session(alice, bob)

            // Assert
            assertEquals(earlier.certificate.notBefore, carol.lookups.certificate(bob.nodeId)?.notBefore)
            assertEquals(current.certificate.notBefore, alice.lookups.certificate(bob.nodeId)?.notBefore)
        }

    @Test
    fun aSessionReceiptCarriesTheResponderCurrentCertificateOnceTheInitiatorsCopyIsPastHalfItsValidity() =
        runTest {
            // Arrange
            val clock = SchedulerClock(testScheduler)
            val bob = securedNode(securedKeys(clock, seed = 2), NEIGHBOR)
            val alice = securedNode(securedKeys(clock, seed = 1), NEIGHBOR)
            advanceTimeBy(HALF - 1.hours)
            session(alice, bob)
            advanceTimeBy(1.hours)
            val current = securedKeys(clock, seed = 2)
            bob.end.keys.certify(current)
            val draft = assertNotNull(alice.end.draft(entry(bob.nodeId)))

            // Act
            bob.end.deliver(alice.end.frame(draft, hopLimit = 5), nowSeconds())
            bob.inbox.messages.first()
            advanceTimeBy(BATCH_MILLIS)
            alice.end.acknowledger.acknowledged(bob.replies.last().frame(5), nowSeconds())

            // Assert
            assertEquals(current.certificate.notBefore, alice.lookups.certificate(bob.nodeId)?.notBefore)
        }

    @Test
    fun aSessionReceiptCarriesTheInitiatorsCurrentCertificateOnceTheRespondersCopyIsPastHalfItsValidity() =
        runTest {
            // Arrange
            val clock = SchedulerClock(testScheduler)
            val bob = securedNode(securedKeys(clock, seed = 2), NEIGHBOR)
            val alice = securedNode(securedKeys(clock, seed = 1), NEIGHBOR)
            advanceTimeBy(HALF - 1.hours)
            session(alice, bob)
            advanceTimeBy(1.hours)
            val current = securedKeys(clock, seed = 1)
            alice.end.keys.certify(current)
            val draft = assertNotNull(bob.end.draft(entry(alice.nodeId)))

            // Act
            alice.end.deliver(bob.end.frame(draft, hopLimit = 5), nowSeconds())
            alice.inbox.messages.first()
            advanceTimeBy(BATCH_MILLIS)
            bob.end.acknowledger.acknowledged(alice.replies.last().frame(5), nowSeconds())

            // Assert
            assertEquals(current.certificate.notBefore, bob.lookups.certificate(alice.nodeId)?.notBefore)
        }

    private fun TestScope.currentWallMillis() = SchedulerClock(testScheduler).wallClockMillis()

    private fun TestScope.nowSeconds() = SchedulerClock(testScheduler).wallClockMillis() / MILLIS

    private companion object {
        const val GRACE_SECONDS = 3_630L
        const val MILLIS = 1_000L
        const val ZERO: Byte = 0
        const val RENEWAL = 43_200L
        const val VALIDITY = 86_400L
        val DIGEST = ByteArray(16)
        val NODE_ID = ByteArray(32)
        val NEIGHBOR = nodeId(9)
        const val HOUR = 3_600L
        const val BATCH_MILLIS = 501L
        val HALF = 12.hours
        const val EPOCH = 300L
        const val MID_EPOCH = 150L
    }
}
