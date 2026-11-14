package ch.trancee.meshlink.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Many [RadioPort]s sharing one simulated air in one process, in virtual time (ADR 0017 decisions
 * 1–3). Every radio is in range of every other; each advertisement, connection, capability read and
 * stream chunk arrives after a latency drawn from [random] within [latencyMillis], so one seed
 * always yields one event order.
 *
 * It models no loss, link caps or advertising and scan windows yet. Coroutines run in [scope], which
 * a test passes as `backgroundScope` so they end with the test.
 */
internal class VirtualAir(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val random: RandomSource,
    private val latencyMillis: IntRange = DEFAULT_LATENCY_MILLIS,
) {
    private val radios = LinkedHashMap<PeerHandle, VirtualRadio>()

    /** Adds a radio whose links negotiate an ATT MTU of at most [maxAttMtu]. */
    fun radio(maxAttMtu: Int = DEFAULT_ATT_MTU): VirtualRadio {
        val radio = VirtualRadio(this, VirtualPeer(), maxAttMtu)
        radios[radio.handle] = radio
        return radio
    }

    internal fun latency(): Long = random.nextInt(latencyMillis.first, latencyMillis.last + 1).toLong()

    internal fun pipe(): Pipe = Pipe()

    internal fun announce(
        advertiser: VirtualRadio,
        payload: ByteArray,
    ) {
        radios.values.forEach { if (it !== advertiser && it.scanning) sight(it, advertiser, payload) }
    }

    internal fun listen(scanner: VirtualRadio) {
        radios.values.forEach { other ->
            val payload = other.advertisement
            if (other !== scanner && payload != null) sight(scanner, other, payload)
        }
    }

    internal suspend fun connect(
        central: VirtualRadio,
        peer: PeerHandle,
    ): RadioResult<CentralLink> {
        val peripheral = radios[peer]
        if (peripheral == null || peripheral === central || peripheral.advertisement == null) {
            return RadioResult.Failed(RadioFailure.PEER_UNREACHABLE)
        }
        delay(latency())
        val connection = VirtualConnection(this, central, peripheral)
        peripheral.emit(RadioEvent.Connected(connection.peripheralEnd))
        return RadioResult.Ok(connection.centralEnd)
    }

    private fun sight(
        scanner: VirtualRadio,
        advertiser: VirtualRadio,
        payload: ByteArray,
    ) {
        val sighting = Sighting(payload, advertiser.handle, random.nextInt(RSSI_MIN, RSSI_MAX + 1))
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
        private val DEFAULT_LATENCY_MILLIS: IntRange = 5..30
        const val DEFAULT_ATT_MTU: Int = 247
        const val RSSI_MIN: Int = -90
        const val RSSI_MAX: Int = -40
    }
}

/** A radio's identity on the [VirtualAir]; compared by reference. */
internal class VirtualPeer : PeerHandle

/** One node's radio on a [VirtualAir]. */
internal class VirtualRadio(
    private val air: VirtualAir,
    val handle: VirtualPeer,
    val maxAttMtu: Int,
) : RadioPort {
    private val eventChannel = Channel<RadioEvent>(Channel.UNLIMITED)

    override val events: Flow<RadioEvent> = eventChannel.receiveAsFlow()

    internal var advertisement: ByteArray? = null
        private set

    internal var scanning: Boolean = false
        private set

    internal var capability: ByteArray? = null
        private set

    override fun startAdvertising(payload: ByteArray): RadioResult<Unit> {
        if (payload.size != RadioPort.ADVERTISEMENT_LENGTH) return RadioResult.Failed(RadioFailure.INVALID_ARGUMENT)
        val copy = payload.copyOf()
        advertisement = copy
        air.announce(this, copy)
        return RadioResult.Ok(Unit)
    }

    override fun stopAdvertising() {
        advertisement = null
    }

    override fun startScanning(): RadioResult<Unit> {
        scanning = true
        air.listen(this)
        return RadioResult.Ok(Unit)
    }

    override fun stopScanning() {
        scanning = false
    }

    override fun serveCapability(record: ByteArray): RadioResult<Unit> {
        if (record.size > RadioPort.CAPABILITY_RECORD_MAX) return RadioResult.Failed(RadioFailure.INVALID_ARGUMENT)
        capability = record.copyOf()
        return RadioResult.Ok(Unit)
    }

    override suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> = air.connect(this, peer)

    internal fun emit(event: RadioEvent) {
        eventChannel.trySend(event)
    }
}

/** One GATT link between two radios, holding both of its ends. */
internal class VirtualConnection(
    air: VirtualAir,
    central: VirtualRadio,
    peripheral: VirtualRadio,
) {
    val attMtu: Int = min(central.maxAttMtu, peripheral.maxAttMtu)
    val centralEnd: VirtualCentralEnd = VirtualCentralEnd(air, this, central, peripheral)
    val peripheralEnd: VirtualLinkEnd = VirtualLinkEnd(air, this, peripheral, central)

    fun oppositeOf(end: VirtualLinkEnd): VirtualLinkEnd = if (end === centralEnd) peripheralEnd else centralEnd
}

/** One radio's end of a [VirtualConnection]. */
internal open class VirtualLinkEnd(
    air: VirtualAir,
    private val connection: VirtualConnection,
    private val local: VirtualRadio,
    protected val remote: VirtualRadio,
) : RadioLink {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val pipe = air.pipe()

    protected var closed: Boolean = false
        private set

    override val peer: PeerHandle get() = remote.handle

    override val attMtu: Int get() = connection.attMtu

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    override suspend fun write(bytes: ByteArray): RadioResult<Unit> {
        if (closed) return RadioResult.Failed(RadioFailure.LINK_CLOSED)
        val target = connection.oppositeOf(this)
        val chunkMax = attMtu - RadioPort.ATT_HEADER
        var start = 0
        while (start < bytes.size) {
            val chunk = bytes.copyOfRange(start, min(start + chunkMax, bytes.size))
            pipe.send { target.inbox.trySend(chunk) }
            start += chunkMax
        }
        return RadioResult.Ok(Unit)
    }

    override fun disconnect() {
        if (closed) return
        close()
        val target = connection.oppositeOf(this)
        pipe.send { target.close() }
    }

    private fun close() {
        if (closed) return
        closed = true
        inbox.close()
        local.emit(RadioEvent.Disconnected(this))
    }
}

/** The central's end of a [VirtualConnection], which can read the peripheral's capability. */
internal class VirtualCentralEnd(
    private val air: VirtualAir,
    connection: VirtualConnection,
    local: VirtualRadio,
    remote: VirtualRadio,
) : VirtualLinkEnd(air, connection, local, remote),
    CentralLink {
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
