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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * A running node's links (spec "Version selection", "Unsecured link", "Link handshake", "Keep one
 * link per neighbor", "Close"): opens a link to each candidate as central, accepts the links peers
 * open, keeps one link per NodeId and lists every linked peer in [peers]. A link opens with
 * `PLAIN_HELLO`s in `unsecured`, or with a Noise handshake and encrypted records once
 * [LinkAdmission.handshakes] is set. Each kept link writes the messages [mail] has for its peer and
 * hands it the routed frames it receives.
 *
 * Every link runs in [scope] and ends with it, sending `CLOSE` `SHUTTING_DOWN`. Not thread-safe: the
 * node calls it, and runs [scope], on its serial dispatcher.
 */
internal class Links(
    private val radio: RadioPort,
    private val clock: Clock,
    admission: LinkAdmission,
    private val scope: CoroutineScope,
    private val peers: MutableStateFlow<List<Peer>>,
    private val mail: Mail,
) {
    private val policy = admission.policy
    private val handshakes = admission.handshakes

    private val mutableRoles = MutableStateFlow<Map<NodeId, LinkRole>>(emptyMap())

    /** This node's role on each kept link; for the lab apps. */
    val roles: StateFlow<Map<NodeId, LinkRole>> = mutableRoles.asStateFlow()

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
        if (event is RadioEvent.Connected) track(event.link.peer) { serve(event.link, null, policy.versions) }
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
        // The selected version and the record's versions, which a secured handshake binds.
        var selection: Pair<Int, List<Int>>? = null
        try {
            selection =
                (link.readCapability() as? RadioResult.Ok)
                    ?.let { CapabilityRecord.decode(it.value) as? Decoded.Valid }
                    ?.let { record -> policy.selectVersion(record.value)?.let { it to record.value.versions } }
        } finally {
            if (selection == null) link.disconnect()
        }
        selection?.let { (version, versions) -> serve(link, version, versions) }
    }

    /**
     * Runs [link] until it closes: as central once it chose [selected] from the peripheral's
     * capability record [versions], as peripheral if [selected] is `null`.
     */
    private suspend fun serve(
        link: RadioLink,
        selected: Int?,
        versions: List<Int>,
    ) = coroutineScope {
        val conn = Link(link, selected, handshakes?.link(link.peer, versions))
        opening++
        val timer =
            launch {
                delay(HANDSHAKE_TIMEOUT_MILLIS)
                conn.pending?.timedOut()
                conn.close(null)
            }
        // Writes mail's messages for the peer once this is its kept link. A refused write means the
        // link is closing or broken; serve cancels the pump when it ends.
        val pump =
            launch {
                val id = conn.chosen.await()
                while (conn.deliver(mail.next(id))) continue
                awaitCancellation()
            }
        try {
            when {
                selected == null -> Unit
                conn.secure == null -> conn.send(policy.hello(selected))
                else -> handshake(conn, conn.secure.start(selected))
            }
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

    /** Handles one record [body]: a fatal one, one that fails to decrypt, closes the bearer. */
    private suspend fun receive(
        conn: Link,
        body: ByteArray,
    ) {
        val plain = conn.unseal(body)
        conn.peer?.let { conn.lastSeen = clock.now() }
        val pending = conn.pending
        when {
            plain == null -> {
                conn.close(null)
            }

            conn.peer != null && (plain[0].toInt() and BYTE_MASK) in ROUTED_TYPES -> {
                mail.received(plain)
            }

            else -> {
                val decoded = LinkFrame.decode(plain)
                when {
                    decoded is Decoded.Valid -> {
                        frame(conn, decoded.value)
                    }

                    // Before its handshake completes, a secured link accepts only HANDSHAKE and CLOSE.
                    pending != null -> {
                        handshake(conn, pending.receive(null))
                    }

                    (decoded as Decoded.Invalid).error != DecodeError.UNKNOWN_TYPE -> {
                        conn.close(CloseReason.PROTOCOL_ERROR)
                    }
                }
            }
        }
        if (table[conn.peer] === conn) publish()
    }

    private suspend fun frame(
        conn: Link,
        frame: LinkFrame,
    ) {
        val pending = conn.pending
        when {
            frame is LinkFrame.Close -> {
                conn.received = frame.reason
                conn.close(null)
            }

            pending != null -> {
                handshake(conn, pending.receive(frame))
            }

            frame is LinkFrame.PlainHello && conn.peer == null -> {
                val reason = policy.checkHello(frame, conn.selected)
                if (reason != null) return conn.close(reason)
                if (conn.selected == null) conn.send(policy.hello(frame.version))
                open(conn, NodeId(frame.nodeId))
            }

            else -> {
                conn.close(CloseReason.PROTOCOL_ERROR)
            }
        }
    }

    /** Does what a secured handshake [step] on [conn] asks. */
    private suspend fun handshake(
        conn: Link,
        step: HandshakeStep,
    ) {
        when (step) {
            is HandshakeStep.Send -> {
                conn.send(step.frame)
            }

            is HandshakeStep.Complete -> {
                step.reply?.let { conn.send(it) }
                conn.transport = step.transport
                step.peer?.let { open(conn, it) } ?: conn.close(CloseReason.HANDSHAKE_FAILED)
            }

            HandshakeStep.Failed -> {
                conn.close(CloseReason.HANDSHAKE_FAILED)
            }

            HandshakeStep.Dropped -> {
                conn.close(null)
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
        conn.secure?.end()
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
        mutableRoles.value =
            table.mapValues { (_, conn) -> if (conn.central) LinkRole.CENTRAL else LinkRole.PERIPHERAL }
        peers.value = listed.sortedBy { it.nodeId.toString() }
    }

    /** One link and what this node knows about it. */
    private class Link(
        val link: RadioLink,
        /** The version this node chose as central, or `null` as peripheral. */
        val selected: Int?,
        /** The link's handshake in a secured profile, or `null` in `unsecured`. */
        val secure: LinkHandshake?,
    ) {
        val central: Boolean get() = selected != null
        var peer: NodeId? = null

        /** The handshake while it runs: until the peer is known, once the link is secured. */
        val pending: LinkHandshake? get() = secure?.takeIf { peer == null }

        /** Encrypts and decrypts every record once the handshake completes. */
        var transport: NoiseTransport? = null
        var lastSeen: Instant = Instant.fromEpochMilliseconds(0)
        var received: CloseReason? = null
        var closing = false
            private set

        /** Completes with the peer's NodeId once this is the peer's kept link. */
        val chosen = CompletableDeferred<NodeId>()

        // Keeps each record whole on the bearer: a message and a CLOSE never interleave.
        private val writing = Mutex()

        suspend fun send(frame: LinkFrame) {
            writing.withLock { link.write(seal(frame.encode())) }
        }

        /**
         * [frame] as a record, encrypted once secured. Only the writer calls it, holding [writing],
         * so nonces follow the record order.
         */
        private fun seal(frame: ByteArray): ByteArray {
            // A sending cipher state fails only after 2^64 - 1 records.
            val cipher = transport
            val body = if (cipher == null) frame else (cipher.encrypt(frame) as NoiseResult.Ok).value
            return LinkRecord.encode(body)
        }

        /** The frame in record [body], or `null` if it fails to decrypt. */
        fun unseal(body: ByteArray): ByteArray? {
            val transport = transport ?: return body
            return (transport.decrypt(body) as? NoiseResult.Ok)?.value
        }

        /**
         * Writes [letter]'s record unless the link is closing, and settles it. Not cancellable once
         * started, so a record is never cut short and a written one is always settled as sent.
         */
        suspend fun deliver(letter: Letter): Boolean {
            var sent = false
            try {
                withContext(NonCancellable) {
                    writing.withLock { if (!closing) sent = link.write(seal(letter.frame)) is RadioResult.Ok }
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

        fun toPeer(id: NodeId): Peer = Peer(id, Reachability.Direct, secured = secure != null, lastSeen)
    }

    companion object {
        /** How long a link may take to exchange hellos before the node closes the bearer (spec `handshake-timeout`). */
        const val HANDSHAKE_TIMEOUT_MILLIS: Long = 10_000

        /** Routed frame types: unicast `0x40`–`0x7F` and flood `0x80`–`0xBF` (spec "Routing"). */
        private val ROUTED_TYPES: IntRange = 0x40..0xBF
        private const val BYTE_MASK = 0xFF
    }
}

/** How a session's links open: by [policy]'s hellos, or by [handshakes] in a secured profile. */
internal class LinkAdmission(
    val policy: LinkPolicy,
    val handshakes: Handshakes?,
)

private fun Clock.now(): Instant = Instant.fromEpochMilliseconds(wallClockMillis())

/** Counts one link to [handle] fewer, forgetting the handle at zero. */
private fun MutableMap<PeerHandle, Int>.decrement(handle: PeerHandle) {
    val left = getValue(handle) - 1
    if (left == 0) remove(handle) else this[handle] = left
}
