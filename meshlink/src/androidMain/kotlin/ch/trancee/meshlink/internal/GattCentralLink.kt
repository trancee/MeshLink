package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

/**
 * This node's link to a peripheral, as GATT client (ADR 0010). [open] connects directly over LE,
 * asks for an ATT MTU of [MeshGatt.REQUESTED_ATT_MTU] and discovers the MeshLink service;
 * [readCapability] reads the capability record and then subscribes to the peripheral's stream.
 * Writes go without response to [MeshGatt.TO_PERIPHERAL]; notifications on [MeshGatt.TO_CENTRAL]
 * arrive on [incoming]. Every GATT request waits for the previous one's callback ([GattExchange]).
 *
 * Requests have no timeout of their own: the stack ends a connection attempt it cannot complete
 * (status 147 after about 30 s), and the core's handshake timer ends a stalled link. A connect the
 * stack ends with a status worth retrying fails as [RadioFailure.TRANSIENT], and the stack closing
 * the open link with one is a transient [RadioEvent.Disconnected] ([AndroidGatt.losesTransiently]).
 * [openChannel] opens L2CAP CoC channels on [io] through [CocChannels]; they close with the link.
 */
internal class GattCentralLink(
    private val context: Context,
    private val device: BluetoothDevice,
    io: CoroutineDispatcher,
    private val emit: (RadioEvent) -> Unit,
) : StreamLink(AndroidPeer(device)),
    CentralLink {
    private val exchange = GattExchange()
    private val callback = Callback()
    private val channels = CocChannels(device, io)

    @Volatile private var gatt: BluetoothGatt? = null

    @Volatile private var ready: Ready? = null

    @Volatile private var mtu = MeshGatt.DEFAULT_ATT_MTU

    @Volatile private var opened = false

    /** The status the stack closed the link with; success until it does. */
    @Volatile private var lost = BluetoothGatt.GATT_SUCCESS

    override val attMtu: Int get() = mtu

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
        return when {
            failure == null -> RadioResult.Ok(this)
            AndroidGatt.failsTransiently(lost) -> RadioResult.Failed(RadioFailure.TRANSIENT)
            else -> RadioResult.Failed(failure)
        }
    }

    override suspend fun readCapability(): RadioResult<ByteArray> {
        val record = ready?.readCapability(exchange)
        return when {
            record != null -> RadioResult.Ok(record)
            isClosed -> RadioResult.Failed(RadioFailure.LINK_CLOSED)
            else -> RadioResult.Failed(RadioFailure.RADIO_ERROR)
        }
    }

    override suspend fun openChannel(psm: Int): RadioResult<RadioStream> = channels.open(psm)

    // Ticket 32a reads the link's RSSI; unsupported until then.
    override suspend fun readRssi(): RadioResult<Int> = RadioResult.Failed(RadioFailure.UNSUPPORTED)

    override suspend fun send(chunk: ByteArray): Boolean {
        val answer =
            ready?.let { ready ->
                exchange.request {
                    if (isClosed) GattExchange.REFUSED else AndroidGatt.write(ready.gatt, ready.toPeripheral, chunk)
                }
            }
        return answer?.status == BluetoothGatt.GATT_SUCCESS
    }

    override fun release() {
        gatt?.disconnect()
    }

    override fun closed() {
        gatt?.close()
        exchange.close()
        channels.close()
        if (opened) emit(RadioEvent.Disconnected(this, AndroidGatt.losesTransiently(lost)))
    }

    private suspend fun establish(): RadioFailure? {
        val gatt = connect() ?: return RadioFailure.PEER_UNREACHABLE
        // Asks for the largest MTU; a refused or failed exchange keeps the default, and a closed link fails discovery.
        val answer = exchange.request { GattExchange.legacy(gatt.requestMtu(MeshGatt.REQUESTED_ATT_MTU)) }
        mtu = answer?.takeIf { it.status == BluetoothGatt.GATT_SUCCESS }?.number ?: MeshGatt.DEFAULT_ATT_MTU
        return discover(gatt)
    }

    /** Connects; a callback that closed the link before `connectGatt` returned leaves its client to close here. */
    private suspend fun connect(): BluetoothGatt? =
        exchange
            .request {
                val client = AndroidGatt.connect(context, device, callback)
                gatt = client
                when {
                    client == null -> GattExchange.REFUSED
                    isClosed -> GattExchange.STARTED.also { client.close() }
                    else -> GattExchange.STARTED
                }
            }?.let { gatt }

    private suspend fun discover(gatt: BluetoothGatt): RadioFailure? {
        val answer = exchange.request { GattExchange.legacy(gatt.discoverServices()) }
        ready = gatt.getService(MeshGatt.SERVICE)?.let { Ready.of(gatt, it) }
        return when {
            answer?.status != BluetoothGatt.GATT_SUCCESS -> RadioFailure.LINK_CLOSED
            ready == null -> RadioFailure.CAPABILITY_MISSING
            else -> null
        }
    }

    /** The discovered MeshLink service's attributes on [gatt]. */
    private class Ready(
        val gatt: BluetoothGatt,
        val capability: BluetoothGattCharacteristic,
        val toPeripheral: BluetoothGattCharacteristic,
        val toCentral: BluetoothGattCharacteristic,
        val cccd: BluetoothGattDescriptor,
    ) {
        /** Reads the capability record through [exchange], then subscribes to the stream; null if either fails. */
        suspend fun readCapability(exchange: GattExchange): ByteArray? {
            val answer = exchange.request { GattExchange.legacy(gatt.readCharacteristic(capability)) }
            val record = answer?.takeIf { it.status == BluetoothGatt.GATT_SUCCESS }?.bytes
            return record?.takeIf { subscribe(exchange) }
        }

        private suspend fun subscribe(exchange: GattExchange): Boolean {
            val local = gatt.setCharacteristicNotification(toCentral, true)
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val answer = if (local) exchange.request { AndroidGatt.write(gatt, cccd, enable) } else null
            return answer?.status == BluetoothGatt.GATT_SUCCESS
        }

        companion object {
            /** The attributes of [service], or `null` if one is missing. */
            fun of(
                gatt: BluetoothGatt,
                service: BluetoothGattService,
            ): Ready? {
                val capability = service.getCharacteristic(MeshGatt.CAPABILITY)
                val toPeripheral = service.getCharacteristic(MeshGatt.TO_PERIPHERAL)
                val toCentral = service.getCharacteristic(MeshGatt.TO_CENTRAL)
                val cccd = toCentral?.getDescriptor(MeshGatt.CCCD)
                return if (capability == null || toPeripheral == null || cccd == null) {
                    null
                } else {
                    Ready(gatt, capability, toPeripheral, toCentral, cccd)
                }
            }
        }
    }

    /** Answers [exchange] from the stack's callbacks and feeds notifications to the stream. */
    private inner class Callback : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                exchange.answer(GattAnswer(status))
            } else {
                lost = status
                close()
            }
        }

        override fun onMtuChanged(
            gatt: BluetoothGatt,
            mtu: Int,
            status: Int,
        ) {
            exchange.answer(GattAnswer(status, number = mtu))
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int,
        ) {
            exchange.answer(GattAnswer(status))
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            exchange.answer(GattAnswer(status, bytes = value.copyOf()))
        }

        @Deprecated("API 26–32 only; API 33+ calls the overload that carries the value.")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            exchange.answer(GattAnswer(status, bytes = LegacyGatt.value(characteristic)))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            exchange.answer(GattAnswer(status))
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            exchange.answer(GattAnswer(status))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == MeshGatt.TO_CENTRAL) receive(value.copyOf())
        }

        @Deprecated("API 26–32 only; API 33+ calls the overload that carries the value.")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == MeshGatt.TO_CENTRAL) receive(LegacyGatt.value(characteristic))
        }
    }
}
