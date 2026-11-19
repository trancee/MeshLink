package ch.trancee.meshlink.internal

import ch.trancee.meshlink.Requirement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Many [RadioPort]s sharing one simulated air in one process, in virtual time (ADR 0017 decisions
 * 1–3). Every radio starts at the origin, in range of every other; a test places one elsewhere with
 * [move], and radios more than [range] apart neither sight nor reach each other. Each advertisement,
 * connection, capability read and stream chunk arrives after a latency drawn from [random] within
 * [latencyMillis], so one seed always yields one event order.
 *
 * A radio takes a fresh address each time it starts advertising, as a phone rotates its resolvable
 * private address, so a peer can't be reached at an address it no longer advertises; a link keeps
 * the address it was opened to. A radio may report a peripheral link cap, which the core enforces;
 * a test drops a link as a transient loss with [VirtualLinkEnd.drop] or by moving its radios apart.
 * It models no other loss.
 * A scanning radio sees every advertisement in range at once and again each time it starts
 * scanning, and logs its scan starts and stops in [VirtualRadio.scanLog].
 *
 * Each sighting and RSSI read draws a signal strength from [random], above the weakest at which a
 * node dials (`Views.DIAL_FLOOR_DBM`), unless a test fixes one for the pair with [setRssi]. A test
 * makes a radio advertise as a backgrounded iPhone with [VirtualRadio.background], so its
 * sightings carry no payload and it keeps its address, and adds a radio that supports the LE 2M
 * PHY with `le2MPhy = true`.
 *
 * A radio supports L2CAP CoC only when a test adds it with `coc = true`; any other radio behaves as
 * an Android 8 or 9 phone, whose listener and channel opening are unsupported. A listening radio
 * always gets [VirtualRadio.PSM], accepts every channel opened to it unless the test sets
 * [VirtualRadio.refuseChannels], and each channel write arrives whole, after a latency, in order. A
 * channel closes with its link; a test drops one with [VirtualChannelEnd.drop].
 *
 * Coroutines run in [scope], which a test passes as `backgroundScope` so they end with the test.
 * When [scope] ends, every radio reports Bluetooth off, so the nodes on the air stop their sessions
 * and periodic timers and the test scheduler can go idle.
 */
