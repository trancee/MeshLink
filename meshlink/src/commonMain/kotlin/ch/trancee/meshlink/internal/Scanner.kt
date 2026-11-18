package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A running node's discovery (spec "Advertisement", "Mesh tag", "Capability record"): serves this
 * node's capability record and advertises it on [radio], and keeps every sighting [discovery]
 * accepts as a link candidate, keyed by the peer's handle. It is the only reader of the radio's
 * events and hands every other event to the links.
 *
 * A peripheral rotates its advertisement once per epoch (spec "Mesh tag", D-02): each run draws a
 * phase from [random], and the node re-advertises a fresh payload for the current epoch whenever
 * the wall clock passes a multiple of the epoch length plus that phase. It draws each advertisement
 * identifier one rotation ahead and advertises exactly that one at the rotation; its neighbor lists
 * announce it to linked peers before (spec "Neighbor list"). A candidate whose mesh tag left the
 * accepted epochs is dropped with the next sighting.
 *
 * A central scans in windows of [SCAN_ON_MILLIS] every [SCAN_PERIOD_MILLIS], the low-density
 * `Balanced` schedule, and stops scanning whenever the run ends.
 *
 * A sighting whose identifier names a peer this node has a link to is no candidate: in a gated mesh
 * its resolvable tag resolves to the peer (spec "Resolvable tag"), and in any mesh the peer's
 * neighbor list announced it. In a gated mesh a run's [Recognition] also puts the node's own
 * resolvable tag in each payload.
 *
 * A sighting without a payload, as a backgrounded iPhone advertises (ADR 0010 decision 5), makes
 * its address an identifier-less candidate, which the links dial to read the advertisement from the
 * capability record and [vet]. An address that fails vetting is stale: sightings of it add no
 * candidate until it goes unseen for [STALE_FORGET_MILLIS].
 */
