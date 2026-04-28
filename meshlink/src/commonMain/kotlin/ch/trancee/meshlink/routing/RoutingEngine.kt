package ch.trancee.meshlink.routing

import ch.trancee.meshlink.wire.Hello
import ch.trancee.meshlink.wire.Update
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Babel-adapted routing engine. Processes inbound Hello and Update messages, enforces the
 * feasibility condition for loop-free routing, tracks differential updates per neighbour, and
 * originates the self-route and Hello broadcasts on a virtual clock.
 *
 * The feasibility condition (RFC 8966 §2.4, simplified):
 * 1. Retraction (metric=0xFFFF): remove route + clear FD if seqNo is newer.
 * 2. No FD yet → accept unconditionally.
 * 3. Newer seqNo → accept, reset FD = totalCost.
 * 4. Same seqNo AND totalCost < FD → accept, update FD.
 * 5. Otherwise → reject.
 */
internal class RoutingEngine(
    private val routingTable: RoutingTable,
    private val localPeerId: ByteArray,
    private val localEdPublicKey: ByteArray,
    private val localDhPublicKey: ByteArray,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val config: RoutingConfig,
    private val diagnosticSink: ch.trancee.meshlink.api.DiagnosticSinkApi =
        ch.trancee.meshlink.api.NoOpDiagnosticSink,
) {
    // Feasibility distances: destinationKey → best-ever accepted total cost.
    private val feasibilityDistances: HashMap<List<Byte>, Double> = HashMap()

    // Per-neighbour, per-destination last-sent seqNo for differential tracking.
    // neighbourKey → (destinationKey → lastSentSeqNo)
    private val neighborLastSentSeqNo: HashMap<List<Byte>, HashMap<List<Byte>, UShort>> = HashMap()

    // Local sequence number incremented on each Hello tick.
    internal var localSeqNo: UShort = 0u

    // Outbound message bus; RouteCoordinator (T04) collects and re-emits.
    private val _outboundMessages = MutableSharedFlow<OutboundFrame>(extraBufferCapacity = 64)
    internal val outboundMessages: Flow<OutboundFrame> = _outboundMessages

    // ── processUpdate ──────────────────────────────────────────────────────────

    /**
     * Process an inbound Update from [fromPeerId] with pre-computed [linkCost].
     *
     * @return true if the update was accepted and installed, false if rejected.
     */
    fun processUpdate(fromPeerId: ByteArray, update: Update, linkCost: Double): Boolean {
        val dest = update.destination
        val destKey = dest.asList()

        // Retraction: metric == 0xFFFF
        if (update.metric == METRIC_RETRACTION) {
            val existingRoute = routingTable.lookupRoute(dest)
            if (existingRoute == null) return false
            if (!isSeqNoNewer(update.seqNo, existingRoute.seqNo)) return false
            routingTable.retract(dest)
            feasibilityDistances.remove(destKey)
            return true
        }

        // Feasibility check
        val totalCost = (update.metric.toDouble() / 100.0) + linkCost
        val existingFd = feasibilityDistances[destKey]

        // Step 2: no FD yet → accept unconditionally
        if (existingFd == null) {
            doInstall(fromPeerId, dest, update, totalCost)
            return true
        }

        val existingRoute = routingTable.lookupRoute(dest)

        // Step 3: newer seqNo → accept, reset FD
        if (existingRoute != null && isSeqNoNewer(update.seqNo, existingRoute.seqNo)) {
            doInstall(fromPeerId, dest, update, totalCost)
            return true
        }

        // Step 4: same seqNo AND strictly lower cost → accept, update FD
        if (
            existingRoute != null && update.seqNo == existingRoute.seqNo && totalCost < existingFd
        ) {
            doInstall(fromPeerId, dest, update, totalCost)
            return true
        }

        return false
    }

    private fun doInstall(
        fromPeerId: ByteArray,
        dest: ByteArray,
        update: Update,
        totalCost: Double,
    ) {
        routingTable.install(
            RouteEntry(
                destination = dest,
                nextHop = fromPeerId,
                metric = totalCost,
                seqNo = update.seqNo,
                feasibilityDistance = totalCost,
                expiresAt = clock() + config.routeExpiryMillis,
                ed25519PublicKey = update.ed25519PublicKey,
                x25519PublicKey = update.x25519PublicKey,
            )
        )
        feasibilityDistances[dest.asList()] = totalCost
    }

    // ── processHello ──────────────────────────────────────────────────────────

    /**
     * Process an inbound Hello from [fromPeerId] and return the list of Updates to send back.
     *
     * - New neighbour (not yet in tracking map): full dump.
     * - Known neighbour, digest mismatch: full dump.
     * - Known neighbour, digest match: differential (routes whose seqNo changed + always
     *   self-route).
     *
     * Updates [neighborLastSentSeqNo] after building the response.
     */
    fun processHello(fromPeerId: ByteArray, hello: Hello): List<Update> {
        val neighborKey = fromPeerId.asList()
        val existingNeighborSeqNos = neighborLastSentSeqNo[neighborKey]
        val allRoutes = routingTable.allRoutes()
        val updates = mutableListOf<Update>()

        if (existingNeighborSeqNos == null) {
            // New neighbour: full dump
            for (route in allRoutes) {
                updates.add(routeToUpdate(route))
            }
        } else if (hello.routeDigest != routingTable.routeDigest()) {
            // Digest mismatch: full dump
            for (route in allRoutes) {
                updates.add(routeToUpdate(route))
            }
        } else {
            // Digest match: differential — changed routes plus always self-route
            for (route in allRoutes) {
                val isSelf = route.destination.contentEquals(localPeerId)
                val sentSeqNo = existingNeighborSeqNos[route.destination.asList()]
                if (isSelf || sentSeqNo == null || sentSeqNo != route.seqNo) {
                    updates.add(routeToUpdate(route))
                }
            }
        }

        // Update tracking map to reflect what we just sent
        val newSeqNos = HashMap<List<Byte>, UShort>()
        for (route in allRoutes) {
            newSeqNos[route.destination.asList()] = route.seqNo
        }
        neighborLastSentSeqNo[neighborKey] = newSeqNos

        return updates
    }

    // ── retractRoutesVia ──────────────────────────────────────────────────────

    /**
     * Retract all routes whose next-hop is [nextHopPeerId] and return retraction Updates. Called
     * when a neighbour disconnects.
     */
    fun retractRoutesVia(nextHopPeerId: ByteArray): List<Update> {
        val retractions = mutableListOf<Update>()
        for (route in routingTable.allRoutes()) {
            if (route.nextHop.contentEquals(nextHopPeerId)) {
                routingTable.retract(route.destination)
                feasibilityDistances.remove(route.destination.asList())
                val newSeqNo = ((route.seqNo.toInt() + 1) and 0xFFFF).toUShort()
                retractions.add(
                    Update(
                        destination = route.destination,
                        metric = METRIC_RETRACTION,
                        seqNo = newSeqNo,
                        ed25519PublicKey = route.ed25519PublicKey,
                        x25519PublicKey = route.x25519PublicKey,
                    )
                )
            }
        }
        return retractions
    }

    // ── neighbor registration ─────────────────────────────────────────────────

    /** Register a neighbour for differential tracking (starts with an empty seqNo map). */
    fun registerNeighbor(peerId: ByteArray) {
        neighborLastSentSeqNo[peerId.asList()] = HashMap()
    }

    /** Unregister a neighbour, clearing its differential tracking state. */
    fun unregisterNeighbor(peerId: ByteArray) {
        neighborLastSentSeqNo.remove(peerId.asList())
    }

    // ── timers ────────────────────────────────────────────────────────────────

    /**
     * Start the Hello timer and full-dump timer coroutines in [scope].
     *
     * Full-dump timer is launched first so that when [RoutingConfig.fullDumpMultiplier] == 1 the
     * full-dump fires before the Hello at the same virtual timestamp — enabling test coverage of
     * the empty-table (loop-not-entered) path.
     *
     * - Full-dump timer (every helloIntervalMillis × fullDumpMultiplier): emits Updates for all
     *   routes.
     * - Hello timer (every helloIntervalMillis): installs self-route, emits Hello + self-route
     *   Update.
     */
    fun startTimers() {
        // Full-dump timer — launched first
        scope.launch {
            while (true) {
                delay(config.helloIntervalMillis * config.fullDumpMultiplier)
                for (route in routingTable.allRoutes()) {
                    val encodedMetric =
                        minOf(floor(route.metric * 100.0), 65534.0).toInt().toUShort()
                    _outboundMessages.emit(
                        OutboundFrame(
                            peerId = null,
                            message =
                                Update(
                                    destination = route.destination,
                                    metric = encodedMetric,
                                    seqNo = route.seqNo,
                                    ed25519PublicKey = route.ed25519PublicKey,
                                    x25519PublicKey = route.x25519PublicKey,
                                ),
                        )
                    )
                }
            }
        }

        // Hello timer — launched second
        scope.launch {
            while (true) {
                delay(config.helloIntervalMillis)
                localSeqNo = ((localSeqNo.toInt() + 1) and 0xFFFF).toUShort()

                // Install / refresh self-route
                routingTable.install(
                    RouteEntry(
                        destination = localPeerId,
                        nextHop = localPeerId,
                        metric = 0.0,
                        seqNo = localSeqNo,
                        feasibilityDistance = 0.0,
                        expiresAt = Long.MAX_VALUE,
                        ed25519PublicKey = localEdPublicKey,
                        x25519PublicKey = localDhPublicKey,
                    )
                )
                feasibilityDistances[localPeerId.asList()] = 0.0

                // Emit Hello broadcast
                _outboundMessages.emit(
                    OutboundFrame(
                        peerId = null,
                        message =
                            Hello(
                                sender = localPeerId,
                                seqNo = localSeqNo,
                                routeDigest = routingTable.routeDigest(),
                            ),
                    )
                )

                // Emit self-route Update broadcast
                _outboundMessages.emit(
                    OutboundFrame(
                        peerId = null,
                        message =
                            Update(
                                destination = localPeerId,
                                metric = 0u,
                                seqNo = localSeqNo,
                                ed25519PublicKey = localEdPublicKey,
                                x25519PublicKey = localDhPublicKey,
                            ),
                    )
                )
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Encode a stored route metric (Double) back to the wire UShort format (capped at 65534). */
    private fun routeToUpdate(route: RouteEntry): Update {
        val encodedMetric = minOf(floor(route.metric * 100.0), 65534.0).toInt().toUShort()
        return Update(
            destination = route.destination,
            metric = encodedMetric,
            seqNo = route.seqNo,
            ed25519PublicKey = route.ed25519PublicKey,
            x25519PublicKey = route.x25519PublicKey,
        )
    }
}
