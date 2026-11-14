package ch.trancee.meshlink.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update

/**
 * A running node's discovery (spec "Advertisement", "Mesh tag", "Capability record"): serves this
 * node's capability record and advertises it on [radio], and keeps every sighting [discovery]
 * accepts as a link candidate, keyed by the peer's handle. It is the only reader of the radio's
 * events and hands every other event to the links.
 */
internal class Scanner(
    private val radio: RadioPort,
    private val clock: Clock,
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
     * recording [failure], if the radio refuses any of the three or reports a [RadioEvent.Failed].
     */
    suspend fun run(onLinkEvent: (RadioEvent) -> Unit) {
        try {
            val payload = discovery.advertisement(epoch())
            val capability = CapabilityRecord.of(listOf(LinkPolicy.PROTOCOL_VERSION), payload)
            val refused =
                radio.serveCapability(capability.encode()) as? RadioResult.Failed
                    ?: radio.startAdvertising(payload) as? RadioResult.Failed
                    ?: radio.startScanning() as? RadioResult.Failed
            failure = refused?.reason
            if (refused == null) {
                val failed = radio.events.firstOrNull { dispatch(it, onLinkEvent) } as RadioEvent.Failed?
                failure = failed?.reason
            }
        } finally {
            radio.stopScanning()
            radio.stopAdvertising()
            mutableCandidates.value = emptyMap()
        }
    }

    /** Handles [event]; returns whether it is a failure that ends discovery. */
    private fun dispatch(
        event: RadioEvent,
        onLinkEvent: (RadioEvent) -> Unit,
    ): Boolean {
        when (event) {
            is RadioEvent.Sighted -> sighted(event.sighting)
            is RadioEvent.Connected, is RadioEvent.Disconnected -> onLinkEvent(event)
            is RadioEvent.Failed -> Unit
        }
        return event is RadioEvent.Failed
    }

    private fun sighted(sighting: Sighting) {
        discovery.accept(sighting.payload, epoch())?.let { advertisement ->
            mutableCandidates.update { it + (sighting.peer to advertisement) }
        }
    }

    private fun epoch(): UInt = Derivations.epoch(clock.wallClockMillis() / MILLIS_PER_SECOND)

    private companion object {
        const val MILLIS_PER_SECOND = 1_000
    }
}