internal class VirtualAir(
    private val scope: CoroutineScope,
    internal val clock: Clock,
    private val random: RandomSource,
    private val latencyMillis: IntRange = DEFAULT_LATENCY_MILLIS,
    private val range: Double = DEFAULT_RANGE,
) {
    private val radios = mutableListOf<VirtualRadio>()
    private val rssis = mutableMapOf<Set<VirtualRadio>, Int>()

    init {
        scope.coroutineContext.job.invokeOnCompletion {
            radios.forEach { radio -> radio.report(setOf(Requirement.BluetoothOff)) }
        }
    }

    /**
     * Adds a radio, reporting [peripheralLinkCap], supporting L2CAP CoC if [coc] and the LE 2M PHY if
     * [le2MPhy], and a platform around it whose nodes do their work on [dispatcher].
     */
    fun platform(
        dispatcher: CoroutineDispatcher,
        peripheralLinkCap: Int? = null,
        coc: Boolean = false,
        le2MPhy: Boolean = false,
    ): VirtualPlatform {
        val radio = radio(peripheralLinkCap = peripheralLinkCap, coc = coc, le2MPhy = le2MPhy)
        return VirtualPlatform(radio, clock, random, dispatcher)
    }

    /**
     * Adds a radio whose links negotiate an ATT MTU of at most [maxAttMtu], that reports
     * [peripheralLinkCap], supports L2CAP CoC if [coc] and reports LE 2M PHY support if [le2MPhy].
     */
    fun radio(
        maxAttMtu: Int = DEFAULT_ATT_MTU,
        peripheralLinkCap: Int? = null,
        coc: Boolean = false,
        le2MPhy: Boolean = false,
    ): VirtualRadio {
        val radio = VirtualRadio(this, maxAttMtu, peripheralLinkCap, coc, le2MPhy)
        radios += radio
        return radio
    }

    /**
     * Fixes the signal strength between [a] and [b], both ways, at [dBm] for every later sighting and
     * RSSI read, until set again.
     */
    fun setRssi(
        a: VirtualRadio,
        b: VirtualRadio,
        dBm: Int,
    ) {
        rssis[setOf(a, b)] = dBm
    }

    /** The signal strength between [a] and [b]: as set, or drawn from [random] in [RSSI_MIN]..[RSSI_MAX]. */
    internal fun rssi(
        a: VirtualRadio,
        b: VirtualRadio,
    ): Int = rssis[setOf(a, b)] ?: random.nextInt(RSSI_MIN, RSSI_MAX + 1)

    internal fun latency(): Long = random.nextInt(latencyMillis.first, latencyMillis.last + 1).toLong()

    /**
     * Places [radio] at ([x], [y]); every radio starts at the origin. Each link of [radio] to a radio
     * now out of range drops at once, as a transient loss.
     */
    fun move(
        radio: VirtualRadio,
        x: Double,
        y: Double,
    ) {
        radio.x = x
        radio.y = y
        radio.ends.filter { it.open && !inRange(radio, it.remote) }.forEach { it.drop() }
    }

    /** Whether [a] and [b] are at most [range] apart. */
    private fun inRange(
        a: VirtualRadio,
        b: VirtualRadio,
    ): Boolean {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy <= range * range
    }

    internal fun announce(
        advertiser: VirtualRadio,
        payload: ByteArray,
    ) {
        radios.forEach { scanner ->
            if (scanner !== advertiser && scanner.scanning && inRange(scanner, advertiser)) {
                sight(scanner, advertiser, payload)
            }
        }
    }

    internal fun listen(scanner: VirtualRadio) {
        radios.forEach { other ->
            val payload = other.advertisement
            if (other !== scanner && payload != null && inRange(scanner, other)) sight(scanner, other, payload)
        }
    }

    internal suspend fun connect(
        central: VirtualRadio,
        peer: PeerHandle,
    ): RadioResult<CentralLink> {
        val peripheral = radios.firstOrNull { it.handle === peer && it !== central && it.advertisement != null }
        if (peripheral == null || !inRange(central, peripheral)) {
            return RadioResult.Failed(RadioFailure.PEER_UNREACHABLE)
        }
        delay(latency())
        val connection = VirtualConnection(this, central, peripheral, peer)
        peripheral.emit(RadioEvent.Connected(connection.peripheralEnd))
        return RadioResult.Ok(connection.centralEnd)
    }

    private fun sight(
        scanner: VirtualRadio,
        advertiser: VirtualRadio,
        payload: ByteArray,
    ) {
        val sighted = payload.takeUnless { advertiser.background }
        val sighting = Sighting(sighted, advertiser.handle, rssi(scanner, advertiser))
        val delayMillis = latency()
        scope.launch {
            delay(delayMillis)
            if (scanner.scanning) scanner.emit(RadioEvent.Sighted(sighting))
        }
    }

    /** One direction of a link: actions run in send order, each after its own latency. */
    internal inner class Pipe {
        private val queue = Channel<Pair<Long, () -> Unit>>(Channel.UNLIMITED)
        private var lastAtMillis = 0L

        init {
            scope.launch {
                while (true) {
                    val (atMillis, action) = queue.receive()
                    delay(atMillis - clock.monotonicMillis())
                    action()
                }
            }
        }

        fun send(action: () -> Unit) {
            lastAtMillis = max(lastAtMillis, clock.monotonicMillis() + latency())
            queue.trySend(lastAtMillis to action)
        }
    }

    companion object {
        /** The latency range of every delivery unless a test sets another. */
        val DEFAULT_LATENCY_MILLIS: IntRange = 5..30
        const val DEFAULT_ATT_MTU: Int = 247
        const val RSSI_MIN: Int = -80
        const val RSSI_MAX: Int = -40

        /** How far apart two radios may be and still hear each other, unless a test sets another range. */
        const val DEFAULT_RANGE: Double = 30.0
    }
}

/** A radio's address on the [VirtualAir]; compared by reference. */
internal class VirtualPeer : PeerHandle