internal class Scanner(
    private val radio: RadioPort,
    private val clock: Clock,
    private val random: RandomSource,
    private val discovery: Discovery,
) {
    private val mutableCandidates = MutableStateFlow<Map<PeerHandle, Advertisement?>>(emptyMap())

    /**
     * The peers whose latest advertisement belongs to this mesh, with that advertisement, and the
     * identifier-less candidates, with `null`.
     */
    val candidates: StateFlow<Map<PeerHandle, Advertisement?>> = mutableCandidates

    /** The stale addresses, with when each was last sighted on the monotonic clock. */
    private val stale = mutableMapOf<PeerHandle, Long>()

    /**
     * Why the radio last refused or failed to serve the capability record, advertise or scan, or
     * `null`. Until real adapters show which failures recur, a failure ends discovery until the next
     * run; tickets 32 and 54 decide retries and diagnostics.
     */
    var failure: RadioFailure? = null
        private set

    /**
     * Serves the capability record, advertises and scans until cancelled, then stops advertising and
     * scanning and forgets the candidates. Passes every link event to [onLinkEvent]. Returns early,
     * recording [failure], if the radio refuses any of the three or a rotation, or reports a
     * [RadioEvent.Failed].
     * Restricted to [role], it neither serves nor advertises as [LinkRole.CENTRAL] and doesn't scan as
     * [LinkRole.PERIPHERAL]. Recognizes peers by [recognition]. With [coc], a peripheral listens
     * for L2CAP CoC channels and, if the radio can, offers the PSM in its capability record and sets
     * [Advertisement.COC_AVAILABLE] (spec "Bearer switch"). Passes every sighting's address, the
     * NodeId its resolvable tag resolved to, if any, and its RSSI to [onSighting].
     */
    suspend fun run(
        role: LinkRole?,
        recognition: Recognition,
        coc: Boolean,
        onLinkEvent: (RadioEvent) -> Unit,
        onSighting: (PeerHandle, NodeId?, Int) -> Unit,
    ) {
        try {
            stale.clear()
            val peripheral = role != LinkRole.CENTRAL
            val central = role != LinkRole.PERIPHERAL
            val listened = if (peripheral && coc) radio.listenForChannels() else null
            psm = (listened as? RadioResult.Ok<Int>)?.value
            if (peripheral) phase = random.nextInt(0, EPOCH_MILLIS.toInt())
            val refused =
                (if (peripheral) advertise(recognition, null) else null)
                    ?: (if (central) radio.startScanning() else null) as? RadioResult.Failed
            failure = refused?.reason
            if (refused == null) {
                val failed =
                    events(peripheral, central, recognition).firstOrNull {
                        dispatch(it, recognition, onLinkEvent, onSighting)
                    }
                        as RadioEvent.Failed?
                failure = failed?.reason
            }
        } finally {
            radio.stopScanning()
            radio.stopAdvertising()
            radio.stopListening()
            mutableCandidates.value = emptyMap()
        }
    }

    /** The PSM this run listens on, or `null`. */
    private var psm: Int? = null

    /** This run's rotation phase within an epoch. */
    private var phase = 0

    /** The identifier this node advertises after its next rotation. */
    private var next = ByteArray(0)

    /**
     * Serves the capability record and advertises a fresh payload for the current epoch with
     * [identifier], or a new one at the start of a run, and draws the identifier for the next
     * rotation; `null` if both succeed.
     */
    private fun advertise(
        recognition: Recognition,
        identifier: ByteArray?,
    ): RadioResult.Failed? {
        val now = clock.wallClockMillis()
        val keys = recognition.keys
        val epoch = epoch(now)
        val key = keys?.let { it.own(now).key }
        val own = identifier ?: discovery.identifier(epoch, key)
        val rotation = now + EPOCH_MILLIS - (now - phase) % EPOCH_MILLIS
        next = discovery.identifier(epoch(rotation), keys?.let { it.at(rotation).key })
        recognition.neighbors.advertised(own, next)
        val coc = if (psm == null) 0 else Advertisement.COC_AVAILABLE
        val flags = coc or if (radio.supportsLe2MPhy) Advertisement.PHY_2M else 0
        val payload = discovery.advertisement(epoch, flags = flags, identifier = own)
        val capability = CapabilityRecord.of(listOf(LinkPolicy.PROTOCOL_VERSION), payload, psm)
        return radio.serveCapability(capability.encode()) as? RadioResult.Failed
            ?: radio.startAdvertising(payload) as? RadioResult.Failed
    }

    /**
     * The radio's events, as [peripheral] the failures of [rotations] and as [central] those of
     * [windows], until the radio's events end.
     */
    private fun events(
        peripheral: Boolean,
        central: Boolean,
        recognition: Recognition,
    ): Flow<RadioEvent> =
        channelFlow {
            val rotating = launch { if (peripheral) rotations(recognition).collect { send(it) } }
            val windowing = launch { if (central) windows().collect { send(it) } }
            radio.events.collect { send(it) }
            rotating.cancel()
            windowing.cancel()
        }

    /**
     * Ends the scan window [run] started after [SCAN_ON_MILLIS], then scans again in a window of
     * that length every [SCAN_PERIOD_MILLIS]; emits a [RadioEvent.Failed] if the radio refuses a start.
     */
    private fun windows(): Flow<RadioEvent> =
        flow {
            while (true) {
                delay(SCAN_ON_MILLIS)
                radio.stopScanning()
                delay(SCAN_PERIOD_MILLIS - SCAN_ON_MILLIS)
                (radio.startScanning() as? RadioResult.Failed)?.let { emit(RadioEvent.Failed(it.reason)) }
            }
        }

    /**
     * Re-advertises at this run's phase in every epoch with the identifier drawn ahead, and sends the
     * neighbor lists before and after; emits a [RadioEvent.Failed] if the radio refuses.
     */
    private fun rotations(recognition: Recognition): Flow<RadioEvent> =
        flow {
            while (true) {
                val now = clock.wallClockMillis()
                // The wall clock is far past the phase, so the remainder is never negative.
                delay(EPOCH_MILLIS - (now - phase) % EPOCH_MILLIS)
                recognition.neighbors.changed()
                advertise(recognition, next)?.let { emit(RadioEvent.Failed(it.reason)) }
                recognition.neighbors.changed()
            }
        }

    /** Handles [event]; returns whether it is a failure that ends discovery. */
    private fun dispatch(
        event: RadioEvent,
        recognition: Recognition,
        onLinkEvent: (RadioEvent) -> Unit,
        onSighting: (PeerHandle, NodeId?, Int) -> Unit,
    ): Boolean {
        when (event) {
            is RadioEvent.Sighted -> sighted(event.sighting, recognition, onSighting)
            is RadioEvent.Connected, is RadioEvent.Disconnected, is RadioEvent.ChannelOpened -> onLinkEvent(event)
            is RadioEvent.Failed -> Unit
        }
        return event is RadioEvent.Failed
    }

    private fun sighted(
        sighting: Sighting,
        recognition: Recognition,
        onSighting: (PeerHandle, NodeId?, Int) -> Unit,
    ) {
        val epoch = epoch()
        val payload = sighting.payload
        val accepted = payload?.let { discovery.accept(it, epoch) }
        val id = accepted?.let { recognition.recognize(it.resolvableTag, epoch, clock.wallClockMillis()) }
        onSighting(sighting.peer, id, sighting.rssi)
        val linked = id != null && recognition.linked(id)
        val sighted = accepted?.takeUnless { linked }
        val anonymous = payload == null && fresh(sighting.peer)
        mutableCandidates.update { candidates ->
            val current = candidates.filterValues { it == null || discovery.accept(it.encode(), epoch) != null }
            when {
                sighted != null -> current + (sighting.peer to sighted)
                anonymous -> current + (sighting.peer to null)
                else -> current
            }
        }
    }

    /**
     * Whether [handle] is not stale. Forgets the stale addresses unseen for [STALE_FORGET_MILLIS]
     * first, and records this sighting of a stale one.
     */
    private fun fresh(handle: PeerHandle): Boolean {
        val now = clock.monotonicMillis()
        stale.values.removeAll { now - it > STALE_FORGET_MILLIS }
        val known = handle in stale
        if (known) stale[handle] = now
        return !known
    }

    /**
     * Vets the identifier-less candidate [handle] by the [advertisement] its capability record
     * carries, `null` if the links couldn't read one: returns whether it belongs to this mesh, and
     * otherwise marks the address stale and drops the candidate.
     */
    fun vet(
        handle: PeerHandle,
        advertisement: ByteArray?,
    ): Boolean {
        val accepted = advertisement?.let { discovery.accept(it, epoch()) } != null
        if (!accepted) {
            stale[handle] = clock.monotonicMillis()
            mutableCandidates.update { it - handle }
        }
        return accepted
    }

    private fun epoch(nowMillis: Long = clock.wallClockMillis()): UInt {
        val seconds = nowMillis / MILLIS_PER_SECOND
        return Derivations.epoch(seconds)
    }

    private companion object {
        /** How long each scan window lasts: the `Balanced` low-density schedule (ADR 0010 decision 6). */
        const val SCAN_ON_MILLIS = 4_000L

        /** How often a scan window starts, so at most four start in any 30 s (library rule R11). */
        const val SCAN_PERIOD_MILLIS = 8_000L

        /** How long a stale address stays stale without a sighting (ticket 32, Q4). */
        const val STALE_FORGET_MILLIS = 600_000L
        const val MILLIS_PER_SECOND = 1_000
        const val EPOCH_MILLIS = Derivations.EPOCH_SECONDS * MILLIS_PER_SECOND.toLong()
    }
}

/**
 * How a run recognizes its peers: by their resolving [keys] in a gated mesh, else `null`, and by the
 * identifiers their [neighbors] lists announced; and whether it has a link to a peer ([linked]).
 */
internal class Recognition(
    val keys: ResolvingKeys?,
    val neighbors: NeighborLists,
    val linked: (NodeId) -> Boolean,
) {
    /** The peer advertising [identifier] in [epoch], at [nowMillis], or `null`. */
    fun recognize(
        identifier: ByteArray,
        epoch: UInt,
        nowMillis: Long,
    ): NodeId? = keys?.resolve(identifier, epoch, nowMillis) ?: neighbors.peerOf(identifier)
}
