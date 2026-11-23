package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Certificate lookups of a secured node (spec "Certificate lookup", ADR 0018 decisions 3 to 8 and
 * amendment): sends this node's lookups and opens their replies; its [answers] take the requests of
 * other nodes and tell relays which reply per salt to pass on.
 *
 * Holds the [Certificates] of other nodes, filled from links and from this node's own lookups;
 * certificates are validated for [profile]. Requests go out on [lanes]. Retries follow the unicast
 * schedule of the node's [density] class. Runs on [scope]'s serial dispatcher.
 */
internal class Lookups(
    private val crypto: CryptoProvider,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val profile: WireProfile,
    private val lanes: Lanes,
    private val density: () -> DensityClass,
) {
    private val certificates = Certificates()
    private val derivations = Derivations(crypto)
    private val seal = LookupSeal(crypto)
    private val floods = MessageIds()
    private val attempts = mutableMapOf<String, Attempt>()
    private val outstanding = mutableMapOf<NodeId, Job>()
    private var run = CoroutineScope(Job().apply { cancel() })

    /** Answers the lookup requests of other nodes. */
    val answers = LookupAnswers(crypto, clock, lanes, certificates, floods)

    /** Starts a run. */
    fun begin() {
        run = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
    }

    /** Ends the run: its lookups stop, and it answers no more for its own NodeId. */
    suspend fun end() {
        run.coroutineContext.job.cancelAndJoin()
        answers.certify(null)
    }

    /** This node's own [certificate] for the current session, which it answers lookups with. */
    fun certify(certificate: IdentityCertificate?) {
        answers.certify(certificate)
    }

    /** A link proved [peer]'s valid [certificate]. */
    fun linked(
        peer: NodeId,
        certificate: IdentityCertificate,
    ) {
        certificates.put(peer.toByteArray(), certificate, clock.nowSeconds())
    }

    /** The certificate this node holds for [peer], or `null`. */
    fun certificate(peer: NodeId): IdentityCertificate? = certificates.get(peer.toByteArray(), clock.nowSeconds())

    /**
     * Looks [target]'s certificate up unless it is held or a lookup for it is outstanding: a request
     * now, then one per retry until a reply brings it or wall-clock [deadlineMillis] passes.
     */
    fun find(
        target: NodeId,
        deadlineMillis: Long,
    ) {
        if (!run.isActive || target in outstanding || certificate(target) != null) return
        val lookup =
            run.launch(start = CoroutineStart.LAZY) {
                try {
                    withTimeoutOrNull(deadlineMillis - clock.wallClockMillis()) { retry(target) }
                } finally {
                    // No new lookup of the target starts before this: a reply that ended it left its certificate held.
                    forget(target)
                }
            }
        outstanding[target] = lookup
        lookup.start()
    }

    /**
     * Whether [reply] answers an outstanding lookup of this node. If it opens to a valid certificate
     * of the NodeId looked up, the certificate is held and the lookup ends; later replies are dropped.
     */
    fun replied(reply: LookupReply): Boolean {
        val attempt = attempts[reply.header.destinationTag.toHexString()] ?: return false
        val target = attempt.target.toByteArray()
        val salt = reply.header.destinationTag
        val opened = seal.open(attempt.key, reply.ephemeralKey, salt, attempt.tag, reply.sealedCertificate)
        val check = opened?.let { IdentityCertificate.validate(crypto, it, profile, target, clock) }
        if (check is CertificateCheck.Valid) {
            certificates.put(target, check.certificate, clock.nowSeconds())
            outstanding.getValue(attempt.target).cancel()
            forget(attempt.target)
        }
        return true
    }

    private suspend fun retry(target: NodeId) {
        var doublings = 0
        while (true) {
            request(target)
            delay(Outbox.retryMillis(density(), doublings))
            doublings = minOf(doublings + 1, Outbox.DOUBLINGS_MAX)
        }
    }

    // A request with a fresh salt, ephemeral key and message_id on every link.
    private fun request(target: NodeId) {
        val salt = crypto.randomBytes(SALT)
        val key = crypto.x25519KeyPair()
        val tag = derivations.lookupTag(salt, target.toByteArray())
        val expiry = clock.nowSeconds() + RouteDiscovery.LIFETIME_MIN_SECONDS
        val header = FloodHeader(RouteDiscovery.RADIUS, crypto.randomBytes(MESSAGE_ID), expiry.toUInt())
        attempts[salt.toHexString()] = Attempt(target, key, tag)
        floods.put(header.messageId, remembered(expiry))
        val bytes = LookupRequest(header, salt, tag, key.publicKey).encode()
        lanes.peers.forEach { lanes.put(it, Parcel(header.messageId, relayed = false, { bytes }) {}) }
    }

    private fun forget(target: NodeId) {
        outstanding -= target
        attempts.values.removeAll { it.target == target }
    }

    // One request of a lookup of [target]: the ephemeral [key] its replies are sealed to, and its lookup [tag].
    private class Attempt(
        val target: NodeId,
        val key: KeyPair,
        val tag: ByteArray,
    )
}

