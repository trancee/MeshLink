package ch.trancee.meshlink.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update

/**
 * A running node's discovery (spec "Advertisement", "Mesh tag"): advertises this node on [radio]
 * and keeps every sighting [discovery] accepts as a link candidate, keyed by the peer's handle.
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
     * Why the radio last refused to advertise or scan, or `null`. Until real adapters show which
     * failures recur, a refusal ends discovery until the next run; tickets 13, 32 and 54 decide
     * retries and diagnostics.
     */
    var failure: RadioFailure? = null
        private set

    /**
     * Advertises and scans until cancelled, then stops both and forgets the candidates. Returns
     * early, recording [failure], if the radio refuses to advertise or scan.
     */
    suspend fun run() {
        try {
            val refused =
                radio.startAdvertising(discovery.advertisement(epoch())) as? RadioResult.Failed
                    ?: radio.startScanning() as? RadioResult.Failed
            failure = refused?.reason
            if (refused == null) {
                radio.events.filterIsInstance<RadioEvent.Sighted>().collect { sighted(it.sighting) }
            }
        } finally {
            radio.stopScanning()
            radio.stopAdvertising()
            mutableCandidates.value = emptyMap()
        }
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
