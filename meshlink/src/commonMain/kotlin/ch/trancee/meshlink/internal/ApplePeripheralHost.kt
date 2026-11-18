package ch.trancee.meshlink.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * The peripheral side of [AppleRadio], fed by the `CBPeripheralManager` callbacks: it publishes
 * the MeshLink GATT service (spec D-13). A central's link begins when it subscribes to the
 * peripheral-to-central characteristic, after reading the capability record, and ends when it
 * unsubscribes, which CoreBluetooth also reports when the central disconnects. A whole capability
 * read from a subscribed central replaces its link, because an unbonded Android central that
 * reconnects subscribes no more (library rule R17). Notifications wait while `updateValue` reports
 * a full queue, until `peripheralManagerIsReady(toUpdateSubscribers:)`. The core caps the inbound
 * links at [AppleRadio.peripheralLinkCap] (ADR 0009 decision 6). [onPowerLoss] runs whenever the
 * peripheral manager reports a state other than powered on.
 */
internal class ApplePeripheralHost(
    private val bluetooth: CoreBluetooth,
    private val onPowerLoss: () -> Unit,
    private val emit: (RadioEvent) -> Unit,
) : PeripheralManagerEvents {
    private var record = ByteArray(0)
    private var published = false
    private val subscribed = mutableSetOf<String>()
    private val links = mutableMapOf<String, PeripheralLink>()

    /** The peripheral manager's raw `CBManagerState`, null until it reports one. */
    val managerState = MutableStateFlow<Long?>(null)

    /** Whether MeshLink advertises. */
    var advertising = false

    /** Counts the moments the notification queue may have room, or a link closed. */
    private val wakeups = MutableStateFlow(0)

    /** Serves [record] from now on, publishing the service unless it is published or publishing. */
    fun serve(record: ByteArray): RadioResult<Unit> {
        if (record.size > RadioPort.CAPABILITY_RECORD_MAX) return RadioResult.Failed(RadioFailure.INVALID_ARGUMENT)
        this.record = record.copyOf()
        if (!published) {
            published = true
            bluetooth.addService(
                GattService(
                    uuid = AppleGatt.SERVICE,
                    capability = AppleGatt.CAPABILITY,
                    toPeripheral = AppleGatt.TO_PERIPHERAL,
                    toCentral = AppleGatt.TO_CENTRAL,
                ),
            )
        }
        return RadioResult.Ok(Unit)
    }

    /** Unless powered on, the peripheral manager's service, channel and links are gone. */
    override fun peripheralState(state: Long) {
        if (state != CbState.POWERED_ON) {
            published = false
            subscribed.clear()
            links.values.toList().forEach { it.close() }
            onPowerLoss()
        }
        managerState.value = state
    }

    /** A failure to advertise matters only while MeshLink advertises. */
    override fun advertisingStarted(ok: Boolean) {
        if (advertising && !ok) emit(RadioEvent.Failed(RadioFailure.RADIO_ERROR))
    }

    override fun serviceAdded(ok: Boolean) {
        if (!ok) {
            published = false
            emit(RadioEvent.Failed(RadioFailure.RADIO_ERROR))
        }
    }

    override fun readRequested(request: CbReadRequest) {
        val value = record
        val capability = request.characteristic.contentEquals(AppleGatt.CAPABILITY)
        val offset = request.offset
        val answer = if (capability && offset <= value.size) value.copyOfRange(offset, value.size) else null
        bluetooth.respond(request, answer)
        if (capability && offset == 0 && request.central.id in subscribed) {
            links[request.central.id]?.close()
            open(request.central)
        }
    }

    override fun written(
        central: CbCentral,
        characteristic: ByteArray,
        value: ByteArray,
    ) {
        if (characteristic.contentEquals(AppleGatt.TO_PERIPHERAL)) links[central.id]?.receive(value)
    }

    override fun subscribed(
        central: CbCentral,
        characteristic: ByteArray,
    ) {
        if (characteristic.contentEquals(AppleGatt.TO_CENTRAL)) {
            subscribed += central.id
            if (central.id !in links) open(central)
        }
    }

    override fun unsubscribed(
        central: CbCentral,
        characteristic: ByteArray,
    ) {
        if (characteristic.contentEquals(AppleGatt.TO_CENTRAL)) {
            subscribed -= central.id
            links[central.id]?.close()
        }
    }

    override fun readyToNotify() {
        wakeups.value++
    }

    private fun open(central: CbCentral) {
        val link = PeripheralLink(central)
        links[central.id] = link
        emit(RadioEvent.Connected(link))
    }

    /** One central's link to this peripheral. */
    private inner class PeripheralLink(
        private val central: CbCentral,
    ) : StreamLink(AppleCentralPeer(central.id)) {
        override val attMtu: Int get() = bluetooth.maximumNotifyLength(central) + RadioPort.ATT_HEADER

        override suspend fun send(chunk: ByteArray): Boolean {
            while (!isClosed) {
                val seen = wakeups.value
                if (bluetooth.notify(central, chunk)) return true
                wakeups.first { it != seen }
            }
            return false
        }

        /** A `CBPeripheralManager` cannot end a central's connection; closing the link is all it can do. */
        override fun release() = Unit

        override fun closed() {
            links.remove(central.id)
            wakeups.value++
            emit(RadioEvent.Disconnected(this))
        }
    }
}
