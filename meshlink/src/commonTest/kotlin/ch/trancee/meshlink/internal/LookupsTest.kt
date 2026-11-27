package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DensityClass
import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

// Certificate lookups (spec "Certificate lookup", ADR 0018), between a requester and a responder
// that each have lanes to three linked peers.
@OptIn(ExperimentalCoroutinesApi::class)
class LookupsTest {
    private val crypto = SodiumCryptoProvider
    private val a = nodeId(1)
    private val b = nodeId(2)
    private val c = nodeId(3)
    private val derivations = Derivations(crypto)

    // The node with identity seed [seed] in [profile], begun and holding its own certificate; [arrived]
    // records the NodeIds of the certificates it came to hold.
    private class Node(
        val lookups: Lookups,
        val lanes: Lanes,
        val nodeId: ByteArray,
        val certificate: IdentityCertificate,
        val arrived: MutableList<NodeId>,
        val hub: DiagnosticsHub,
        val diagnosed: List<DiagnosticEvent>,
    )

    private fun TestScope.node(
        seed: Int,
        profile: WireProfile = WireProfile.CLASSICAL,
        density: DensityClass = DensityClass.Medium,
    ): Node {
        val lanes = Lanes().apply { listOf(a, b, c).forEach { open(it) } }
        val hub = DiagnosticsHub(backgroundScope, debug = true, crypto = crypto)
        val diagnosed = mutableListOf<DiagnosticEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.events.collect { diagnosed += it } }
        val lookups = Lookups(crypto, Pace(clock()) { density }, backgroundScope, profile, lanes, hub)
        val nodeId = derivations.nodeId(identityKey(seed).publicKey)
        val certificate = certificate(seed, profile)
        val arrived = mutableListOf<NodeId>()
        lookups.begin { arrived += it }
        lookups.certify(certificate)
        return Node(lookups, lanes, nodeId, certificate, arrived, hub, diagnosed)
    }

    private fun TestScope.clock() = SchedulerClock(testScheduler)

    private fun TestScope.nowSeconds() = clock().wallClockMillis() / 1_000

    private fun TestScope.certificate(
        seed: Int,
        profile: WireProfile = WireProfile.CLASSICAL,
        identity: Ed25519KeyPair = identityKey(seed),
        validity: kotlin.time.Duration = 24.hours,
    ): IdentityCertificate {
        val xWing = if (profile == WireProfile.POST_QUANTUM) crypto.xWingKeyPair().publicKey else null
        val keys = StaticKeys(crypto.x25519KeyPair().publicKey, xWing)
        return IdentityCertificate.issue(crypto, clock(), identity, keys, validity)
    }

    private fun TestScope.deadline() = clock().wallClockMillis() + 600_000

    private suspend fun Lanes.frames(peer: NodeId) = drain(peer).map { it.render() }

    private suspend fun Lanes.requests(peer: NodeId) =
        frames(peer).map { assertIs<Decoded.Valid<LookupRequest>>(LookupRequest.decode(it)).value }

    private suspend fun Lanes.replies(peer: NodeId) =
        frames(peer).map { assertIs<Decoded.Valid<LookupReply>>(LookupReply.decode(it)).value }

    // [requester]'s lookup of [target], answered by [responder] on its link to [a]; the reply.
    private suspend fun TestScope.answered(
        requester: Node,
        responder: Node,
        target: ByteArray,
    ): LookupReply {
        requester.lookups.find(NodeId(target), deadline())
        runCurrent()
        val request = requester.lanes.requests(a).single()
        listOf(b, c).forEach { requester.lanes.drain(it) }
        responder.lookups.answers.requested(a, request, nowSeconds())
        return responder.lanes.replies(a).single()
    }

    @Test
    fun aLookupFloodsATaggedRequestOnEveryLinkThenRetriesWithAFreshSaltKeyAndIdOnTheUnicastSchedule() =
        runTest {
            // Arrange
            val requester = node(1)
            val target = derivations.nodeId(identityKey(9).publicKey)

            // Act
            requester.lookups.find(NodeId(target), deadline())
            runCurrent()
            val first = listOf(a, b, c).map { requester.lanes.requests(it).single() }
            advanceTimeBy(Outbox.retryMillis(DensityClass.Medium, 0) - 1)
            val early = requester.lanes.frames(a)
            advanceTimeBy(1)
            runCurrent()
            val second = requester.lanes.requests(a).single()

            // Assert
            val request = first[0]
            assertEquals(1, first.map { it.encode().toHexString() }.toSet().size)
            assertEquals(RouteDiscovery.RADIUS, request.header.hopLimit)
            assertEquals(nowSeconds() - 2 + RouteDiscovery.LIFETIME_MIN_SECONDS, request.header.expiry.toLong())
            assertContentEquals(derivations.lookupTag(request.salt, target), request.lookupTag)
            assertEquals(emptyList(), early)
            assertFalse(second.salt.contentEquals(request.salt))
            assertFalse(second.ephemeralKey.contentEquals(request.ephemeralKey))
            assertFalse(second.header.messageId.contentEquals(request.header.messageId))
            assertContentEquals(derivations.lookupTag(second.salt, target), second.lookupTag)
        }

    @Test
    fun aNodeHasAtMostOneLookupOutstandingPerNodeId() =
        runTest {
            // Arrange
            val requester = node(1)
            val target = NodeId(derivations.nodeId(identityKey(9).publicKey))
            requester.lookups.find(target, deadline())
            runCurrent()
            requester.lanes.drain(a)

            // Act
            requester.lookups.find(target, deadline())
            requester.lookups.find(NodeId(derivations.nodeId(identityKey(8).publicKey)), deadline())
            runCurrent()

            // Assert: only the other NodeId's lookup was sent.
            assertEquals(1, requester.lanes.requests(a).size)
        }

    @Test
    fun aLookupEndsAtItsDeadline() =
        runTest {
            // Arrange
            val requester = node(1)
            val target = NodeId(derivations.nodeId(identityKey(9).publicKey))
            requester.lookups.find(target, clock().wallClockMillis() + 1_000)
            runCurrent()
            requester.lanes.drain(a)

            // Act
            advanceTimeBy(Outbox.retryMillis(DensityClass.Medium, 0))
            val after = requester.lanes.frames(a)
            requester.lookups.find(target, deadline())
            runCurrent()

            // Assert: no retry, and a new lookup may start.
            assertEquals(emptyList(), after)
            assertEquals(1, requester.lanes.requests(a).size)
            val peer = requester.hub.peer(target)
            assertEquals(
                listOf("ML6003 CertificateLookupUnanswered(peer=$peer, attempts=1) x1"),
                requester.diagnosed.map { it.toString() },
            )
        }

    @Test
    fun aNodeWithoutARunOrWithACachedCertificateSendsNoLookup() =
        runTest {
            // Arrange
            val lanes = Lanes().apply { open(a) }
            val hub = DiagnosticsHub(backgroundScope, debug = true, crypto = crypto)
            val pace = Pace(clock()) { DensityClass.Low }
            val idle = Lookups(crypto, pace, backgroundScope, WireProfile.CLASSICAL, lanes, hub)
            val requester = node(1)
            requester.lookups.linked(NodeId(derivations.nodeId(identityKey(9).publicKey)), certificate(9))

            // Act
            idle.find(a, deadline())
            requester.lookups.find(NodeId(derivations.nodeId(identityKey(9).publicKey)), deadline())
            runCurrent()

            // Assert
            assertEquals(emptyList(), lanes.frames(a))
            assertEquals(emptyList(), requester.lanes.frames(a))
        }

    @Test
    fun theTargetAnswersOnTheAskingLinkWithItsCertificateSealedToTheRequesterAndStopsTheRequest() =
        runTest {
            // Arrange
            val requester = node(1)
            val responder = node(2)

            // Act
            val reply = answered(requester, responder, responder.nodeId)

            // Assert
            assertEquals(LookupReply.CLASSICAL_LENGTH, reply.encode().size)
            assertEquals(Router.HOP_LIMIT_MAX, reply.header.hopLimit)
            assertEquals(nowSeconds() + RouteDiscovery.LIFETIME_MIN_SECONDS, reply.header.expiry.toLong())
            assertEquals(listOf(b, c).map { emptyList<ByteArray>() }, listOf(b, c).map { responder.lanes.frames(it) })
            assertTrue(requester.lookups.replied(reply))
            assertContentEquals(
                responder.certificate.encode(),
                requester.lookups.certificate(NodeId(responder.nodeId))?.encode(),
            )
        }

    @Test
    fun aPostQuantumReplyCarriesThePostQuantumCertificate() =
        runTest {
            // Arrange
            val requester = node(1, WireProfile.POST_QUANTUM)
            val responder = node(2, WireProfile.POST_QUANTUM)

            // Act
            val reply = answered(requester, responder, responder.nodeId)

            // Assert
            assertEquals(LookupReply.POST_QUANTUM_LENGTH, reply.encode().size)
            assertTrue(requester.lookups.replied(reply))
            assertNotNull(requester.lookups.certificate(NodeId(responder.nodeId)))
        }

    @Test
    fun aNodeAnswersFromItsCacheWithTheCertificateOfTheLatestNotAfter() =
        runTest {
            // Arrange
            val requester = node(1)
            val responder = node(2)
            val target = derivations.nodeId(identityKey(9).publicKey)
            val longer = certificate(9)
            responder.lookups.linked(NodeId(target), longer)
            responder.lookups.linked(NodeId(target), certificate(9, validity = 1.hours))

            // Act
            val reply = answered(requester, responder, target)

            // Assert
            assertTrue(requester.lookups.replied(reply))
            assertContentEquals(longer.encode(), requester.lookups.certificate(NodeId(target))?.encode())
        }

    @Test
    fun aRequestNoNodeMatchesIsFloodedOnWithOneHopLessAndRemembered() =
        runTest {
            // Arrange
            val requester = node(1)
            val relay = node(2)
            requester.lookups.find(NodeId(derivations.nodeId(identityKey(9).publicKey)), deadline())
            runCurrent()
            val request = requester.lanes.requests(a).single()

            // Act
            val first = relay.lookups.answers.requested(a, request, nowSeconds())
            val again = relay.lookups.answers.requested(b, request, nowSeconds())

            // Assert
            assertEquals(emptyList(), relay.lanes.frames(a))
            val sent = listOf(b, c).map { relay.lanes.requests(it).single() }
            sent.forEach {
                assertEquals(RouteDiscovery.RADIUS - 1, it.header.hopLimit)
                assertContentEquals(request.copy(RouteDiscovery.RADIUS - 1).encode(), it.encode())
            }
            assertEquals(listOf(true, false), listOf(first, again))
        }

    @Test
    fun aRequestOverItsLinksBudgetIsRememberedButNotFloodedOnAndAnAnsweredOneDoesNotAsk() =
        runTest {
            // Arrange
            val requester = node(1)
            val relay = node(2)
            requester.lookups.find(NodeId(derivations.nodeId(identityKey(9).publicKey)), deadline())
            requester.lookups.find(NodeId(relay.nodeId), deadline())
            runCurrent()
            val (unknown, known) = requester.lanes.requests(a)
            var asked = 0

            // Act
            val first = relay.lookups.answers.requested(a, unknown, nowSeconds()) { false.also { asked++ } }
            val again = relay.lookups.answers.requested(b, unknown, nowSeconds())
            relay.lookups.answers.requested(a, known, nowSeconds()) { false.also { asked++ } }

            // Assert
            assertEquals(listOf(true, false, 1), listOf(first, again, asked))
            assertEquals(listOf(b, c).map { emptyList<ByteArray>() }, listOf(b, c).map { relay.lanes.frames(it) })
            assertEquals(1, relay.lanes.frames(a).size, "the known node's reply")
        }

    @Test
    fun aRequestOnItsLastHopOrUntimelyIsNotFloodedOn() =
        runTest {
            // Arrange
            val requester = node(1)
            val relay = node(2)
            requester.lookups.find(NodeId(derivations.nodeId(identityKey(9).publicKey)), deadline())
            runCurrent()
            val request = requester.lanes.requests(a).single()
            val expired = request.header.expiry.toLong() + UnsecuredMessaging.CLOCK_SKEW_SECONDS + 1

            // Act
            val last = relay.lookups.answers.requested(a, request.copy(1), nowSeconds())
            val untimely = relay.lookups.answers.requested(a, request.copy(id = messageId(5)), expired)

            // Assert
            assertEquals(listOf(true, false), listOf(last, untimely))
            assertEquals(listOf(b, c).map { emptyList<ByteArray>() }, listOf(b, c).map { relay.lanes.frames(it) })
        }

    @Test
    fun aRequestWithALowOrderKeyGetsNoReplyAndGoesNoFurther() =
        runTest {
            // Arrange
            val responder = node(2)
            val salt = tag(4)
            val request =
                LookupRequest(
                    FloodHeader(RouteDiscovery.RADIUS, messageId(1), (nowSeconds() + 60).toUInt()),
                    salt,
                    derivations.lookupTag(salt, responder.nodeId),
                    ByteArray(32),
                )

            // Act
            responder.lookups.answers.requested(a, request, nowSeconds())

            // Assert
            val peers = listOf(a, b, c)
            assertEquals(peers.map { emptyList<ByteArray>() }, peers.map { responder.lanes.frames(it) })
        }

    @Test
    fun theRequesterTakesTheFirstValidReplyStopsRetryingAndDropsLaterReplies() =
        runTest {
            // Arrange
            val requester = node(1)
            val responder = node(2)
            val reply = answered(requester, responder, responder.nodeId)

            // Act
            val first = requester.lookups.replied(reply)
            val later = requester.lookups.replied(reply)
            advanceTimeBy(Outbox.retryMillis(DensityClass.Medium, 0))

            // Assert
            assertTrue(first)
            assertFalse(later)
            assertEquals(emptyList(), requester.lanes.frames(a))
            assertEquals(listOf(NodeId(responder.nodeId)), requester.arrived)
        }

    @Test
    fun aLookupAnsweredIsReportedWithTheRequestsItTook() =
        runTest {
            // Arrange
            val requester = node(1)
            val responder = node(2)
            val target = NodeId(responder.nodeId)
            requester.lookups.find(target, deadline())
            runCurrent()
            advanceTimeBy(Outbox.retryMillis(DensityClass.Medium, 0))
            runCurrent()
            val request = requester.lanes.requests(a).last()
            responder.lookups.answers.requested(a, request, nowSeconds())

            // Act
            requester.lookups.replied(responder.lanes.replies(a).single())

            // Assert
            val peer = requester.hub.peer(target)
            val expected = listOf("ML6002 CertificateLookup(peer=$peer, attempts=2) x1")
            assertEquals(expected, requester.diagnosed.map { it.toString() })
        }

    @Test
    fun aCertificateALinkOrFirstMessageProvedArrives() =
        runTest {
            // Arrange
            val node = node(1)
            val peer = NodeId(derivations.nodeId(identityKey(9).publicKey))

            // Act
            node.lookups.linked(peer, certificate(9))

            // Assert
            assertEquals(listOf(peer), node.arrived)
            assertNotNull(node.lookups.certificate(peer))
        }

    @Test
    fun aRefreshIsHeldOnlyIfItIsAValidCertificateOfThePeer() =
        runTest {
            // Arrange: each flawed refresh is what a session peer might attach to a receipt.
            val node = node(1)
            val peer = NodeId(derivations.nodeId(identityKey(9).publicKey))
            val brief = certificate(9, validity = 1.seconds).encode()
            advanceTimeBy(EXPIRED_MILLIS)
            val issued = certificate(9)
            val valid = issued.encode()
            val signature = valid.size - SIGNATURE
            val flawed =
                listOf(
                    certificate(8).encode(),
                    brief,
                    valid.copyOf().also { it[signature] = (it[signature] + 1).toByte() },
                    overlong(issued, identityKey(9)),
                    certificate(9, WireProfile.POST_QUANTUM).encode(),
                )

            // Act
            node.lookups.refreshed(peer, null)
            flawed.forEach { node.lookups.refreshed(peer, it) }
            val held = node.lookups.certificate(peer)
            node.lookups.refreshed(peer, valid)

            // Assert: none, another NodeId, expired, bad signature, over 86,400 s, other profile; then a valid one.
            assertNull(held)
            assertContentEquals(valid, assertNotNull(node.lookups.certificate(peer)).encode())
            assertEquals(listOf(peer), node.arrived)
        }

    @Test
    fun aReplyToAnEarlierAttemptOfAnOutstandingLookupIsTaken() =
        runTest {
            // Arrange
            val requester = node(1)
            val responder = node(2)
            val reply = answered(requester, responder, responder.nodeId)
            advanceTimeBy(Outbox.retryMillis(DensityClass.Medium, 0))

            // Act
            val taken = requester.lookups.replied(reply)

            // Assert
            assertTrue(taken)
            assertNotNull(requester.lookups.certificate(NodeId(responder.nodeId)))
        }

    @Test
    fun anUnrequestedReplyIsNotTakenNorCached() =
        runTest {
            // Arrange
            val requester = node(1)
            val responder = node(2)
            val reply = answered(node(3), responder, responder.nodeId)

            // Act
            val taken = requester.lookups.replied(reply)

            // Assert
            assertFalse(taken)
            assertNull(requester.lookups.certificate(NodeId(responder.nodeId)))
        }

    @Test
    fun repliesThatFailToOpenOrValidateAreRejectedNeverCachedAndTheLookupGoesOn() =
        runTest {
            // Arrange: each forged reply seals a bad certificate the way a responder would.
            val requester = node(1)
            val target = derivations.nodeId(identityKey(9).publicKey)
            requester.lookups.find(NodeId(target), clock().wallClockMillis() + 3_600_000)
            runCurrent()
            val request = requester.lanes.requests(a).single()
            val brief = certificate(9, validity = 1.seconds).encode()
            advanceTimeBy(EXPIRED_MILLIS)
            val issued = certificate(9)
            val valid = issued.encode()
            val signature = valid.size - 1
            val forged =
                listOf(
                    sealed(request, certificate(8).encode()),
                    sealed(request, brief),
                    sealed(request, valid.copyOf().also { it[signature] = (it[signature] + 1).toByte() }),
                    sealed(request, overlong(issued, identityKey(9))),
                    sealed(request, certificate(9, WireProfile.POST_QUANTUM).encode()),
                    sealed(request, valid).let { it.copy(sealed = it.sealedCertificate.flipped()) },
                    sealed(request, valid).copy(key = ByteArray(32)),
                )
            requester.lanes.drain(a)

            // Act
            val taken = forged.map { requester.lookups.replied(it) }
            val cached = requester.lookups.certificate(NodeId(target))
            advanceTimeBy(EXPIRED_MILLIS)

            // Assert: another NodeId, expired, bad signature, over 86,400 s, other profile, failed AEAD, low-order key.
            assertEquals(forged.map { true }, taken)
            assertNull(cached)
            assertTrue(requester.lanes.requests(a).isNotEmpty())
            assertEquals(emptyList(), requester.arrived)
            assertEquals(
                listOf("ML6004 InvalidCertificateReply() x1", "ML6004 InvalidCertificateReply() x6"),
                requester.diagnosed.map { it.toString() },
            )
        }

    @Test
    fun aRelayPassesOnlyTheFirstReplyPerSalt() =
        runTest {
            // Arrange
            val relay = node(2)
            val expiry = nowSeconds() + 60

            // Act
            val first = relay.lookups.answers.passes(tag(4), expiry)
            val second = relay.lookups.answers.passes(tag(4), expiry)
            val other = relay.lookups.answers.passes(tag(5), expiry)

            // Assert
            assertEquals(listOf(true, false, true), listOf(first, second, other))
        }

    @Test
    fun anEndedRunStopsItsLookups() =
        runTest {
            // Arrange
            val requester = node(1)
            requester.lookups.find(NodeId(derivations.nodeId(identityKey(9).publicKey)), deadline())
            runCurrent()
            requester.lanes.drain(a)

            // Act
            requester.lookups.end()
            advanceTimeBy(Outbox.retryMillis(DensityClass.Medium, 0))

            // Assert
            assertEquals(emptyList(), requester.lanes.frames(a))
            assertEquals(emptyList(), requester.diagnosed)
        }

    @Test
    fun aNodeWithoutItsOwnCertificateFloodsARequestForItOn() =
        runTest {
            // Arrange
            val responder = node(2)
            responder.lookups.certify(null)
            val salt = tag(4)
            val request =
                LookupRequest(
                    FloodHeader(RouteDiscovery.RADIUS, messageId(1), (nowSeconds() + 60).toUInt()),
                    salt,
                    derivations.lookupTag(salt, responder.nodeId),
                    crypto.x25519KeyPair().publicKey,
                )

            // Act
            responder.lookups.answers.requested(a, request, nowSeconds())

            // Assert
            assertEquals(1, responder.lanes.requests(b).size)
        }

    @Test
    fun anEndedRunAnswersNoMoreForItsOwnNodeId() =
        runTest {
            // Arrange
            val responder = node(2)
            val salt = tag(4)
            val request =
                LookupRequest(
                    FloodHeader(RouteDiscovery.RADIUS, messageId(1), (nowSeconds() + 60).toUInt()),
                    salt,
                    derivations.lookupTag(salt, responder.nodeId),
                    crypto.x25519KeyPair().publicKey,
                )

            // Act
            responder.lookups.end()
            responder.lookups.answers.requested(a, request, nowSeconds())

            // Assert
            assertEquals(emptyList(), responder.lanes.frames(a))
            assertEquals(1, responder.lanes.requests(b).size)
        }

    // A reply to [request] sealing [certificate].
    private fun TestScope.sealed(
        request: LookupRequest,
        certificate: ByteArray,
    ): LookupReply {
        val ephemeral = crypto.x25519KeyPair()
        val seal =
            LookupSeal(crypto).seal(ephemeral, request.ephemeralKey, request.salt, request.lookupTag, certificate)
        val header =
            UnicastHeader(Router.HOP_LIMIT_MAX, messageId(7), (nowSeconds() + 60).toUInt(), request.salt, tag(6))
        return LookupReply(header, ephemeral.publicKey, assertNotNull(seal))
    }

    private fun LookupRequest.copy(
        hopLimit: Int = header.hopLimit,
        id: ByteArray = header.messageId,
    ) = LookupRequest(FloodHeader(hopLimit, id, header.expiry), salt, lookupTag, ephemeralKey)

    private fun LookupReply.copy(
        key: ByteArray = ephemeralKey,
        sealed: ByteArray = sealedCertificate,
    ) = LookupReply(header, key, sealed)

    private fun ByteArray.flipped() = copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

    private companion object {
        // Past a 1-second certificate's not_after and the 30-second clock skew.
        const val EXPIRED_MILLIS = 31_000L
        const val SIGNATURE = 64
    }
}
