package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Peer
import ch.trancee.meshlink.Reachability
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RoutedPeersTest {
    private val routes = Routes()
    private val roster = Roster()
    private val derivations = Derivations(SodiumCryptoProvider)

    private fun TestScope.routed(): RoutedPeers {
        val clock = SchedulerClock(testScheduler)
        return RoutedPeers(clock, this, routes, roster, UnsecuredPeerTags(SodiumCryptoProvider))
    }

    // A secured node's routed peers, and the lookups whose certificates they are listed by.
    private fun TestScope.securedRouted(): Pair<RoutedPeers, Lookups> {
        val clock = SchedulerClock(testScheduler)
        val lookups =
            Lookups(SodiumCryptoProvider, clock, backgroundScope, WireProfile.CLASSICAL, Lanes()) {
                DensityClass.MEDIUM
            }
        lookups.begin {}
        return RoutedPeers(clock, this, routes, roster, SecuredPeerTags(SodiumCryptoProvider, lookups)) to lookups
    }

    private fun TestScope.certificate(
        seed: Int,
        validity: Duration = 24.hours,
    ): IdentityCertificate {
        val keys = StaticKeys(SodiumCryptoProvider.x25519KeyPair().publicKey, null)
        val clock = SchedulerClock(testScheduler)
        return IdentityCertificate.issue(SodiumCryptoProvider, clock, identityKey(seed), keys, validity)
    }

    private fun securedId(seed: Int) = NodeId(derivations.nodeId(identityKey(seed).publicKey))

    // A route found to [certificate]'s public tag for this epoch.
    private fun TestScope.foundPublic(
        certificate: IdentityCertificate,
        lifeMillis: Long = Routes.FORWARD_MILLIS,
    ) {
        val now = testScheduler.currentTime
        val tag = EndToEndTags(derivations).publicTag(certificate.x25519Key, epoch())
        routes.offer(tag, Routes.Route(nodeId(9), now + lifeMillis, 2u, 3, 0), now)
    }

    private fun TestScope.epoch() = Derivations.epoch(SchedulerClock(testScheduler).wallClockMillis() / 1_000)

    private fun TestScope.found(
        id: NodeId,
        hops: Int = 3,
        sequence: UInt = 2u,
        epoch: UInt = epoch(),
        heardMillis: Long = 0,
        lifeMillis: Long = Routes.FORWARD_MILLIS,
    ) {
        val now = testScheduler.currentTime
        val route = Routes.Route(nodeId(9), now + lifeMillis, sequence, hops, heardMillis)
        routes.offer(derivations.unsecuredTag(id.toByteArray(), epoch), route, now)
    }

    private fun listed() = roster.peers.value.map { it.nodeId to it.reachability }

    @Test
    fun aNodeHeardFromIsListedAsRoutedWhileARouteDiscoveryFoundLeadsToIt() =
        runTest {
            val peers = routed()
            found(nodeId(1), hops = 4)

            peers.heard(nodeId(1))

            val peer = roster.peers.value.single()
            assertEquals(nodeId(1), peer.nodeId)
            assertEquals(Reachability.Routed(4), peer.reachability)
            assertEquals(false, peer.secured)
            assertNull(peer.proximity)
            peers.end()
        }

    @Test
    fun aNodeSentToIsListedAndAnUnknownOneIsNot() =
        runTest {
            val peers = routed()
            found(nodeId(1))
            found(nodeId(2))

            peers.sending(nodeId(1))

            assertEquals(listOf(nodeId(1) to Reachability.Routed(3)), listed())
            peers.end()
        }

    @Test
    fun aKnownNodeOnlyOnAReverseRouteIsNotListed() =
        runTest {
            val peers = routed()
            routes.learn(derivations.unsecuredTag(nodeId(1).toByteArray(), epoch()), nodeId(9), 0)

            peers.heard(nodeId(1))

            assertEquals(emptyList(), listed())
        }

    @Test
    fun aRouteToTheTagOfTheEpochBeforeOrAfterAlsoLists() =
        runTest {
            val peers = routed()
            found(nodeId(1), hops = 2, epoch = epoch() - 1u)
            found(nodeId(2), hops = 5, epoch = epoch() + 1u)
            found(nodeId(3), epoch = epoch() + 2u)

            listOf(1, 2, 3).forEach { peers.heard(nodeId(it)) }

            assertEquals(listOf(nodeId(1) to Reachability.Routed(2), nodeId(2) to Reachability.Routed(5)), listed())
            peers.end()
        }

    @Test
    fun aRoutedPeerLeavesWhenItsRouteExpires() =
        runTest {
            val peers = routed()
            found(nodeId(1), lifeMillis = 10_000)
            found(nodeId(2), lifeMillis = 20_000)
            peers.heard(nodeId(1))
            peers.heard(nodeId(2))

            advanceTimeBy(10_000)
            runCurrent()
            val first = listed()
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(listOf(nodeId(2) to Reachability.Routed(3)), first)
            assertEquals(emptyList(), listed())
        }

    @Test
    fun theSoonestRouteToExpireListsAnewWhicheverNodeWasHeardFirst() =
        runTest {
            // Arrange
            val peers = routed()
            found(nodeId(1), lifeMillis = 20_000)
            found(nodeId(2), lifeMillis = 10_000)
            peers.heard(nodeId(1))
            peers.heard(nodeId(2))

            // Act
            advanceTimeBy(10_000)
            runCurrent()

            // Assert
            assertEquals(listOf(nodeId(1) to Reachability.Routed(3)), listed())
        }

    @Test
    fun aPeerIsListedAnewOnceWhenTheFirstRouteExpiresHoweverOftenTheListChanged() =
        runTest {
            val peers = routed()
            found(nodeId(1), lifeMillis = 10_000)
            found(nodeId(2), lifeMillis = 20_000)
            peers.heard(nodeId(1))
            peers.heard(nodeId(2))
            val lists = mutableListOf<List<Peer>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { roster.peers.collect { lists += it } }

            advanceTimeBy(15_000)

            assertEquals(listOf(2, 1), lists.map { it.size })
            peers.end()
        }

    @Test
    fun theTagsAreDerivedAnewInTheNextEpoch() =
        runTest {
            val peers = routed()
            peers.heard(nodeId(1))
            advanceTimeBy(Derivations.EPOCH_SECONDS * 2_000L)
            found(nodeId(1))

            peers.update()

            assertEquals(listOf(nodeId(1) to Reachability.Routed(3)), listed())
            peers.end()
        }

    @Test
    fun lastSeenIsTheLaterOfTheLastMessageAndTheRouteHeard() =
        runTest {
            val peers = routed()
            val heardAt = SchedulerClock(testScheduler).wallClockMillis() + 1_000
            found(nodeId(1), heardMillis = heardAt + 5_000)
            found(nodeId(2), heardMillis = heardAt - 5_000)
            advanceTimeBy(1_000)

            peers.heard(nodeId(1))
            peers.heard(nodeId(2))
            peers.sending(nodeId(2))

            val seen = roster.peers.value.map(Peer::lastSeen)
            assertEquals(listOf(heardAt + 5_000, heardAt).map(Instant::fromEpochMilliseconds), seen)
            peers.end()
        }

    @Test
    fun aNodeOnlySentToIsLastSeenWhenItsRouteWasHeard() =
        runTest {
            val peers = routed()
            found(nodeId(1), heardMillis = 1_234)

            peers.sending(nodeId(1))

            assertEquals(
                Instant.fromEpochMilliseconds(1_234),
                roster.peers.value
                    .single()
                    .lastSeen,
            )
            peers.end()
        }

    @Test
    fun pastTheMostKnownNodesTheOneLongestUnusedIsForgotten() =
        runTest {
            val peers = routed()
            found(nodeId(1))
            found(nodeId(2))
            peers.sending(nodeId(1))
            peers.sending(nodeId(2))

            (0 until RoutedPeers.KNOWN_MAX - 2).forEach { n ->
                peers.sending(
                    NodeId(
                        ByteArray(32) { (n shr it * 8).toByte() }.also {
                            it[31] =
                                0x55
                        },
                    ),
                )
            }
            peers.sending(nodeId(1))
            peers.sending(nodeId(3))

            assertEquals(listOf(nodeId(1) to Reachability.Routed(3)), listed())
            peers.end()
        }

    @Test
    fun theEndListsNoRoutedPeerAndForgetsTheKnownNodes() =
        runTest {
            val peers = routed()
            found(nodeId(1))
            peers.heard(nodeId(1))

            peers.end()
            val ended = listed()
            peers.update()

            assertEquals(emptyList(), ended)
            assertEquals(emptyList(), listed())
        }

    @Test
    fun aSecuredNodeIsListedOnlyWhileACertificateIsHeldAndARouteLeadsToItsPublicTag() =
        runTest {
            val (peers, lookups) = securedRouted()
            val routedCertificate = certificate(5)
            val unroutedCertificate = certificate(6)
            foundPublic(routedCertificate)
            peers.heard(securedId(5))
            peers.heard(securedId(6))
            val uncertified = listed()

            lookups.linked(securedId(5), routedCertificate)
            lookups.linked(securedId(6), unroutedCertificate)
            peers.update()

            assertEquals(emptyList(), uncertified)
            val peer = roster.peers.value.single()
            assertEquals(securedId(5) to Reachability.Routed(3), peer.nodeId to peer.reachability)
            assertEquals(true, peer.secured)
            peers.end()
        }

    @Test
    fun aSecuredRoutedPeerLeavesWhenItsCertificateLapsesBeforeItsRoute() =
        runTest {
            val (peers, lookups) = securedRouted()
            val brief = certificate(5, validity = 60.seconds)
            foundPublic(brief, lifeMillis = 120_000)
            lookups.linked(securedId(5), brief)
            peers.heard(securedId(5))

            advanceTimeBy(59_999)
            runCurrent()
            val before = listed()
            advanceTimeBy(1)
            runCurrent()

            assertEquals(listOf(securedId(5) to Reachability.Routed(3)), before)
            assertEquals(emptyList(), listed())
            peers.end()
        }

    @Test
    fun aNewerCertificateOfASecuredPeerIsRoutedByItsOwnTags() =
        runTest {
            val (peers, lookups) = securedRouted()
            val older = certificate(5, validity = 1.hours)
            foundPublic(older)
            lookups.linked(securedId(5), older)
            peers.heard(securedId(5))
            val first = listed()

            lookups.linked(securedId(5), certificate(5))
            peers.update()

            assertEquals(listOf(securedId(5) to Reachability.Routed(3)), first)
            assertEquals(emptyList(), listed())
            peers.end()
        }

    @Test
    fun aDirectPeerIsListedOnceAsDirectAndThePeersAreOrderedByNodeId() {
        val roster = Roster()
        val at = Instant.fromEpochMilliseconds(0)

        roster.routed(listOf(3, 1).map { Peer(nodeId(it), Reachability.Routed(2), false, at, null) })
        roster.direct(listOf(3, 2).map { Peer(nodeId(it), Reachability.Direct, false, at, null) })

        val expected = listOf(1 to Reachability.Routed(2), 2 to Reachability.Direct, 3 to Reachability.Direct)
        assertEquals(expected.map { (n, r) -> nodeId(n) to r }, roster.peers.value.map { it.nodeId to it.reachability })
    }
}
