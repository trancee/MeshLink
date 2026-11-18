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
 * the wall clock passes a multiple of the epoch length plus that phase. A candidate whose mesh tag
 * left the accepted epochs is dropped with the next sighting.
 *
 * In a gated mesh a run's [Recognition] puts the node's own resolvable tag in each payload, and a
 * sighting whose resolvable tag resolves to a peer this node has a link to is no candidate (spec
 * "Resolvable tag").
 */
internal class Scanner(
    private val radio: RadioPort,
    private val clock: Clock,
    private val random: RandomSource,
    private val discovery: Discovery,
) {
    private val mutableCandidates = MutableStateFlow<Map<PeerHandle, Advertisement>>(emptyMap())

    /** The peers whose latest advertisement belongs to this mesh, with that advertisement. */
    val candidates: StateFlow<Map<PeerHandle, Advertisement>> = mutableCandidates

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
     * [LinkRole.PERIPHERAL]. A gated mesh passes its [recognition]. With [coc], a peripheral listens
     * for L2CAP CoC channels and, if the radio can, offers the PSM in its capability record and sets
     * [Advertisement.COC_AVAILABLE] (spec "Bearer switch").
     */
    suspend fun run(
        role: LinkRole?,
        recognition: Recognition?,
        coc: Boolean,
        onLinkEvent: (RadioEvent) -> Unit,
    ) {
        try {
            val peripheral = role != LinkRole.CENTRAL
            val central = role != LinkRole.PERIPHERAL
            val listened = if (peripheral && coc) radio.listenForChannels() else null
            psm = (listened as? RadioResult.Ok<Int>)?.value
            val refused =
                (if (peripheral) advertise(recognition) else null)
                    ?: (if (central) radio.startScanning() else null) as? RadioResult.Failed
            failure = refused?.reason
            if (refused == null) {
                val failed =
                    events(peripheral, recognition).firstOrNull { dispatch(it, recognition, onLinkEvent) }
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

    /** Serves the capability record and advertises a fresh payload for the current epoch; `null` if both succeed. */
    private fun advertise(recognition: Recognition?): RadioResult.Failed? {
        val key = recognition?.let { it.keys.own(clock.wallClockMillis()).key }
        val flags = if (psm == null) 0 else Advertisement.COC_AVAILABLE
        val payload = discovery.advertisement(epoch(), key, flags)
        val capability = CapabilityRecord.of(listOf(LinkPolicy.PROTOCOL_VERSION), payload, psm)
        return radio.serveCapability(capability.encode()) as? RadioResult.Failed
            ?: radio.startAdvertising(payload) as? RadioResult.Failed
    }

    /** The radio's events, and as [peripheral] the failures of [rotations] until the radio's events end. */
    private fun events(
        peripheral: Boolean,
        recognition: Recognition?,
    ): Flow<RadioEvent> =
        channelFlow {
            val rotating = launch { if (peripheral) rotations(recognition).collect { send(it) } }
            radio.events.collect { send(it) }
            rotating.cancel()
        }

    /** Re-advertises at this run's phase in every epoch; emits a [RadioEvent.Failed] if the radio refuses. */
    private fun rotations(recognition: Recognition?): Flow<RadioEvent> =
        flow {
            val phase = random.nextInt(0, EPOCH_MILLIS.toInt())
            while (true) {
                val now = clock.wallClockMillis()
                // The wall clock is far past the phase, so the remainder is never negative.
                delay(EPOCH_MILLIS - (now - phase) % EPOCH_MILLIS)
                advertise(recognition)?.let { emit(RadioEvent.Failed(it.reason)) }
            }
        }

    /** Handles [event]; returns whether it is a failure that ends discovery. */
    private fun dispatch(
        event: RadioEvent,
        recognition: Recognition?,
        onLinkEvent: (RadioEvent) -> Unit,
    ): Boolean {
        when (event) {
            is RadioEvent.Sighted -> sighted(event.sighting, recognition)
            is RadioEvent.Connected, is RadioEvent.Disconnected, is RadioEvent.ChannelOpened -> onLinkEvent(event)
            is RadioEvent.Failed -> Unit
        }
        return event is RadioEvent.Failed
    }

    private fun sighted(
        sighting: Sighting,
        recognition: Recognition?,
    ) {
        val epoch = epoch()
        val linked = { advertisement: Advertisement ->
            recognition?.let {
                it.keys.resolve(advertisement.resolvableTag, epoch, clock.wallClockMillis())?.let(it.linked)
            } == true
        }
        val sighted = discovery.accept(sighting.payload, epoch)?.takeUnless(linked)
        mutableCandidates.update { candidates ->
            val current = candidates.filterValues { discovery.accept(it.encode(), epoch) != null }
            if (sighted == null) current else current + (sighting.peer to sighted)
        }
    }

    private fun epoch(): UInt = Derivations.epoch(clock.wallClockMillis() / MILLIS_PER_SECOND)

    private companion object {
        const val MILLIS_PER_SECOND = 1_000
        const val EPOCH_MILLIS = Derivations.EPOCH_SECONDS * MILLIS_PER_SECOND.toLong()
    }
}

/**
 * How a gated-mesh run recognizes its peers: by their resolving [keys], and whether it has a link to a peer
 * ([linked]).
 */
internal class Recognition(
    val keys: ResolvingKeys,
    val linked: (NodeId) -> Boolean,
)