/**
 * The answering side of a secured node's [Lookups]: answers the lookup requests for its own NodeId,
 * once [certify] gave it its certificate, or for one of its [certificates], and floods the others on
 * over [lanes]. [floods] are the requests it has seen, its own lookups' among them. Tells relays which
 * reply per salt to pass on. Runs on the node's serial dispatcher.
 */
internal class LookupAnswers(
    private val crypto: CryptoProvider,
    private val clock: Clock,
    private val lanes: Lanes,
    private val certificates: Certificates,
    private val floods: MessageIds,
) {
    private val derivations = Derivations(crypto)
    private val seal = LookupSeal(crypto)
    private val passed = MessageIds()
    private var own: IdentityCertificate? = null
    private var nodeId = ByteArray(0)

    /** This node's own [certificate] for the current session; `null` after a run. */
    fun certify(certificate: IdentityCertificate?) {
        own = certificate
        nodeId = if (certificate == null) ByteArray(0) else derivations.nodeId(certificate.ed25519Key)
    }

    /**
     * [request] arrived from [peer] at Unix second [nowSeconds]. Unless it is untimely or seen, this
     * node answers it for its own or a cached NodeId, or floods it on. Returns whether it was new: its
     * salt then keys a reverse route to [peer].
     */
    fun requested(
        peer: NodeId,
        request: LookupRequest,
        nowSeconds: Long,
    ): Boolean {
        val header = request.header
        if (!timely(header.expiry.toLong(), nowSeconds) || floods.has(header.messageId, clock.wallClockMillis())) {
            return false
        }
        floods.put(header.messageId, remembered(header.expiry.toLong()))
        val answer = answer(request)
        when {
            answer != null -> {
                reply(peer, request, answer)
            }

            header.hopLimit > 1 -> {
                val next = FloodHeader(header.hopLimit - 1, header.messageId, header.expiry)
                val bytes = LookupRequest(next, request.salt, request.lookupTag, request.ephemeralKey).encode()
                (lanes.peers - peer).forEach { lanes.put(it, Parcel(header.messageId, relayed = true, { bytes }) {}) }
            }
        }
        return true
    }

    /** Whether a relay passes on a reply to [salt] expiring at Unix second [expiry]: only the first per salt. */
    fun passes(
        salt: ByteArray,
        expiry: Long,
    ): Boolean {
        if (passed.has(salt, clock.wallClockMillis())) return false
        passed.put(salt, remembered(expiry))
        return true
    }

    // The certificate a request asks for: this node's own, or one it holds; null if it holds neither.
    private fun answer(request: LookupRequest): IdentityCertificate? {
        val asked = { candidate: ByteArray ->
            derivations.lookupTag(request.salt, candidate).contentEquals(request.lookupTag)
        }
        return own?.takeIf { asked(nodeId) } ?: certificates.find(clock.nowSeconds(), asked)
    }

    // A reply sealing [certificate] to the request, to its salt from a random source, on the asking link;
    // none for a low-order request key.
    private fun reply(
        peer: NodeId,
        request: LookupRequest,
        certificate: IdentityCertificate,
    ) {
        val key = crypto.x25519KeyPair()
        val sealed =
            seal.seal(key, request.ephemeralKey, request.salt, request.lookupTag, certificate.encode()) ?: return
        val expiry = (clock.nowSeconds() + RouteDiscovery.LIFETIME_MIN_SECONDS).toUInt()
        val messageId = crypto.randomBytes(MESSAGE_ID)
        val header = UnicastHeader(Router.HOP_LIMIT_MAX, messageId, expiry, request.salt, crypto.randomBytes(SALT))
        val bytes = LookupReply(header, key.publicKey, sealed).encode()
        lanes.put(peer, Parcel(header.messageId, relayed = true, { bytes }) {})
    }
}

private fun Clock.nowSeconds(): Long = wallClockMillis() / MILLIS_PER_SECOND

private const val SALT = 8
private const val MESSAGE_ID = 12
private const val MILLIS_PER_SECOND = 1_000L
