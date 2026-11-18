package ch.trancee.meshlink.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * This node's link to a peripheral, as GATT client through a `CBCentralManager` (ADR 0010).
 * [open] connects, then discovers the MeshLink service and its characteristics; [readCapability]
 * reads the capability record and then subscribes to the peripheral's stream. Writes go without
 * response, waiting while `canSendWriteWithoutResponse` is false until
 * `peripheralIsReady(toSendWriteWithoutResponse:)`. Every request waits for the previous one's
 * callback, through [requests]. CoreBluetooth negotiates the ATT MTU by itself. [channels] opens
 * the link's L2CAP CoC channels.
 *
 * A connection attempt never times out in CoreBluetooth, so [open] cancels it after
 * [CONNECT_TIMEOUT_MILLIS], about when Android's stack gives up. CoreBluetooth callbacks name the
 * peripheral only, so a late callback for a cancelled connection reaches the next link to the same
 * peripheral; that link then fails, and the core connects again.
 */
internal class AppleCentralLink(
    private val bluetooth: CoreBluetooth,
    private val peripheral: CbPeripheral,
    private val emit: (RadioEvent) -> Unit,
    private val closing: (AppleCentralLink) -> Unit,
) : StreamLink(ApplePeer(peripheral)),
    CentralLink {
    /** The link's requests, which the delegate callbacks answer. */
    val requests = AppleRequests { isClosed }
    private var opened = false

    /** Counts the moments the peripheral may take more writes, or the link closed. */
    private val wakeups = MutableStateFlow(0)

    /** The link's L2CAP CoC channels, which close with it. */
    val channels = AppleChannelOpener(bluetooth, peripheral)

    override val attMtu: Int get() = bluetooth.maximumWriteLength(peripheral) + RadioPort.ATT_HEADER

    /** Connects and discovers the MeshLink service; on failure or cancellation the link is closed. */
    suspend fun open(): RadioResult<CentralLink> {
        val failure =
            try {
                establish()
            } catch (e: CancellationException) {
                disconnect()
                throw e
            }
        if (failure != null) disconnect()
        opened = failure == null
        return failure?.let { RadioResult.Failed(it) } ?: RadioResult.Ok(this)
    }

    override suspend fun readCapability(): RadioResult<ByteArray> {
        val record = requests.request { bluetooth.read(peripheral, AppleGatt.CAPABILITY) }?.value
        val subscribed =
            record != null && requests.request { bluetooth.subscribe(peripheral, AppleGatt.TO_CENTRAL) } != null
        return when {
            subscribed -> RadioResult.Ok(record)
            isClosed -> RadioResult.Failed(RadioFailure.LINK_CLOSED)
            else -> RadioResult.Failed(RadioFailure.RADIO_ERROR)
        }
    }

    /** `peripheralIsReady(toSendWriteWithoutResponse:)`. */
    fun readyToWrite() {
        wakeups.value++
    }

    override suspend fun openChannel(psm: Int): RadioResult<RadioStream> = channels.open(psm)

    /** `CBPeripheral.readRSSI()`, answered by `didReadRSSI`. */
    override suspend fun readRssi(): RadioResult<Int> {
        val answer = requests.request { bluetooth.readRssi(peripheral) }
        return when {
            answer != null -> RadioResult.Ok(answer.rssi)
            isClosed -> RadioResult.Failed(RadioFailure.LINK_CLOSED)
            else -> RadioResult.Failed(RadioFailure.RADIO_ERROR)
        }
    }

    override suspend fun send(chunk: ByteArray): Boolean {
        while (!isClosed) {
            val seen = wakeups.value
            if (bluetooth.canWrite(peripheral)) {
                bluetooth.write(peripheral, AppleGatt.TO_PERIPHERAL, chunk)
                return true
            }
            wakeups.first { it != seen }
        }
        return false
    }

    override fun release() = bluetooth.cancel(peripheral)

    override fun closed() {
        requests.fail()
        channels.close()
        wakeups.value++
        closing(this)
        if (opened) emit(RadioEvent.Disconnected(this))
    }

    private suspend fun establish(): RadioFailure? {
        val connected =
            withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { requests.request { bluetooth.connect(peripheral) } }
        val services =
            connected?.let { requests.request { bluetooth.discoverServices(peripheral, AppleGatt.SERVICE) } }
        val service = services?.uuids.orEmpty().any { it.contentEquals(AppleGatt.SERVICE) }
        val characteristics =
            if (service) {
                requests.request { bluetooth.discoverCharacteristics(peripheral, AppleGatt.SERVICE, CHARACTERISTICS) }
            } else {
                null
            }
        val found = characteristics?.uuids.orEmpty()
        val complete = CHARACTERISTICS.all { wanted -> found.any { it.contentEquals(wanted) } }
        return when {
            connected == null -> RadioFailure.PEER_UNREACHABLE
            services == null -> RadioFailure.LINK_CLOSED
            !service -> RadioFailure.CAPABILITY_MISSING
            characteristics == null -> RadioFailure.LINK_CLOSED
            !complete -> RadioFailure.CAPABILITY_MISSING
            else -> null
        }
    }

    companion object {
        /** How long [open] waits for `didConnect`, as Android's stack does (status 147 after about 30 s). */
        const val CONNECT_TIMEOUT_MILLIS: Long = 30_000
    }
}

/**
 * One CoreBluetooth request at a time on a link: [request] starts one and waits until a delegate
 * callback calls [answer] or [fail]. A link that is [closed] starts none.
 */
internal class AppleRequests(
    private val closed: () -> Boolean,
) {
    private val mutex = Mutex()
    private var pending: CompletableDeferred<Answer?>? = null

    /** Starts one request with [action] and waits for its answer; null if it failed or the link closed. */
    suspend fun request(action: () -> Unit): Answer? =
        mutex.withLock {
            if (closed()) return null
            val answer = CompletableDeferred<Answer?>()
            pending = answer
            action()
            try {
                answer.await()
            } finally {
                pending = null
            }
        }

    /**
     * Answers the pending request: `didConnect`, a discovery with [uuids], a read with [value], a
     * subscription, or an RSSI read with [rssi].
     */
    fun answer(
        value: ByteArray? = null,
        uuids: List<ByteArray> = emptyList(),
        rssi: Int = 0,
    ) {
        pending?.complete(Answer(value, uuids, rssi))
    }

    /** Fails the pending request, as a callback with an error does. */
    fun fail() {
        pending?.complete(null)
    }

    /** A callback's successful answer, with the value, UUIDs or RSSI it carried. */
    class Answer(
        val value: ByteArray?,
        val uuids: List<ByteArray>,
        val rssi: Int,
    )
}

private val CHARACTERISTICS = listOf(AppleGatt.CAPABILITY, AppleGatt.TO_PERIPHERAL, AppleGatt.TO_CENTRAL)

/** Whether these advertised `CBUUID.data` bytes are the MESH UUID, in its 32-bit or 128-bit form. */
internal fun ByteArray.isMesh(): Boolean = MESH_UUIDS.any { contentEquals(it) }

private val MESH_UUIDS = listOf(AppleGatt.MESH_SHORT, AppleGatt.MESH_FULL)
