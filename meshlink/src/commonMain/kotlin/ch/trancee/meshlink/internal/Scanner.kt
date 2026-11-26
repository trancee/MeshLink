package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DensityClass
import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Power
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What sets a [Scanner]'s rests between windows: its [power] policy and a [pinned] density class, if any. */
internal class ScanSchedule(
    val power: Power = Power.Balanced,
    val pinned: DensityClass? = null,
)

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
 * A central scans in windows of [Scanner.ON_MILLIS] and stops scanning whenever the run ends. At
 * the end of each window it estimates its local density from the sightings of the last 10 s and its
 * links, classifies it, or keeps the [schedule]'s pinned class, and rests as long as the schedule's
 * power sets for its [density] class (ADR 0008 decisions 2 and 3). Each run starts in the low class.
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
    schedule: ScanSchedule = ScanSchedule(),
    private val diagnose: (DiagnosticEvent) -> Unit = {},
) {
    private val policy = schedule.power
    private val pinned = schedule.pinned
    private var estimator = DensityEstimator(clock)
    private var classifier = DensityClassifier(clock, pinned)

    /** This node's density class, which sets its link budget and scan schedule. */
    val density: DensityClass get() = classifier.current

    private val mutableCandidates = MutableStateFlow<Map<PeerHandle, Candidate>>(emptyMap())

    /**
     * The peers whose latest advertisement belongs to this mesh and the identifier-less candidates,
     * each with when it was last heard; every sighting of one updates it.
     */
    val candidates: StateFlow<Map<PeerHandle, Candidate>> = mutableCandidates

    /** The stale addresses, with when each was last sighted on the monotonic clock. */
    private val stale = mutableMapOf<PeerHandle, Long>()

    /**
     * Why the radio last refused or failed to serve the capability record or scan, or `null`. Until
     * real adapters show which failures recur, a failure ends discovery until the next run; tickets
     * 32 and 54 decide retries and diagnostics.
     */
    var failure: RadioFailure? = null
        private set

    private val mutableAdvertisingLost = MutableStateFlow(false)

    /** Reports a radio failure and returns its reason; see [reportFailure]. */
    private val failed: (RadioEvent) -> RadioFailure? = { event ->
        reportFailure(event, diagnose) { radio.lose(mutableAdvertisingLost) }
    }

    /**
     * Whether the radio refused to advertise, or reported a [RadioEvent.AdvertisingFailed], during
     * the current run. The run then goes on without advertising (ADR 0010 decision 6): it keeps
     * scanning, serving the capability record and listening, so links already open stay up.
     */
    val advertisingLost: StateFlow<Boolean> = mutableAdvertisingLost

    /**
     * Serves the capability record, advertises and scans until cancelled, then stops advertising and
     * scanning and forgets the candidates. Passes every link event to [onLinkEvent]. Returns early,
     * recording [failure], if the radio refuses to serve or scan, or reports a [RadioEvent.ScanFailed]
     * or [RadioEvent.ServeFailed]; a
     * refused or failed advertisement sets [advertisingLost] instead.
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
            estimator = DensityEstimator(clock)
            classifier = DensityClassifier(clock, pinned)
            val peripheral = role != LinkRole.CENTRAL
            val central = role != LinkRole.PERIPHERAL
            val listened = if (peripheral && coc) radio.listenForChannels() else null
            psm = (listened as? RadioResult.Ok<Int>)?.value
            if (peripheral) phase = random.nextInt(0, EPOCH_MILLIS.toInt())
            val refused =
                (if (peripheral) advertise(recognition, null) else null)
                    ?: (if (central) radio.startScanning() as? RadioResult.Failed else null)?.scanFailed()
            failure = refused?.let(failed)
            if (refused == null) {
                if (central) diagnose(DiagnosticEvent.ScanWindow(DiagnosticEvent.ScanWindow.Phase.Started))
                failure =
                    events(peripheral, central, recognition)
                        .map { dispatch(it, recognition, onLinkEvent, onSighting) }
                        .firstOrNull { it != null }
            }
        } finally {
            radio.stopScanning()
            radio.stopAdvertising()
            radio.stopListening()
            mutableCandidates.value = emptyMap()
            mutableAdvertisingLost.value = false
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
     * rotation; `null` unless serving fails. A refused advertisement loses advertising for the rest of the run.
     */
    private fun advertise(
        recognition: Recognition,
        identifier: ByteArray?,
    ): RadioEvent.ServeFailed? {
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
        val refused = (radio.serveCapability(capability.encode()) as? RadioResult.Failed)?.serveFailed()
        val advertised = if (refused == null) radio.startAdvertising(payload) else null
        if (advertised is RadioResult.Failed) {
            failed(RadioEvent.AdvertisingFailed(advertised.reason, advertised.platformCode))
        }
        return refused
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
            val windowing = launch { if (central) windows(recognition).collect { send(it) } }
            radio.events.collect { send(it) }
            rotating.cancel()
            windowing.cancel()
        }

    /**
     * Ends the scan window [run] started after [Scanner.ON_MILLIS], classifies the node's density,
     * rests as long as [policy] sets for its class and scans again for as long, and so on; emits a
     * [RadioEvent.ScanFailed] if the radio refuses a start. Counts the links [recognition] knows.
     */
    private fun windows(recognition: Recognition): Flow<RadioEvent> =
        flow {
            while (true) {
                delay(Scanner.ON_MILLIS)
                radio.stopScanning()
                diagnose(DiagnosticEvent.ScanWindow(DiagnosticEvent.ScanWindow.Phase.Stopped))
                val estimate =
                    estimator.estimate(recognition.links()) { identifier, epoch ->
                        recognition.recognize(identifier, epoch, clock.wallClockMillis())
                    }
                val before = classifier.current
                val density = classifier.classify(estimate)
                if (density != before) diagnose(DiagnosticEvent.DensityClassChanged(before, density))
                delay(policy.offMillis(density))
                when (val started = radio.startScanning()) {
                    is RadioResult.Failed -> emit(started.scanFailed())
                    is RadioResult.Ok -> diagnose(DiagnosticEvent.ScanWindow(DiagnosticEvent.ScanWindow.Phase.Started))
                }
            }
        }

    /**
     * Re-advertises at this run's phase in every epoch with the identifier drawn ahead, and sends the
     * neighbor lists before and after, until advertising is lost; emits a [RadioEvent.ServeFailed] if the
     * radio refuses to serve the capability record.
     */
    private fun rotations(recognition: Recognition): Flow<RadioEvent> =
        flow {
            while (true) {
                val now = clock.wallClockMillis()
                // The wall clock is far past the phase, so the remainder is never negative.
                delay(EPOCH_MILLIS - (now - phase) % EPOCH_MILLIS)
                if (mutableAdvertisingLost.value) break
                recognition.neighbors.changed()
                advertise(recognition, next)?.let { emit(it) }
                recognition.neighbors.changed()
            }
        }

    /** Handles [event]; returns the reason if it is a failure that ends discovery. */
    private fun dispatch(
        event: RadioEvent,
        recognition: Recognition,
        onLinkEvent: (RadioEvent) -> Unit,
        onSighting: (PeerHandle, NodeId?, Int) -> Unit,
    ): RadioFailure? =
        when (event) {
            is RadioEvent.Sighted -> {
                sighted(event.sighting, recognition, onSighting).let { null }
            }

            is RadioEvent.Connected, is RadioEvent.Disconnected, is RadioEvent.ChannelOpened -> {
                onLinkEvent(event).let { null }
            }

            is RadioEvent.AdvertisingFailed, is RadioEvent.ScanFailed, is RadioEvent.ServeFailed -> {
                failed(event)
            }

            is RadioEvent.GattRetried -> {
                diagnose(DiagnosticEvent.GattOperationRetried(event.platformCode)).let { null }
            }
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
        val linked = id != null && id in recognition.links().values
        val sighted = accepted?.takeUnless { linked }
        val anonymous = payload == null && fresh(sighting.peer)
        accepted?.let { estimator.heard(it.resolvableTag, epoch) }
        if (anonymous) estimator.heardAnonymous(sighting.peer)
        val heard = clock.monotonicMillis()
        mutableCandidates.update { candidates ->
            val current =
                candidates.filterValues { candidate ->
                    candidate.advertisement.let { it == null || discovery.accept(it.encode(), epoch) != null }
                }
            when {
                sighted != null || anonymous -> {
                    val candidate = candidate(current[sighting.peer], sighted, heard, id, sighting.rssi)
                    current + (sighting.peer to candidate)
                }

                else -> {
                    current
                }
            }
        }
    }

    /**
     * The candidate [advertisement] makes, or an identifier-less one if `null`, [heard] now at [rssi]
     * dBm. If [prior] carried the same identifier, or none, and was heard less than [GAP_MILLIS] ago,
     * it was first heard when [prior] was, and its signal moves [RSSI_ALPHA] of the way from
     * [prior]'s to [rssi]; else it was first heard now, at [rssi]. Known if its identifier resolved
     * to the peer [id].
     */
    private fun candidate(
        prior: Candidate?,
        advertisement: Advertisement?,
        heard: Long,
        id: NodeId?,
        rssi: Int,
    ): Candidate {
        val tag = advertisement?.resolvableTag
        val same =
            prior?.takeIf { heard - it.heard < GAP_MILLIS && it.advertisement?.resolvableTag.contentEquals(tag) }
        val since = same?.since ?: heard
        val smoothed = same?.let { it.rssi + RSSI_ALPHA * (rssi - it.rssi) } ?: rssi.toDouble()
        return Candidate(advertisement, heard, since, known = id != null, rssi = smoothed)
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
            diagnose(DiagnosticEvent.StaleAddressMarked())
            estimator.forget(handle)
            mutableCandidates.update { it - handle }
        }
        return accepted
    }

    private fun epoch(nowMillis: Long = clock.wallClockMillis()): UInt {
        val seconds = nowMillis / MILLIS_PER_SECOND
        return Derivations.epoch(seconds)
    }

    companion object {
        /** How long each scan window lasts, in every class and [Power]. */
        const val ON_MILLIS: Long = 4_000

        /** How long a stale address stays stale without a sighting (ticket 32, Q4). */
        private const val STALE_FORGET_MILLIS = 600_000L

        /** How long a candidate may go unheard and keep when it was first heard (bridge rule). */
        private const val GAP_MILLIS = 60_000L

        /** How far each sighting moves a candidate's smoothed signal toward its own, as in [Proximities]. */
        private const val RSSI_ALPHA = 0.25

        private const val MILLIS_PER_SECOND = 1_000
        private const val EPOCH_MILLIS = Derivations.EPOCH_SECONDS * MILLIS_PER_SECOND.toLong()
    }
}

