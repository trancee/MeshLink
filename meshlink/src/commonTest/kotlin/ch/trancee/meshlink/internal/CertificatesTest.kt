package ch.trancee.meshlink.internal

import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

// The certificate cache (ADR 0018 decision 7, spec "Certificate lookup"), on virtual time.
@OptIn(ExperimentalCoroutinesApi::class)
class CertificatesTest {
    @Test
    fun theLeastRecentlyUsedCertificateIsEvictedPastSixtyFourEntries() =
        runTest {
            // Arrange: 64 cached, then the first is used, so the second is the least recently used.
            val cache = Certificates()
            val held = (1..Certificates.CAPACITY + 1).map { issue(it) }
            held.take(Certificates.CAPACITY).forEach { cache.put(it.first, it.second, nowSeconds()) }
            cache.get(held[0].first, nowSeconds())

            // Act
            cache.put(held.last().first, held.last().second, nowSeconds())

            // Assert
            assertNull(cache.get(held[1].first, nowSeconds()))
            assertEquals(
                held.filterIndexed { index, _ -> index != 1 }.map { it.second },
                held.map { cache.get(it.first, nowSeconds()) }.filterIndexed { index, _ -> index != 1 },
            )
        }

    @Test
    fun aCertificateIsDroppedAtItsNotAfter() =
        runTest {
            // Arrange
            val cache = Certificates()
            val (nodeId, certificate) = issue(1, validity = 1.hours)
            cache.put(nodeId, certificate, nowSeconds())
            advanceTimeBy(1.hours.inWholeMilliseconds - 1_000)
            val before = cache.get(nodeId, nowSeconds())
            val countBefore = cache.count(nowSeconds())

            // Act
            advanceTimeBy(1_000)

            // Assert
            assertSame(certificate, before)
            assertEquals(listOf(1, 0), listOf(countBefore, cache.count(nowSeconds())))
            assertNull(cache.get(nodeId, nowSeconds()))
        }

    @Test
    fun anExpiredCertificateIsNeitherCachedNorTakesASlot() =
        runTest {
            // Arrange: a full cache whose first entry expires.
            val cache = Certificates()
            val short = issue(1, validity = 1.hours)
            val rest = (2..Certificates.CAPACITY).map { issue(it) }
            (listOf(short) + rest).forEach { cache.put(it.first, it.second, nowSeconds()) }
            advanceTimeBy(1.hours.inWholeMilliseconds)
            val late = issue(Certificates.CAPACITY + 1)
            val stale = issue(Certificates.CAPACITY + 2, validity = 1.hours)
            advanceTimeBy(1.hours.inWholeMilliseconds)

            // Act
            cache.put(late.first, late.second, nowSeconds())
            cache.put(stale.first, stale.second, nowSeconds())

            // Assert: the expired one made room, and every live one stayed.
            assertNull(cache.get(stale.first, nowSeconds()))
            assertEquals((rest + late).map { it.second }, (rest + late).map { cache.get(it.first, nowSeconds()) })
        }

    @Test
    fun theCertificateWithTheLatestNotAfterIsKeptPerNodeId() =
        runTest {
            // Arrange
            val cache = Certificates()
            val (nodeId, long) = issue(1, validity = 2.hours)
            val short = issue(1, validity = 1.hours).second
            cache.put(nodeId, long, nowSeconds())

            // Act
            cache.put(nodeId, short, nowSeconds())
            val kept = cache.get(nodeId, nowSeconds())
            advanceTimeBy(1_000)
            val later = issue(1, validity = 2.hours).second
            cache.put(nodeId, later, nowSeconds())

            // Assert
            assertSame(long, kept)
            assertSame(later, cache.get(nodeId, nowSeconds()))
        }

    @Test
    fun findReturnsTheCertificateWhoseNodeIdMatches() =
        runTest {
            // Arrange
            val cache = Certificates()
            val held = (1..3).map { issue(it) }
            held.forEach { cache.put(it.first, it.second, nowSeconds()) }

            // Act
            val found = cache.find(nowSeconds()) { it.contentEquals(held[1].first) }
            val missing = cache.find(nowSeconds()) { false }

            // Assert
            assertSame(held[1].second, found)
            assertNull(missing)
        }

    private fun TestScope.nowSeconds() = SchedulerClock(testScheduler).wallClockMillis() / 1_000

    // The NodeId and a classical certificate of the identity from seed [n], valid from now for [validity].
    private fun TestScope.issue(
        n: Int,
        validity: Duration = 24.hours,
    ): Pair<ByteArray, IdentityCertificate> {
        val crypto = SodiumCryptoProvider
        val identity = identityKey(n)
        val keys = StaticKeys(crypto.x25519KeyPair().publicKey, null)
        val certificate = IdentityCertificate.issue(crypto, SchedulerClock(testScheduler), identity, keys, validity)
        return Derivations(crypto).nodeId(identity.publicKey) to certificate
    }
}