/** One node's radio on a [VirtualAir]. */
internal class VirtualRadio(
    private val air: VirtualAir,
    val maxAttMtu: Int,
    override val peripheralLinkCap: Int? = null,
    val coc: Boolean = false,
    override val supportsLe2MPhy: Boolean = false,
) : RadioPort {
    /** The radio's current address; each [startAdvertising] replaces it unless the radio is [background]. */
    var handle: VirtualPeer = VirtualPeer()
        private set

    private val eventChannel = Channel<RadioEvent>(Channel.UNLIMITED)

    override val events: Flow<RadioEvent> = eventChannel.receiveAsFlow()

    /** What the radio reports missing, or `null` while it doesn't know yet; see [report] and [reportUnknown]. */
    private val missing = MutableStateFlow<Set<Requirement>?>(emptySet())
    internal val requirements: StateFlow<Set<Requirement>?> = missing

    internal var advertisement: ByteArray? = null
        private set

    internal var scanning: Boolean = false
        private set

    /**
     * Whether the radio advertises as a backgrounded iPhone does, with only the MESH bit of Apple's
     * overflow area, so scanners see its sightings without a payload. Like an iPhone, it keeps its
     * address when it starts advertising again (ADR 0010 decision 7).
     */
    var background: Boolean = false

    private val mutableScanLog = mutableListOf<Pair<Long, Boolean>>()

    /** When the radio started (`true`) and stopped (`false`) scanning, in monotonic milliseconds. */
    val scanLog: List<Pair<Long, Boolean>> get() = mutableScanLog.toList()

    internal var capability: ByteArray? = null
        private set

    /** Where the radio is on the air; see [VirtualAir.move]. */
    internal var x: Double = 0.0
    internal var y: Double = 0.0

    private val mutableAdvertisements = mutableListOf<Pair<Long, ByteArray>>()

    /** Every payload this radio started advertising, with the wall-clock milliseconds it started at. */
    internal val advertisements: List<Pair<Long, ByteArray>> get() = mutableAdvertisements.toList()

    /** The radio's link ends, which add and log themselves. */
    internal val log = VirtualEnds()

    /** This radio's end of every link it took part in, open or closed, in the order they opened. */
    internal val ends: List<VirtualLinkEnd> get() = log.ends

    /** Each link this radio held as peripheral, in order: `true` when it opened, `false` when it closed. */
    internal val peripheralLog: List<Pair<VirtualLinkEnd, Boolean>> get() = log.peripheral

    override fun startAdvertising(payload: ByteArray): RadioResult<Unit> {
        if (payload.size != RadioPort.ADVERTISEMENT_LENGTH) return RadioResult.Failed(RadioFailure.INVALID_ARGUMENT)
        val copy = payload.copyOf()
        if (!background) handle = VirtualPeer()
        advertisement = copy
        mutableAdvertisements += air.clock.wallClockMillis() to copy
        air.announce(this, copy)
        return RadioResult.Ok(Unit)
    }

    override fun stopAdvertising() {
        advertisement = null
    }

    override fun startScanning(): RadioResult<Unit> {
        scanning = true
        mutableScanLog += air.clock.monotonicMillis() to true
        air.listen(this)
        return RadioResult.Ok(Unit)
    }

    override fun stopScanning() {
        if (scanning) mutableScanLog += air.clock.monotonicMillis() to false
        scanning = false
    }

    override fun serveCapability(record: ByteArray): RadioResult<Unit> {
        if (record.size > RadioPort.CAPABILITY_RECORD_MAX) return RadioResult.Failed(RadioFailure.INVALID_ARGUMENT)
        capability = record.copyOf()
        return RadioResult.Ok(Unit)
    }

    override suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> = air.connect(this, peer)

    /** Whether the radio accepts channels; see [listenForChannels]. */
    internal var listening: Boolean = false
        private set

    /** Whether the radio refuses every channel opened to it, as a platform out of channels does. */
    var refuseChannels: Boolean = false

    override suspend fun listenForChannels(): RadioResult<Int> {
        if (!coc) return RadioResult.Failed(RadioFailure.UNSUPPORTED)
        listening = true
        return RadioResult.Ok(PSM)
    }

    override fun stopListening() {
        listening = false
    }

    /** Reports [missing] as the radio's requirements, as a real radio does when Bluetooth turns off. */
    fun report(missing: Set<Requirement>) {
        this.missing.value = missing
    }

    /** Reports nothing until the next [report], as a real radio does before its first state callback. */
    fun reportUnknown() {
        missing.value = null
    }

    internal fun emit(event: RadioEvent) {
        eventChannel.trySend(event)
    }

    companion object {
        /** The PSM every listening radio gets, the first of the LE dynamic range. */
        const val PSM: Int = 0x0080
    }
}

