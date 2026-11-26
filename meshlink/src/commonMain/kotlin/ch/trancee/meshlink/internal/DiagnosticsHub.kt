package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.LinkRef
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.PeerRef
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.SecurityProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * The diagnostics hub (ADR 0020): filters `Debug` events, coalesces repeats and fans events out to
 * every collector of [events], each with its own bounded buffer, and to [log], if set, whether or
 * not anyone collects. It also names peers and links for events with keys and counters drawn anew by
 * [begin] on each start.
 */
internal class DiagnosticsHub(
    private val scope: CoroutineScope,
    private val debug: Boolean,
    private val crypto: CryptoProvider,
    private val log: PlatformLog? = null,
) {
    private val subscribers = MutableStateFlow<List<Subscriber>>(emptyList())
    private val closed = MutableStateFlow(false)
    private val windows = MutableStateFlow<Map<Any, Repeats>>(emptyMap())
    private val peerKey = MutableStateFlow(crypto.randomBytes(PEER_KEY_BYTES))
    private val links = MutableStateFlow(0)

    /** A hot flow of events from the moment of collection, without replay; completes on [close]. */
    val events: Flow<DiagnosticEvent> =
        flow {
            val subscriber = Subscriber()
            subscribers.update { it + subscriber }
            if (closed.value) subscriber.channel.close()
            try {
                for (event in subscriber.channel) {
                    val dropped = subscriber.dropped.getAndUpdate { 0 }
                    if (dropped > 0) emit(DiagnosticEvent.DiagnosticsDropped(dropped))
                    emit(event)
                }
            } finally {
                subscribers.update { it - subscriber }
            }
        }

    /** Emits [event] at once, or counts it as a repeat within its code and reference's window. */
    fun report(event: DiagnosticEvent) {
        if (event.severity == DiagnosticEvent.Severity.Debug && !debug) return
        val key = event.code to event.reference
        var opened = false
        windows.update { open ->
            val repeats = open[key]
            opened = repeats == null
            open + (key to if (repeats == null) Repeats(0, event) else Repeats(repeats.count + 1, event))
        }
        if (opened) {
            deliver(event)
            scope.launch {
                delay(WINDOW_MS)
                flush(key)
            }
        }
    }

    /**
     * Begins a start of a node in [profile] and [preset]: draws a new peer key and restarts the link counter, so
     * no reference outlives a start, then reports `ML1001`, and `ML1003` in `Unsecured`.
     */
    fun begin(
        profile: SecurityProfile,
        preset: Preset,
    ) {
        peerKey.value = crypto.randomBytes(PEER_KEY_BYTES)
        links.value = 0
        report(DiagnosticEvent.Started(profile, preset))
        if (profile == SecurityProfile.Unsecured) report(DiagnosticEvent.UnsecuredProfile())
    }

    /** The [PeerRef] of [id] in this start. */
    fun peer(id: NodeId): PeerRef {
        val hash = crypto.blake2b(PEER_HASH_BYTES, id.toByteArray(), peerKey.value)
        val prefix = hash.copyOf(Int.SIZE_BYTES)
        return PeerRef(prefix.fold(0) { value, byte -> (value shl Byte.SIZE_BITS) or byte.toUByte().toInt() })
    }

    /** The next [LinkRef] of this start. */
    fun link(): LinkRef = LinkRef(links.updateAndGet { it + 1 })

    /** Completes every current and later collector of [events]. */
    fun close() {
        closed.value = true
        subscribers.value.forEach { it.channel.close() }
    }

    private fun flush(key: Any) {
        val repeats = windows.getAndUpdate { it - key }.getValue(key)
        if (repeats.count > 0) {
            repeats.latest.count = repeats.count
            deliver(repeats.latest)
        }
    }

    private fun deliver(event: DiagnosticEvent) {
        log?.let { write(it, event) }
        subscribers.value.forEach { it.channel.trySend(event) }
    }

    /** Writes [event] to [log]: its code, name, count and numbers shown, every other field hidden. */
    private fun write(
        log: PlatformLog,
        event: DiagnosticEvent,
    ) {
        val (numbers, others) = event.fields.partition { it.second is Number }
        val shown = listOf(event.code, event::class.simpleName, "count=${event.count}") + numbers.map(::field)
        log.write(event.severity, shown.joinToString(" "), others.joinToString(" ", transform = ::field))
    }

    private fun field(field: Pair<String, Any>): String = "${field.first}=${field.second}"

    private class Repeats(
        val count: Int,
        val latest: DiagnosticEvent,
    )

    private class Subscriber {
        val dropped = MutableStateFlow(0)
        val channel =
            Channel<DiagnosticEvent>(BUFFER, BufferOverflow.DROP_OLDEST) { dropped.update { count -> count + 1 } }
    }

    private companion object {
        const val WINDOW_MS = 10_000L
        const val BUFFER = 256
        const val PEER_KEY_BYTES = 32
        const val PEER_HASH_BYTES = 16
    }
}
