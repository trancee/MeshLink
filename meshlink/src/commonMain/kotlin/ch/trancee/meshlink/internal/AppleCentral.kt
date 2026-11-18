package ch.trancee.meshlink.internal

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The central side of [AppleRadio], fed by the `CBCentralManager` callbacks: it scans, and it
 * connects one [AppleCentralLink] per peripheral, whose `CBPeripheral` callbacks [links] routes.
 */
internal class AppleCentral(
    private val bluetooth: CoreBluetooth,
    private val emit: (RadioEvent) -> Unit,
) : CentralManagerEvents {
    private var scanning = false

    private val open = mutableMapOf<String, AppleCentralLink>()

    /** The central manager's raw `CBManagerState`, null until it reports one. */
    val managerState = MutableStateFlow<Long?>(null)

    /** Routes the `CBPeripheral` callbacks to the link of their peripheral. */
    val links = AppleLinkEvents(open)

    /** Scans for the MESH service, reporting duplicates; ticket 32 owns duty cycles and background scans. */
    fun startScanning(): RadioResult<Unit> {
        bluetooth.scan(AppleGatt.MESH_SHORT)
        scanning = true
        return RadioResult.Ok(Unit)
    }

    fun stopScanning() {
        scanning = false
        bluetooth.stopScan()
    }

    /** Connects as central; a second link to a peripheral that has one is a [RadioFailure.RADIO_ERROR]. */
    suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> =
        when {
            peer !is ApplePeer -> {
                RadioResult.Failed(RadioFailure.INVALID_ARGUMENT)
            }

            peer.peripheral.id in open -> {
                RadioResult.Failed(RadioFailure.RADIO_ERROR)
            }

            else -> {
                val id = peer.peripheral.id
                val link = AppleCentralLink(bluetooth, peer.peripheral, emit) { open.remove(id) }
                open[id] = link
                link.open()
            }
        }

    /** Unless powered on, the central manager's connections are gone, and CoreBluetooth reports no disconnects. */
    override fun centralState(state: Long) {
        if (state != CbState.POWERED_ON) open.values.toList().forEach { it.close() }
        managerState.value = state
    }

    override fun discovered(
        peripheral: CbPeripheral,
        services: List<ByteArray>,
        rssi: Int,
    ) {
        val payload = services.firstOrNull { it.size == RadioPort.ADVERTISEMENT_LENGTH && !it.isMesh() }
        if (scanning && payload != null) emit(RadioEvent.Sighted(Sighting(payload, ApplePeer(peripheral), rssi)))
    }

    override fun connected(peripheral: CbPeripheral) {
        open[peripheral.id]?.let { it.requests.answer() }
    }

    override fun connectFailed(peripheral: CbPeripheral) {
        open[peripheral.id]?.close()
    }

    override fun disconnected(peripheral: CbPeripheral) {
        open[peripheral.id]?.close()
    }
}

/** Routes each `CBPeripheral` callback to the link of its peripheral in [links], if it has one. */
internal class AppleLinkEvents(
    private val links: Map<String, AppleCentralLink>,
) : PeripheralEvents {
    override fun servicesDiscovered(
        peripheral: CbPeripheral,
        services: List<ByteArray>?,
    ) = discovered(peripheral, services)

    override fun characteristicsDiscovered(
        peripheral: CbPeripheral,
        characteristics: List<ByteArray>?,
    ) = discovered(peripheral, characteristics)

    private fun discovered(
        peripheral: CbPeripheral,
        uuids: List<ByteArray>?,
    ) {
        val requests = links[peripheral.id]?.requests
        if (uuids != null) requests?.answer(uuids = uuids) else requests?.fail()
    }

    /** A read answer of the capability, or a notification of the stream. */
    override fun valueUpdated(
        peripheral: CbPeripheral,
        characteristic: ByteArray,
        value: ByteArray?,
    ) {
        val link = links[peripheral.id] ?: return
        when {
            characteristic.contentEquals(AppleGatt.CAPABILITY) -> {
                if (value != null) link.requests.answer(value) else link.requests.fail()
            }

            characteristic.contentEquals(AppleGatt.TO_CENTRAL) && value != null -> {
                link.receive(value)
            }
        }
    }

    override fun subscribeAnswered(
        peripheral: CbPeripheral,
        ok: Boolean,
    ) {
        val requests = links[peripheral.id]?.requests
        if (ok) requests?.answer() else requests?.fail()
    }

    override fun readyToWrite(peripheral: CbPeripheral) {
        links[peripheral.id]?.readyToWrite()
    }

    /** `didModifyServices`: a link whose MeshLink service went away is over. */
    override fun servicesInvalidated(
        peripheral: CbPeripheral,
        services: List<ByteArray>,
    ) {
        if (services.any { it.contentEquals(AppleGatt.SERVICE) }) links[peripheral.id]?.disconnect()
    }

    /** `didOpen` an L2CAP channel; one to a peripheral without a link is closed. */
    override fun channelOpened(
        peripheral: CbPeripheral,
        channel: CbChannel?,
    ) {
        val link = links[peripheral.id]
        if (link != null) link.channels.opened(channel) else channel?.close()
    }
}