/**
 * How a run recognizes its peers: by their resolving [keys] in a gated mesh, else `null`, and by the
 * identifiers their [neighbors] lists announced; and the peers it has a link to, by handle ([links]).
 */
internal class Recognition(
    val keys: ResolvingKeys?,
    val neighbors: NeighborLists,
    val links: () -> Map<PeerHandle, NodeId>,
) {
    /** The peer advertising [identifier] in [epoch], at [nowMillis], or `null`. */
    fun recognize(
        identifier: ByteArray,
        epoch: UInt,
        nowMillis: Long,
    ): NodeId? = keys?.resolve(identifier, epoch, nowMillis) ?: neighbors.peerOf(identifier)
}

/** Stops advertising for the rest of the run and records it in [lost]. */
private fun RadioPort.lose(lost: MutableStateFlow<Boolean>) {
    stopAdvertising()
    lost.value = true
}

private fun RadioResult.Failed.scanFailed() = RadioEvent.ScanFailed(reason, platformCode)

private fun RadioResult.Failed.serveFailed() = RadioEvent.ServeFailed(reason, platformCode)

/**
 * Reports the radio failure [event] through [diagnose] (ADR 0020 decision 7) and returns its reason.
 * A lost advertisement calls [loseAdvertising], for the rest of the run, and returns `null`.
 */
private fun reportFailure(
    event: RadioEvent,
    diagnose: (DiagnosticEvent) -> Unit,
    loseAdvertising: () -> Unit,
): RadioFailure? =
    when (event) {
        is RadioEvent.ScanFailed -> {
            diagnose(DiagnosticEvent.ScanFailed(event.platformCode))
            if (event.registrationFailed) diagnose(DiagnosticEvent.ScannerRegistrationFailed())
            event.reason
        }

        is RadioEvent.ServeFailed -> {
            diagnose(DiagnosticEvent.GattServiceFailed(event.platformCode))
            event.reason
        }

        else -> {
            val lost = event as RadioEvent.AdvertisingFailed
            diagnose(DiagnosticEvent.AdvertisingFailed(lost.platformCode))
            loseAdvertising()
            null
        }
    }
