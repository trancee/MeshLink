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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * A running node's links (spec "Version selection", "Unsecured link", "Link handshake", "Keep one
 * link per neighbor", "Close"): opens a link to each candidate as central, accepts the links peers
 * open, keeps one link per NodeId and lists every linked peer in [peers]. A link opens with
 * `PLAIN_HELLO`s in `unsecured`, or with a Noise handshake and encrypted records once
 * [LinkAdmission.handshakes] is set. Each kept link writes the messages [mail] has for its peer and
 * hands it the routed frames it receives. A frame larger than one record travels as `FRAGMENT`s;
 * every link reassembles within the node's shared large-stream [budget] (spec "Fragments"). With
 * [LinkAdmission.coc], each kept link this node opens to a peer that offers a PSM moves to L2CAP CoC
 * (spec "Bearer switch").
 *
 * Every link runs in [scope] and ends with it, sending `CLOSE` `SHUTTING_DOWN`. Not thread-safe: the
 * node calls it, and runs [scope], on its serial dispatcher.
 */
internal class Links(
    radio: RadioPort,
    private val clock: Clock,
    admission: LinkAdmission,
    private val scope: CoroutineScope,
    private val peers: MutableStateFlow<List<Peer>>,
    private val mail: Mail,
) {
    private val policy = admission.policy
    private val handshakes = admission.handshakes
    private val keys = handshakes?.keys
    private val report = admission.report
    private val limits = LinkLimits(radio, admission.report)
    private val coc = admission.coc

    private val mutableRoles = MutableStateFlow<Map<NodeId, LinkRole>>(emptyMap())

    /** This node's role on each kept link; for the lab apps. */
    val roles: StateFlow<Map<NodeId, LinkRole>> = mutableRoles.asStateFlow()

    private val budget = ReassemblyBudget()
    private val inbound = Inbound()
    private val channels = Channels()
    private val table = mutableMapOf<NodeId, Link>()
    private val busy = mutableMapOf<PeerHandle, Int>()

    // Peers whose link closed with DUPLICATE_LINK while their other link was still opening.
    private val kept = mutableMapOf<NodeId, Peer>()
    private var opening = 0

    /**
     * Opens a link as central to each of [candidates] that has no link and no link opening, unless
     * it refused this node for its link cap (spec "Connect retries and link caps").
     */
    fun connectTo(candidates: Set<PeerHandle>) {
        limits.dialable(candidates).filter { it !in busy }.forEach { handle -> track(handle) { central(handle) } }
    }

    /**
     * Accepts the link a peer opened in [event] unless the radio holds its peripheral link cap, and
     * the L2CAP CoC channel a peer opened to switch to; reconnects as central after a transient loss of
     * a central link. Other events need nothing.
     */
    fun accept(event: RadioEvent) {
        when (event) {
            is RadioEvent.Connected -> {
                val link = event.link
                if (!limits.admit()) {
                    scope.launch { limits.refuse(link) }
                } else {
                    track(link.peer) {
                        try {
                            serve(link, null, policy.versions, null)
                        } finally {
                            limits.released()
                        }
                    }
                }
            }

            is RadioEvent.Disconnected -> {
                limits.lost(event)?.let { handle -> track(handle) { central(handle) } }
            }

            is RadioEvent.ChannelOpened -> {
                channels.accept(event.channel)
            }

            else -> {}
        }
    }

    /** Sends this node's [next] resolving key on every kept link (spec "Resolving key update"). */
    fun announce(next: ResolvingKey) {
        table.values.toList().forEach { post(it, LinkFrame.ResolvingKeyUpdate(next)) }
    }

    /**
     * Sends [frame] on [conn] apart from the caller: a peripheral's frame waits for credits, which
     * its link's reader must stay free to read (spec "Flow control").
     */
    private fun post(
        conn: Link,
        frame: LinkFrame,
    ) {
        scope.launch { conn.send(frame) }
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
        val link = limits.connect(handle) ?: return
        // The selected version and the record, whose versions a secured handshake binds.
        var selection: Pair<Int, CapabilityRecord>? = null
        try {
            selection =
                (link.readCapability() as? RadioResult.Ok)
                    ?.let { CapabilityRecord.decode(it.value) as? Decoded.Valid }
                    ?.let { record -> policy.selectVersion(record.value)?.let { it to record.value } }
        } finally {
            if (selection == null) link.disconnect()
        }
        val conn =
            selection?.let { (version, record) ->
                val offer = record.l2capPsm?.takeIf { coc }?.let { ChannelOffer(link, it) }
                serve(link, version, record.versions, offer)
            }
        if (conn != null && conn.peer == null && conn.received == CloseReason.RESOURCE_LIMIT) limits.refusedBy(handle)
        // A kept link whose channel was lost links again, like a transient loss (spec "Bearer switch").
        if (conn?.lost == true) track(handle) { central(handle) }
    }

    /**
     * Runs [link] until it closes: as central once it chose [selected] from the peripheral's
     * capability record [versions], and switches to the channel the record may [offer]; as
     * peripheral if [selected] is `null`. Returns the link.
     */
    private suspend fun serve(
        link: RadioLink,
        selected: Int?,
        versions: List<Int>,
        offer: ChannelOffer?,
    ) = coroutineScope {
        val secure = handshakes?.link(link.peer, versions)
        val conn = Link(link, selected, secure, Reassembly(budget, clock), clock, offer)
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
        // Rekeys the sending direction on time once the link is secured (spec "Rekey").
        val rekeys = launch { conn.rekeyOnTime() }
        try {
            when {
                selected == null -> Unit
                conn.secure == null -> conn.send(policy.hello(selected))
                else -> handshake(conn, conn.secure.start(selected))
            }
            val reader = RecordReader()
            // Reads GATT until the link closes or the peer's BEARER_SWITCH moves it to CoC.
            link.incoming.firstOrNull { chunk ->
                // An open link's central counts each notification and grants credits back until it
                // switches (spec "Flow control").
                val counted = conn.peer != null && conn.bearer.channel == null
                inbound.read(conn, reader, chunk, onChannel = false)
                conn.credits.notified(counted)?.let { conn.send(LinkFrame.Credit(it)) }
                if (conn.peer != null) timer.cancel()
                !conn.reading || conn.bearer.moved
            }
            val channel = conn.bearer.incoming
            if (conn.reading && channel != null) channels.read(conn, channel)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { conn.close(CloseReason.SHUTTING_DOWN) }
            throw e
        } finally {
            timer.cancel()
            pump.cancel()
            rekeys.cancel()
            release(conn)
        }
        conn
    }

    /** Reads a link's records: whole frames, reassembled streams and what each asks of [Links]. */
    private inner class Inbound {
        /**
         * Handles the records in [chunk], read from the channel if [onChannel], else from GATT;
         * records on GATT after the peer's `BEARER_SWITCH` are dropped. Bytes [reader] can't frame
         * close the bearer.
         */
        suspend fun read(
            conn: Link,
            reader: RecordReader,
            chunk: ByteArray,
            onChannel: Boolean,
        ) {
            when (val records = reader.read(chunk)) {
                is Decoded.Invalid -> {
                    conn.close(null)
                }

                is Decoded.Valid -> {
                    val bearer = conn.bearer
                    records.value.forEach { if (conn.reading && bearer.moved == onChannel) receive(conn, it) }
                }
            }
        }

        /**
         * Handles one record [body]: a fatal one, or one that fails to decrypt, closes the bearer; a
         * peer overdue its rekey is closed with `PROTOCOL_ERROR`.
         */
        suspend fun receive(
            conn: Link,
            body: ByteArray,
        ) {
            val opened = conn.cipher.let { if (it == null) LinkCipher.Opened.Frame(body) else it.open(body) }
            conn.peer?.let { conn.lastSeen = clock.now() }
            when (opened) {
                is LinkCipher.Opened.Frame -> dispatch(conn, opened.bytes)
                LinkCipher.Opened.Failed -> conn.close(null)
                LinkCipher.Opened.Overdue -> conn.close(CloseReason.PROTOCOL_ERROR)
            }
            if (table[conn.peer] === conn) publish()
        }

        /** Handles one whole frame: a record's, or a reassembled stream's. */
        private suspend fun dispatch(
            conn: Link,
            plain: ByteArray,
        ) {
            val decoded = LinkFrame.decode(plain)
            val pending = conn.pending
            when {
                decoded is Decoded.Valid -> {
                    frame(conn, decoded.value)
                }

                // Before its handshake completes, a secured link accepts only HANDSHAKE, its fragments and CLOSE.
                pending != null -> {
                    handshake(conn, pending.receive(null))
                }

                (decoded as Decoded.Invalid).error != DecodeError.UNKNOWN_TYPE -> {
                    conn.close(CloseReason.PROTOCOL_ERROR)
                }

                // An unknown link-local frame is dropped; a routed one is mail's once the link is open.
                (plain[0].toInt() and BYTE_MASK) < ROUTED_FIRST -> {
                    report(LinkDiagnostic.UnknownLinkFrameDropped(plain[0].toInt() and BYTE_MASK))
                }

                conn.peer != null -> {
                    mail.received(plain)
                }
            }
        }

        /**
         * Adds [fragment] to its stream: a whole frame is dispatched, a refused stream answered with
         * `BUSY`. A fatal error fails a running handshake, which reassembles at most one `HANDSHAKE`
         * frame's bytes, and otherwise closes the link with `PROTOCOL_ERROR`.
         */
        private suspend fun reassemble(
            conn: Link,
            fragment: LinkFrame.Fragment,
        ) {
            val pending = conn.pending
            val limit = if (pending == null) Reassembly.FRAME_MAX else LinkFrame.Handshake.FRAME_MAX
            when (val result = conn.reassembly.accept(fragment, limit)) {
                Reassembled.Waiting -> {}

                is Reassembled.Complete -> {
                    dispatch(conn, result.frame)
                }

                is Reassembled.Refused -> {
                    post(conn, result.busy)
                }

                Reassembled.Fatal -> {
                    if (pending == null) {
                        conn.close(CloseReason.PROTOCOL_ERROR)
                    } else {
                        handshake(conn, pending.receive(null))
                    }
                }
            }
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

                frame is LinkFrame.Fragment -> {
                    reassemble(conn, frame)
                }

                pending != null -> {
                    handshake(conn, pending.receive(frame))
                }

                frame is LinkFrame.Busy -> {
                    conn.refused(frame.streamId)
                }

                // Only a central grants credits, and never above the window (spec "Flow control").
                frame is LinkFrame.Credit -> {
                    if (!conn.credits.grant(frame.credits)) conn.close(CloseReason.PROTOCOL_ERROR)
                }

                // Only a gated mesh's secured links learn keys; any other drops the frame
                // (spec "Resolving key update").
                frame is LinkFrame.ResolvingKeyUpdate -> {
                    conn.peer?.let { keys?.learn(it, frame.next, clock.wallClockMillis()) }
                }

                frame is LinkFrame.PlainHello && conn.peer == null -> {
                    val reason = policy.checkHello(frame, conn.selected)
                    if (reason != null) return conn.close(reason)
                    if (conn.selected == null) conn.send(policy.hello(frame.version))
                    open(conn, NodeId(frame.nodeId))
                }

                else -> {
                    other(conn, frame)
                }
            }
        }

        /**
         * A frame the rules above don't cover. `BEARER_SWITCH` on an open link moves it (spec "Bearer
         * switch"). A `REKEY` was already applied by the cipher, and an `Unsecured` link drops it as
         * an unknown link-local frame (D-48); anything else closes the link with `PROTOCOL_ERROR`.
         */
        private suspend fun other(
            conn: Link,
            frame: LinkFrame,
        ) {
            when {
                frame == LinkFrame.BearerSwitch && conn.peer != null -> channels.switched(conn)
                frame != LinkFrame.Rekey -> conn.close(CloseReason.PROTOCOL_ERROR)
                conn.cipher == null -> report(LinkDiagnostic.UnknownLinkFrameDropped(frame.encode()[0].toInt()))
            }
        }
    }

    /**
     * Moves links to L2CAP CoC (spec "Bearer switch", D-49): the central opens a channel to the PSM
     * the peer offered and writes `BEARER_SWITCH`; the peripheral answers with its own on the
     * channel the central opened. Each end then reads the channel once the peer's switch arrives.
     */
    private inner class Channels {
        // The channels peers opened that no link has claimed yet, by peer.
        private val opened = MutableStateFlow<Map<PeerHandle, RadioStream>>(emptyMap())

        /** Keeps [channel] for its peer's link to claim; disconnects it if no link or another channel waits. */
        fun accept(channel: RadioStream) {
            val handle = channel.peer
            if (handle !in busy || handle in opened.value) {
                channel.disconnect()
            } else {
                opened.value += handle to channel
            }
        }

        /** Opens the channel [offer] names for central [conn] and switches to it, or reports why it stayed on GATT. */
        suspend fun upgrade(
            conn: Link,
            offer: ChannelOffer,
        ) {
            when (val result = offer.link.openChannel(offer.psm)) {
                is RadioResult.Ok -> {
                    switchTo(conn, result.value)
                }

                // A radio without L2CAP CoC was never going to switch: nothing fell back.
                is RadioResult.Failed -> {
                    if (result.reason != RadioFailure.UNSUPPORTED) report(LinkDiagnostic.BearerFallback(result.reason))
                }
            }
        }

        /**
         * The peer's `BEARER_SWITCH` arrived on [conn]. A central reads its channel from now on. A
         * peripheral claims the channel the central opened, within `coc-accept-timeout`, and answers
         * with its own switch. A second switch, a switch from a peripheral first, or no channel
         * close the link with `PROTOCOL_ERROR`.
         */
        suspend fun switched(conn: Link) {
            val bearer = conn.bearer
            val ready =
                when {
                    bearer.moved -> false
                    conn.central -> bearer.channel != null
                    else -> claim(conn)
                }
            if (ready) bearer.moved = true else conn.close(CloseReason.PROTOCOL_ERROR)
        }

        /**
         * Reads [conn]'s channel until the link closes. A channel that ends first closes the link,
         * which counts as [Link.lost] if it was the peer's kept link.
         */
        suspend fun read(
            conn: Link,
            channel: RadioStream,
        ) {
            val reader = RecordReader()
            channel
                .incoming
                .takeWhile { conn.reading }
                .collect { inbound.read(conn, reader, it, true) }
            if (conn.reading) {
                conn.lost = table[conn.peer] === conn
                conn.close(null)
            }
        }

        /** Disconnects any channel peripheral [conn]'s peer opened that it never claimed. */
        fun release(conn: Link) {
            val handle = conn.link.peer
            if (conn.central) return
            opened.value[handle]?.disconnect()
            opened.value -= handle
        }

        // A peripheral's switch: the central sent its last credit-counted notification, so this
        // end's own notifications until its switch go uncredited (D-49).
        private suspend fun claim(conn: Link): Boolean {
            conn.credits.waive()
            val handle = conn.link.peer
            val channel = withTimeoutOrNull(COC_ACCEPT_TIMEOUT_MILLIS) { opened.first { handle in it }[handle] }
            opened.value -= handle
            return channel != null && switchTo(conn, channel)
        }

        /** Writes `BEARER_SWITCH` on [conn] and moves its writes to [channel]; disconnects it if the link closed. */
        private suspend fun switchTo(
            conn: Link,
            channel: RadioStream,
        ): Boolean {
            conn.bearer.offer(channel)
            conn.send(LinkFrame.BearerSwitch)
            val switched = conn.bearer.channel === channel
            if (!switched) channel.disconnect()
            return switched
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
                conn.secured(LinkCipher(step.transport, clock))
                val peer = step.peer ?: return conn.close(CloseReason.HANDSHAKE_FAILED)
                open(conn, peer)
                keys?.next?.let { post(conn, LinkFrame.ResolvingKeyUpdate(it)) }
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
        // The loser drains until the peer's CLOSE, at most DRAIN_TIMEOUT_MILLIS (spec "Duplicate links").
        val loser = if (keepNew) other else conn
        conn.offer?.takeIf { keepNew }?.let { scope.launch { channels.upgrade(conn, it) } }
        loser?.let {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                it.close(CloseReason.DUPLICATE_LINK)
                delay(DRAIN_TIMEOUT_MILLIS)
                it.close(null)
            }
        }
        // A closing link sends no grant, so only a kept central link grants the window.
        if (conn.central) conn.send(LinkFrame.Credit(Credits.WINDOW))
    }

    private fun release(conn: Link) {
        conn.credits.stop()
        channels.release(conn)
        conn.secure?.end()
        conn.reassembly.clear()
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
        val reassembly: Reassembly,
        private val clock: Clock,
        /** The channel a central's peer offers, or `null`. */
        val offer: ChannelOffer?,
    ) {
        val central: Boolean get() = selected != null
        var peer: NodeId? = null

        /** The handshake while it runs: until the peer is known, once the link is secured. */
        val pending: LinkHandshake? get() = secure?.takeIf { peer == null }

        /** Encrypts and decrypts every record once the handshake completes. */
        var cipher: LinkCipher? = null
            private set
        private val keyed = CompletableDeferred<LinkCipher>()
        var lastSeen: Instant = Instant.fromEpochMilliseconds(0)
        var received: CloseReason? = null
        private var closing = false

        // Closed as a duplicate: still reading the peer's frames until its CLOSE or the timeout.
        private var draining = false

        /** Whether the peer's frames are still read: until this link closes, or ends its drain. */
        val reading: Boolean get() = !closing || draining

        /** Completes with the peer's NodeId once this is the peer's kept link. */
        val chosen = CompletableDeferred<NodeId>()

        /** The link's GATT notification credits (spec "Flow control"). */
        val credits = Credits(peripheral = !central)

        /** Where the link's records travel: GATT, then L2CAP CoC once switched (spec "Bearer switch"). */
        val bearer = Bearer(link, credits)

        /** Whether the peer's kept link ended as its channel did, without a `CLOSE`. */
        var lost = false

        // An open link's peripheral sends each frame but CLOSE as credited notifications.
        private val crediting: Boolean get() = !central && peer != null

        // Keeps each record whole on the bearer: a message's records and a CLOSE never interleave.
        private val writing = Mutex()

        // This link's recent outgoing streams by stream_id, for a BUSY that answers one.
        private val streams = mutableMapOf<Int, Outgoing>()
        private var nextStream = 0

        /**
         * Writes control [frame] as one record, or as `FRAGMENT`s of a new stream if it is longer
         * than a record body. Only `PostQuantum` handshake messages are, and they travel in
         * cleartext (message 3 is written before the link is secured), so a fragment carries the
         * whole cleartext body.
         */
        suspend fun send(frame: LinkFrame) {
            val bytes = frame.encode()
            val credited = crediting && frame !is LinkFrame.Close
            writing.withLock {
                // Nothing follows a CLOSE, not even a reply to a frame read while draining, and a
                // CLOSE never follows a record cut short.
                if (closing && (frame !is LinkFrame.Close || credits.torn)) return@withLock
                if (bytes.size <= LinkRecord.BODY_MAX) {
                    bearer.write(seal(bytes), credited)
                } else {
                    val dataMax = LinkRecord.BODY_MAX - LinkFrame.Fragment.HEADER
                    LinkFrame.Fragment.split(stream(), bytes, dataMax).forEach {
                        bearer.write(seal(it.encode()), credited)
                    }
                }
                if (frame == LinkFrame.BearerSwitch) bearer.switched()
            }
        }

        /** Encrypts every later record with [cipher]: the handshake completed. */
        fun secured(cipher: LinkCipher) {
            this.cipher = cipher
            keyed.complete(cipher)
        }

        /**
         * Once secured, sends `REKEY` each time the sending key is due until the link closes. A
         * `REKEY` the cipher inserts at its record limit moves the due time on. A started `REKEY`
         * is written whole.
         */
        suspend fun rekeyOnTime() {
            val cipher = keyed.await()
            while (!closing) {
                val wait = cipher.rekeyDueMillis - clock.monotonicMillis()
                if (wait > 0) delay(wait) else withContext(NonCancellable) { send(LinkFrame.Rekey) }
            }
        }

        /** The next stream id; ids are taken in turn, so open streams' ids stay unique. */
        private fun stream(): Int {
            val id = nextStream
            nextStream = (id + 1) and BYTE_MASK
            return id
        }

        /**
         * [frame] as a record, encrypted once secured, and then after a `REKEY` record when the
         * sending key has one record left. Only the writer calls it, holding [writing], so nonces
         * follow the record order.
         */
        private fun seal(frame: ByteArray): ByteArray {
            val secured = cipher ?: return LinkRecord.encode(frame)
            return secured.seal(frame)
        }

        /**
         * Writes [letter]'s frame unless the link is closing, and settles it: sent once every record
         * is written, not sent if a write fails or the peer answers `BUSY`. Not cancellable once
         * started, so a record is never cut short and a written frame is always settled. Returns
         * whether the link can take the next frame.
         */
        suspend fun deliver(letter: Letter): Boolean {
            var written = Written.FAILED
            var sent = false
            try {
                withContext(NonCancellable) {
                    written = write(letter)
                    sent = written == Written.SENT
                }
            } finally {
                letter.settle(sent)
            }
            return written != Written.FAILED
        }

        /**
         * One record if the frame fits, else `FRAGMENT`s of a new stream. Only the link's message
         * pump calls it, so one stream at a time is in flight and stream ids taken in turn are
         * unique among open streams. Each record takes [writing] apart, so a `CLOSE` can pass.
         * Messages travel only on `unsecured` links until secured messaging, which sizes fragments
         * for sealed records, so a record body carries the whole [LinkRecord.BODY_MAX].
         */
        private suspend fun write(letter: Letter): Written {
            val frame = letter.frame
            val plainMax = LinkRecord.BODY_MAX
            if (frame.size <= plainMax) return record(frame)
            val now = clock.monotonicMillis()
            streams.values.removeAll { now - it.startedMillis >= Reassembly.IDLE_TIMEOUT_MILLIS }
            val id = stream()
            val stream = Outgoing(letter.settle, now)
            streams[id] = stream
            var written = Written.SENT
            for (piece in LinkFrame.Fragment.split(id, frame, plainMax - LinkFrame.Fragment.HEADER)) {
                written = if (stream.refused) Written.REFUSED else record(piece.encode())
                if (written != Written.SENT) break
            }
            stream.writing = false
            if (written != Written.SENT) streams -= id
            return written
        }

        private suspend fun record(frame: ByteArray): Written =
            writing.withLock {
                val written = !closing && bearer.write(seal(frame), crediting) is RadioResult.Ok
                if (written) Written.SENT else Written.FAILED
            }

        /**
         * The peer refused stream [id] with `BUSY`: one still being written stops; one already
         * written is settled again as not sent, so the outbox retries its message.
         */
        fun refused(id: Int) {
            val stream = streams[id] ?: return
            if (stream.writing) {
                stream.refused = true
            } else {
                streams -= id
                stream.settle(false)
            }
        }

        /**
         * Sends `CLOSE` with [reason] unless it is `null`, then disconnects; once only. A
         * `DUPLICATE_LINK` close drains instead: it disconnects at the next close, which the peer's
         * `CLOSE`, a fatal frame or the drain timeout makes.
         */
        suspend fun close(reason: CloseReason?) {
            if (closing) {
                if (draining) {
                    draining = false
                    bearer.disconnect()
                }
                return
            }
            closing = true
            credits.stop()
            if (reason == CloseReason.DUPLICATE_LINK) {
                draining = true
                send(LinkFrame.Close(reason))
                return
            }
            try {
                reason?.let { send(LinkFrame.Close(it)) }
            } finally {
                bearer.disconnect()
            }
        }

        fun toPeer(id: NodeId): Peer = Peer(id, Reachability.Direct, secured = secure != null, lastSeen)
    }

    /** A stream this link sent, started at [startedMillis] monotonic time, and its message's [settle]. */
    private class Outgoing(
        val settle: (sent: Boolean) -> Unit,
        val startedMillis: Long,
    ) {
        var writing = true
        var refused = false
    }

    private enum class Written { SENT, REFUSED, FAILED }

    /** The L2CAP CoC [psm] a central's [link] can open a channel to. */
    private class ChannelOffer(
        val link: CentralLink,
        val psm: Int,
    )

    companion object {
        /** How long a link may take to exchange hellos before the node closes the bearer (spec `handshake-timeout`). */
        const val HANDSHAKE_TIMEOUT_MILLIS: Long = 10_000

        /** How long a link closed as a duplicate waits for the peer's `CLOSE` (spec `duplicate-drain-timeout`). */
        const val DRAIN_TIMEOUT_MILLIS: Long = 10_000

        /** A peripheral's wait for the channel after the central's `BEARER_SWITCH` (spec `coc-accept-timeout`). */
        const val COC_ACCEPT_TIMEOUT_MILLIS: Long = 5_000

        /**
         * The first routed frame type: unicast `0x40`–`0x7F` and flood `0x80`–`0xBF` (spec
         * "Routing"); `0xC0` and above are reserved and close the link before this check.
         */
        private const val ROUTED_FIRST = 0x40
        private const val BYTE_MASK = 0xFF
    }
}

/**
 * How a session's links open: by [policy]'s hellos, or by [handshakes] in a secured profile; with
 * [coc], a central moves each link it opens to a peer that offers a PSM to L2CAP CoC. They [report]
 * diagnostics.
 */
internal class LinkAdmission(
    val policy: LinkPolicy,
    val handshakes: Handshakes?,
    val coc: Boolean,
    /** Emits the link diagnostics the session's links report. */
    val report: (LinkDiagnostic) -> Unit,
)

private fun Clock.now(): Instant = Instant.fromEpochMilliseconds(wallClockMillis())

/** Counts one link to [handle] fewer, forgetting the handle at zero. */
private fun MutableMap<PeerHandle, Int>.decrement(handle: PeerHandle) {
    val left = getValue(handle) - 1
    if (left == 0) remove(handle) else this[handle] = left
}