/** One radio's link ends, in the order they opened, and when each it held as peripheral opened and closed. */
internal class VirtualEnds {
    private val mutableEnds = mutableListOf<VirtualLinkEnd>()
    private val mutablePeripheral = mutableListOf<Pair<VirtualLinkEnd, Boolean>>()

    val ends: List<VirtualLinkEnd> get() = mutableEnds.toList()
    val peripheral: List<Pair<VirtualLinkEnd, Boolean>> get() = mutablePeripheral

    fun opened(end: VirtualLinkEnd) {
        mutableEnds += end
        if (end !is VirtualCentralEnd) mutablePeripheral += end to true
    }

    fun closed(end: VirtualLinkEnd) {
        if (end !is VirtualCentralEnd) mutablePeripheral += end to false
    }
}

/**
 * One GATT link between two radios, holding both of its ends. The central's end keeps the handle
 * it [dialed], even if the peripheral rotated its address meanwhile, as a platform link does.
 */
internal class VirtualConnection(
    air: VirtualAir,
    central: VirtualRadio,
    peripheral: VirtualRadio,
    dialed: PeerHandle,
) {
    val attMtu: Int = min(central.maxAttMtu, peripheral.maxAttMtu)
    val centralEnd: VirtualCentralEnd = VirtualCentralEnd(air, this, central, peripheral, dialed)
    val peripheralEnd: VirtualLinkEnd = VirtualLinkEnd(air, this, peripheral, central, central.handle)

    fun oppositeOf(end: VirtualLinkEnd): VirtualLinkEnd = if (end === centralEnd) peripheralEnd else centralEnd
}

/** One L2CAP CoC channel between the two ends of a [VirtualConnection], holding both of its ends. */
internal class VirtualChannel(
    air: VirtualAir,
    connection: VirtualConnection,
) {
    val centralEnd: VirtualChannelEnd = VirtualChannelEnd(air, this, connection.centralEnd.peer)
    val peripheralEnd: VirtualChannelEnd = VirtualChannelEnd(air, this, connection.peripheralEnd.peer)

    fun oppositeOf(end: VirtualChannelEnd): VirtualChannelEnd = if (end === centralEnd) peripheralEnd else centralEnd
}

/** One radio's end of a [VirtualChannel]. */
internal class VirtualChannelEnd(
    air: VirtualAir,
    private val channel: VirtualChannel,
    override val peer: PeerHandle,
) : RadioStream {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val pipe = air.Pipe()
    private var closed = false

    /** Every byte this end wrote, in order. */
    var written: ByteArray = ByteArray(0)
        private set

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    override suspend fun write(bytes: ByteArray): RadioResult<Unit> {
        if (closed) return RadioResult.Failed(RadioFailure.LINK_CLOSED)
        written += bytes
        val target = channel.oppositeOf(this)
        val copy = bytes.copyOf()
        pipe.send { target.inbox.trySend(copy) }
        return RadioResult.Ok(Unit)
    }

    override fun disconnect() {
        if (closed) return
        close()
        val target = channel.oppositeOf(this)
        pipe.send { target.close() }
    }

    /** Closes both ends at once, as a channel lost to the air. */
    fun drop() {
        close()
        channel.oppositeOf(this).close()
    }

    internal fun close() {
        closed = true
        inbox.close()
    }
}

