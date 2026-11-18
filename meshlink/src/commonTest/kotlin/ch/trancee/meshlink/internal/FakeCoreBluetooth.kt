package ch.trancee.meshlink.internal

/**
 * A scripted [CoreBluetooth]: records every call in [calls] as `name args` (UUIDs and values in
 * hex), answers [notify] and [canWrite] from [notifyQueueFull] and [writeQueueFull], and holds the
 * [events], all three delegates in one, which a test then calls as CoreBluetooth would.
 */
internal class FakeCoreBluetooth : CoreBluetooth {
    override var authorization: Long = CbAuthorization.ALLOWED_ALWAYS

    lateinit var delegates: CoreBluetoothDelegates

    lateinit var events: FakeEvents

    val calls = mutableListOf<String>()

    var notifyQueueFull = false

    var writeQueueFull = false

    var notifyLength = NOTIFY_LENGTH

    var writeLength = WRITE_LENGTH

    var service: GattService? = null

    val notified = mutableListOf<ByteArray>()

    val written = mutableListOf<ByteArray>()

    val responses = mutableListOf<ByteArray?>()

    override fun open(delegates: CoreBluetoothDelegates) {
        this.delegates = delegates
        events = FakeEvents(delegates)
        calls += "open"
    }

    override fun scan(service: ByteArray) {
        calls += "scan ${service.toHexString()}"
    }

    override fun stopScan() {
        calls += "stopScan"
    }

    override fun advertise(services: List<ByteArray>) {
        calls += "advertise ${services.joinToString(",") { it.toHexString() }}"
    }

    override fun stopAdvertising() {
        calls += "stopAdvertising"
    }

    override fun addService(service: GattService) {
        // `CBMutableService` asserts `UUID.data.length == 2 || UUID.data.length == 16`.
        check(service.uuid.size == 2 || service.uuid.size == 16) {
            "CBMutableService rejects ${service.uuid.size} B UUIDs"
        }
        this.service = service
        calls += "addService ${service.uuid.toHexString()}"
    }

    override fun respond(
        request: CbReadRequest,
        value: ByteArray?,
    ) {
        responses += value
        calls += "respond ${request.central.id} ${value?.toHexString()}"
    }

    override fun notify(
        central: CbCentral,
        value: ByteArray,
    ): Boolean {
        calls += "notify ${central.id} ${value.toHexString()}"
        if (!notifyQueueFull) notified += value
        return !notifyQueueFull
    }

    override fun maximumNotifyLength(central: CbCentral): Int = notifyLength

    override fun connect(peripheral: CbPeripheral) {
        calls += "connect ${peripheral.id}"
    }

    override fun cancel(peripheral: CbPeripheral) {
        calls += "cancel ${peripheral.id}"
    }

    override fun discoverServices(
        peripheral: CbPeripheral,
        service: ByteArray,
    ) {
        calls += "discoverServices ${peripheral.id} ${service.toHexString()}"
    }

    override fun discoverCharacteristics(
        peripheral: CbPeripheral,
        service: ByteArray,
        characteristics: List<ByteArray>,
    ) {
        calls += "discoverCharacteristics ${peripheral.id} ${characteristics.size}"
    }

    override fun read(
        peripheral: CbPeripheral,
        characteristic: ByteArray,
    ) {
        calls += "read ${peripheral.id} ${characteristic.toHexString()}"
    }

    override fun subscribe(
        peripheral: CbPeripheral,
        characteristic: ByteArray,
    ) {
        calls += "subscribe ${peripheral.id} ${characteristic.toHexString()}"
    }

    override fun write(
        peripheral: CbPeripheral,
        characteristic: ByteArray,
        value: ByteArray,
    ) {
        calls += "write ${peripheral.id} ${value.toHexString()}"
        written += value
    }

    override fun canWrite(peripheral: CbPeripheral): Boolean = !writeQueueFull

    override fun maximumWriteLength(peripheral: CbPeripheral): Int = writeLength

    override fun openChannel(
        peripheral: CbPeripheral,
        psm: Int,
    ) {
        calls += "openChannel ${peripheral.id} $psm"
    }

    override fun publishChannel() {
        calls += "publishChannel"
    }

    override fun unpublishChannel(psm: Int) {
        calls += "unpublishChannel $psm"
    }

    private companion object {
        /** An iPhone's notification payload at ATT MTU 185. */
        const val NOTIFY_LENGTH = 182

        /** An iPhone's write-without-response payload at ATT MTU 185. */
        const val WRITE_LENGTH = 182
    }
}

internal data class FakePeripheral(
    override val id: String,
) : CbPeripheral

internal data class FakeCentral(
    override val id: String,
) : CbCentral

internal class FakeReadRequest(
    override val central: CbCentral,
    override val characteristic: ByteArray,
    override val offset: Int,
) : CbReadRequest

/**
 * A scripted `CBL2CAPChannel` from [peerId]: [read] hands out [readable] in turn, then reports the
 * end; [write] takes at most [writeLimit] bytes at a time into [written] while [hasSpace], or fails
 * once [writeFails]. [events] is what the radio opened it with.
 */
internal class FakeChannel(
    override val peerId: String,
) : CbChannel {
    val readable = ArrayDeque<ByteArray>()

    val written = mutableListOf<ByteArray>()

    var writeLimit = Int.MAX_VALUE

    var writeFails = false

    var events: CbChannelEvents? = null

    var closed = 0

    override var hasSpace = true

    override val hasBytes: Boolean get() = readable.isNotEmpty()

    override fun open(events: CbChannelEvents) {
        this.events = events
    }

    override fun read(buffer: ByteArray): Int {
        val next = readable.removeFirst()
        next.copyInto(buffer)
        return next.size
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        if (writeFails) return -1
        val count = minOf(writeLimit, bytes.size - offset)
        written += bytes.copyOfRange(offset, offset + count)
        return count
    }

    override fun close() {
        closed++
    }
}

/** The four [delegates] in one, so a test calls any callback on it. */
internal class FakeEvents(
    delegates: CoreBluetoothDelegates,
) : CentralManagerEvents by delegates.central,
    PeripheralEvents by delegates.peripheral,
    PeripheralManagerEvents by delegates.peripheralManager,
    ChannelListenerEvents by delegates.channels
