package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Peer
import ch.trancee.meshlink.Reachability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * A running node's `unsecured` links (spec "Version selection", "Unsecured link", "Keep one link
 * per neighbor", "Close"): opens a link to each candidate as central, accepts the links peers open,
 * keeps one link per NodeId and lists every linked peer in [peers]. Each kept link writes the
 * messages [mail] has for its peer and hands it the routed frames it receives.
 *
 * Every link runs in [scope] and ends with it, sending `CLOSE` `SHUTTING_DOWN`. Not thread-safe: the
 * node calls it, and runs [scope], on its serial dispatcher.
 */
internal class Links(
    private val radio: RadioPort,
    private val clock: Clock,
    private val policy: LinkPolicy,
    private val scope: CoroutineScope,
    private val peers: MutableStateFlow<List<Peer>>,
    private val mail: Mail,
) {
    private val table = mutableMapOf<NodeId, Link>()
    private val busy = mutableMapOf<PeerHandle, Int>()

    // Peers whose link closed with DUPLICATE_LINK while their other link was still opening.
    private val kept = mutableMapOf<NodeId, Peer>()
    private var opening = 0

    /** Opens a link as central to each of [candidates] that has no link and no link opening. */
    fun connectTo(candidates: Set<PeerHandle>) {
        candidates.filter { it !in busy }.forEach { handle -> track(handle) { central(handle) } }
    }

    /** Accepts the link a peer opened in [event]; other events need nothing here. */
    fun accept(event: RadioEvent) {
        if (event is RadioEvent.Connected) track(event.link.peer) { serve(event.link, selected = null) }
    }

    private fun track(
        handle: PeerHandle,
        block: suspend () -> Unit,
    ) {
        busy[handle] = (busy[handle] ?: 0) + 1
        scope.launch {
            try {
                block()
            } finally {
                busy.decrement(handle)
            }
        }
    }

    private suspend fun central(handle: PeerHandle) {
        val link =
            when (val connected = radio.connect(handle)) {
                is RadioResult.Ok -> connected.value
                is RadioResult.Failed -> return
            }
        var version: Int? = null
        try {
            version =
                (link.readCapability() as? RadioResult.Ok)
                    ?.let { CapabilityRecord.decode(it.value) as? Decoded.Valid }
                    ?.let { policy.selectVersion(it.value) }
        } finally {
            if (version == null) link.disconnect()
        }
        version?.let { serve(link, it) }
    }

    /** Runs [link] until it closes: as central once it chose [selected], as peripheral if `null`. */
    private suspend fun serve(
        link: RadioLink,
        selected: Int?,
    ) = coroutineScope {
        val conn = Link(link, selected)
        opening++
        val timer =
            launch {
                delay(HANDSHAKE_TIMEOUT_MILLIS)
                conn.close(null)
            }
        val pump = launch { pump(conn) }
        try {
            selected?.let { conn.send(policy.hello(it)) }
            val reader = RecordReader()
            link.incoming.takeWhile { !conn.closing }.collect { chunk ->
                when (val records = reader.read(chunk)) {
                    is Decoded.Invalid -> conn.close(null)
                    is Decoded.Valid -> records.value.forEach { if (!conn.closing) receive(conn, it) }
                }
                if (conn.peer != null) timer.cancel()
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { conn.close(CloseReason.SHUTTING_DOWN) }
            throw e
        } finally {
            timer.cancel()
            pump.cancel()
            release(conn)
        }
    }

    private suspend fun receive(
        conn: Link,
        body: ByteArray,
    ) {
        conn.peer?.let { conn.lastSeen = clock.now() }
        if (conn.peer != null && (body[0].toInt() and BYTE_MASK) in ROUTED_TYPES) {
            mail.received(body)
        } else {
            when (val decoded = LinkFrame.decode(body)) {
                is Decoded.Invalid -> {
                    if (decoded.error != DecodeError.UNKNOWN_TYPE) conn.close(CloseReason.PROTOCOL_ERROR)
                }

                is Decoded.Valid -> {
                    frame(conn, decoded.value)
                }
            }
        }
        if (table[conn.peer] === conn) publish()
    }

    /** Writes [mail]'s messages for the peer once [conn] is its kept link, until cancelled. */
    private suspend fun pump(conn: Link) {
        val id = conn.chosen.await()
        while (true) {
            // A refused write means the link is closing or broken; serve cancels the pump when it ends.
            if (!conn.deliver(mail.next(id))) awaitCancellation()
        }
    }

    private suspend fun frame(
        conn: Link,
        frame: LinkFrame,
    ) {
        when (frame) {
            is LinkFrame.Close -> {
                conn.received = frame.reason
                conn.close(null)
            }

            is LinkFrame.PlainHello -> {
                val reason =
                    if (conn.peer == null) policy.checkHello(frame, conn.selected) else CloseReason.PROTOCOL_ERROR
                if (reason != null) return conn.close(reason)
                if (conn.selected == null) conn.send(policy.hello(frame.version))
                open(conn, NodeId(frame.nodeId))
            }
        }
    }

    private suspend fun open(
        conn: Link,
        id: NodeId,
    ) {
        conn.peer = id
        conn.lastSeen = clock.now()
        opening--
        kept.remove(id)
        mail.linked(id)
        val other = table[id]
        // Both ends keep the link whose central has the lower NodeId; same direction keeps the first.
        val ownCentral = policy.keepsOwnCentral(id.toByteArray())
        val keepNew = other == null || (other.central != conn.central && conn.central == ownCentral)
        if (keepNew) {
            table[id] = conn
            conn.chosen.complete(id)
        }
        // The loser's CLOSE may wait for a message write; closing it apart keeps this link reading.
        val loser = if (keepNew) other else conn
        loser?.let { scope.launch(start = CoroutineStart.UNDISPATCHED) { it.close(CloseReason.DUPLICATE_LINK) } }
    }

    private fun release(conn: Link) {
        val id = conn.peer
        if (id == null) opening--
        if (id != null && table[id] === conn) {
            table.remove(id)
            mail.unlinked(id)
            if (conn.received == CloseReason.DUPLICATE_LINK) kept[id] = conn.toPeer(id)
        }
        publish()
    }

    private fun publish() {
        if (opening == 0) kept.clear()
        val listed = table.map { (id, conn) -> conn.toPeer(id) } + kept.values
        peers.value = listed.sortedBy { it.nodeId.toString() }
    }

    /** One link and what this node knows about it. */
    private class Link(
        val link: RadioLink,
        /** The version this node chose as central, or `null` as peripheral. */
        val selected: Int?,
    ) {
        val central: Boolean get() = selected != null
        var peer: NodeId? = null
        var lastSeen: Instant = Instant.fromEpochMilliseconds(0)
        var received: CloseReason? = null
        var closing = false
            private set

        /** Completes with the peer's NodeId once this is the peer's kept link. */
        val chosen = CompletableDeferred<NodeId>()

        // Keeps each record whole on the bearer: a message and a CLOSE never interleave.
        private val writing = Mutex()

        suspend fun send(frame: LinkFrame) {
            writing.withLock { link.write(LinkRecord.encode(frame.encode())) }
        }

        /**
         * Writes [letter]'s record unless the link is closing, and settles it. Not cancellable once
         * started, so a record is never cut short and a written one is always settled as sent.
         */
        suspend fun deliver(letter: Letter): Boolean {
            var sent = false
            try {
                withContext(NonCancellable) {
                    writing.withLock { if (!closing) sent = link.write(letter.record) is RadioResult.Ok }
                }
            } finally {
                letter.settle(sent)
            }
            return sent
        }

        /** Sends `CLOSE` with [reason] unless it is `null`, then disconnects; once only. */
        suspend fun close(reason: CloseReason?) {
            if (closing) return
            closing = true
            try {
                reason?.let { send(LinkFrame.Close(it)) }
            } finally {
                link.disconnect()
            }
        }

        fun toPeer(id: NodeId): Peer = Peer(id, Reachability.Direct, secured = false, lastSeen)
    }

    companion object {
        /** How long a link may take to exchange hellos before the node closes the bearer (spec `handshake-timeout`). */
        const val HANDSHAKE_TIMEOUT_MILLIS: Long = 10_000

        /** Routed frame types: unicast `0x40`–`0x7F` and flood `0x80`–`0xBF` (spec "Routing"). */
        private val ROUTED_TYPES: IntRange = 0x40..0xBF
        private const val BYTE_MASK = 0xFF
    }
}

private fun Clock.now(): Instant = Instant.fromEpochMilliseconds(wallClockMillis())

/** Counts one link to [handle] fewer, forgetting the handle at zero. */
private fun MutableMap<PeerHandle, Int>.decrement(handle: PeerHandle) {
    val left = getValue(handle) - 1
    if (left == 0) remove(handle) else this[handle] = left
}