/** One radio's end of a [VirtualConnection]. */
internal open class VirtualLinkEnd(
    private val air: VirtualAir,
    private val connection: VirtualConnection,
    private val local: VirtualRadio,
    /** The radio at the link's other end. */
    internal val remote: VirtualRadio,
    /** The handle of [remote] this end knows. */
    override val peer: PeerHandle,
) : RadioLink {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val pipe = air.Pipe()

    protected var closed: Boolean = false
        private set

    /** Whether the link is still open at this end. */
    val open: Boolean get() = !closed

    /** Every byte this end wrote, in order. */
    var written: ByteArray = ByteArray(0)
        private set

    private val mutableWrites = mutableListOf<Pair<Long, ByteArray>>()

    /** Each write's bytes, with its wall-clock time. */
    val writes: List<Pair<Long, ByteArray>> get() = mutableWrites.toList()

    /** Whether [write] returns only once the peer received the bytes, as a write with flow control does. */
    var paced: Boolean = false

    /** How many chunks, each one write without response or notification, this end wrote. */
    var chunks: Int = 0
        private set

    private val mutableChannels = mutableListOf<VirtualChannelEnd>()

    /** This end of every channel opened on the link, in the order they opened. */
    val channels: List<VirtualChannelEnd> get() = mutableChannels.toList()

    internal fun add(channel: VirtualChannelEnd) {
        mutableChannels += channel
    }

    init {
        local.log.opened(this)
    }

    /** The other radio's end of the link. */
    val opposite: VirtualLinkEnd get() = connection.oppositeOf(this)

    override val attMtu: Int get() = connection.attMtu

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    override suspend fun write(bytes: ByteArray): RadioResult<Unit> {
        if (closed) return RadioResult.Failed(RadioFailure.LINK_CLOSED)
        written += bytes
        mutableWrites += air.clock.wallClockMillis() to bytes.copyOf()
        val target = connection.oppositeOf(this)
        val chunkMax = attMtu - RadioPort.ATT_HEADER
        val received = CompletableDeferred<Unit>()
        var start = 0
        while (start < bytes.size) {
            val chunk = bytes.copyOfRange(start, min(start + chunkMax, bytes.size))
            val last = start + chunkMax >= bytes.size
            chunks++
            pipe.send {
                target.inbox.trySend(chunk)
                if (last) received.complete(Unit)
            }
            start += chunkMax
        }
        if (paced) received.await()
        return RadioResult.Ok(Unit)
    }

    override fun disconnect() {
        if (closed) return
        close(false)
        val target = connection.oppositeOf(this)
        pipe.send { target.close(false) }
    }

    /** Closes both ends at once as a transient loss, as a platform reports an unanswered ATT request. */
    fun drop() {
        close(true)
        connection.oppositeOf(this).close(true)
    }

    private fun close(transient: Boolean) {
        if (closed) return
        closed = true
        inbox.close()
        mutableChannels.forEach { it.close() }
        local.log.closed(this)
        local.emit(RadioEvent.Disconnected(this, transient))
    }
}

/** The central's end of a [VirtualConnection], which can read the peripheral's capability and the link's RSSI. */
internal class VirtualCentralEnd(
    private val air: VirtualAir,
    private val connection: VirtualConnection,
    private val local: VirtualRadio,
    remote: VirtualRadio,
    peer: PeerHandle,
) : VirtualLinkEnd(air, connection, local, remote, peer),
    CentralLink {
    override suspend fun openChannel(psm: Int): RadioResult<RadioStream> {
        if (!local.coc) return RadioResult.Failed(RadioFailure.UNSUPPORTED)
        delay(air.latency())
        return when {
            closed -> {
                RadioResult.Failed(RadioFailure.LINK_CLOSED)
            }

            !remote.listening || remote.refuseChannels || psm != VirtualRadio.PSM -> {
                RadioResult.Failed(RadioFailure.RADIO_ERROR)
            }

            else -> {
                RadioResult.Ok(open())
            }
        }
    }

    private fun open(): RadioStream {
        val channel = VirtualChannel(air, connection)
        add(channel.centralEnd)
        connection.peripheralEnd.add(channel.peripheralEnd)
        remote.emit(RadioEvent.ChannelOpened(channel.peripheralEnd))
        return channel.centralEnd
    }

    override suspend fun readRssi(): RadioResult<Int> {
        delay(air.latency())
        return if (closed) RadioResult.Failed(RadioFailure.LINK_CLOSED) else RadioResult.Ok(air.rssi(local, remote))
    }

    override suspend fun readCapability(): RadioResult<ByteArray> {
        delay(air.latency())
        val record = remote.capability
        return when {
            closed -> RadioResult.Failed(RadioFailure.LINK_CLOSED)
            record == null -> RadioResult.Failed(RadioFailure.CAPABILITY_MISSING)
            else -> RadioResult.Ok(record.copyOf())
        }
    }
}
