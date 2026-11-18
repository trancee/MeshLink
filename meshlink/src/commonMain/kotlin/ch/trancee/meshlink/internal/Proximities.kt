package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Proximity
import ch.trancee.meshlink.ProximityBucket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * The proximity of each linked peer (ADR 0007 decision 10): an exponentially weighted moving
 * average of its RSSI samples with weight [ALPHA], bucketed at [IMMEDIATE_DBM] and [NEAR_DBM], and
 * [ProximityBucket.Unknown] once the last sample is [STALE_MILLIS] old. Samples come from the
 * sightings of a linked peer, matched by address or resolved NodeId, and from reading the RSSI of
 * each central link every [POLL_MILLIS].
 *
 * Calls [onChange] so the node republishes its peers after every reading, when a sighting moves a
 * peer to another bucket, and when a peer's sample goes stale. Runs its timers in [scope]; not
 * thread-safe, like the links that own it.
 */
internal class Proximities(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val onChange: () -> Unit,
) {
    private val entries = mutableMapOf<NodeId, Entry>()

    /**
     * Starts measuring the peer [id] linked at [handle], replacing its former link's measurements,
     * and polls [readRssi] if the link is central.
     */
    fun linked(
        id: NodeId,
        handle: PeerHandle,
        readRssi: (suspend () -> RadioResult<Int>)?,
    ) {
        unlinked(id)
        val entry = Entry(handle)
        entries[id] = entry
        entry.poll = readRssi?.let { scope.launch { poll(entry, it) } }
    }

    /** Stops measuring the peer [id]. */
    fun unlinked(id: NodeId) {
        entries.remove(id)?.stop()
    }

    /** Records a sighting at [handle], of the peer [id] if its resolvable tag resolved, with [rssi] in dBm. */
    fun sighted(
        handle: PeerHandle,
        id: NodeId?,
        rssi: Int,
    ) {
        val entry = entries.values.firstOrNull { it.handle == handle } ?: id?.let { entries[it] } ?: return
        val before = entry.bucket()
        entry.sample(rssi)
        if (entry.bucket() != before) onChange()
    }

    /** The proximity of the linked peer [id]. */
    fun of(id: NodeId): Proximity = entries.getValue(id).proximity()

    private suspend fun poll(
        entry: Entry,
        readRssi: suspend () -> RadioResult<Int>,
    ) {
        var supported = true
        while (supported) {
            delay(POLL_MILLIS)
            when (val result = readRssi()) {
                is RadioResult.Ok -> {
                    entry.sample(result.value)
                    onChange()
                }

                is RadioResult.Failed -> {
                    supported = result.reason != RadioFailure.UNSUPPORTED
                }
            }
        }
    }

    private inner class Entry(
        val handle: PeerHandle,
    ) {
        var poll: Job? = null
        private var expiry: Job? = null
        private var rssi: Double? = null
        private var measuredMillis = 0L
        private var measuredAt: Instant? = null

        /** Averages [sample] into the RSSI, or starts over from it if the RSSI is stale. */
        fun sample(sample: Int) {
            val last = rssi
            rssi = if (last == null || stale()) sample.toDouble() else last + ALPHA * (sample - last)
            measuredMillis = clock.monotonicMillis()
            measuredAt = Instant.fromEpochMilliseconds(clock.wallClockMillis())
            expiry?.cancel()
            expiry =
                scope.launch {
                    delay(STALE_MILLIS)
                    onChange()
                }
        }

        fun bucket(): ProximityBucket {
            val current = rssi
            return when {
                current == null || stale() -> ProximityBucket.Unknown
                current >= IMMEDIATE_DBM -> ProximityBucket.Immediate
                current >= NEAR_DBM -> ProximityBucket.Near
                else -> ProximityBucket.Far
            }
        }

        fun proximity(): Proximity = Proximity(rssi, bucket(), measuredAt)

        fun stop() {
            poll?.cancel()
            expiry?.cancel()
        }

        private fun stale(): Boolean = clock.monotonicMillis() - measuredMillis >= STALE_MILLIS
    }

    private companion object {
        /** The weight of a new sample in the average. */
        const val ALPHA = 0.25

        /** The weakest RSSI of [ProximityBucket.Immediate], in dBm. */
        const val IMMEDIATE_DBM = -55.0

        /** The weakest RSSI of [ProximityBucket.Near], in dBm. */
        const val NEAR_DBM = -75.0

        /** How old a sample may be before the peer's proximity is [ProximityBucket.Unknown]. */
        const val STALE_MILLIS = 30_000L

        /** How often a central link's RSSI is read (ticket 32, Q5). */
        const val POLL_MILLIS = 10_000L
    }
}
