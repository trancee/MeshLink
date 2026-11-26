package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves the MeshLink GATT service (spec D-13) as peripheral. A central's link begins when it
 * enables notifications on the peripheral-to-central characteristic, which it does after reading the
 * capability record, and ends when it disconnects. Notifications are serialized across the server:
 * each waits for `onNotificationSent` (library rule R14). Opening the server retries (rule R13).
 */
internal class GattServerHost(
    private val context: Context,
    private val manager: BluetoothManager,
    private val scope: CoroutineScope,
    private val emit: (RadioEvent) -> Unit,
) {
    @Volatile private var record = ByteArray(0)

    @Volatile private var served: Served? = null

    private var opening: Job? = null
    private val links = ConcurrentHashMap<BluetoothDevice, PeripheralLink>()
    private val mtus = ConcurrentHashMap<BluetoothDevice, Int>()
    private val callback = Callback()

    /** Serves [record] to every central from now on, opening the server if it is closed. */
    fun serve(record: ByteArray): RadioResult<Unit> {
        if (record.size > RadioPort.CAPABILITY_RECORD_MAX) return RadioResult.Failed(RadioFailure.INVALID_ARGUMENT)
        this.record = record.copyOf()
        if (served == null && opening?.isActive != true && !open()) {
            opening = scope.launch { retryOpen() }
        }
        return RadioResult.Ok(Unit)
    }

    /** Closes the server and every link, as when Bluetooth turns off; [serve] opens it again. */
    fun close() {
        opening?.cancel()
        served?.let {
            it.server.close()
            it.notifications.close()
        }
        served = null
        links.values.forEach { it.close() }
        mtus.clear()
    }

    private fun open(): Boolean {
        val server = manager.openGattServer(context, callback) ?: return false
        val service = MeshGatt.service()
        served = Served(server, service.getCharacteristic(MeshGatt.TO_CENTRAL), emit)
        server.addService(service)
        return true
    }

    private suspend fun retryOpen() {
        val opened = OPEN_RETRY_MILLIS.any { wait -> delay(wait).let { open() } }
        if (!opened) emit(RadioEvent.ServeFailed(RadioFailure.RADIO_ERROR))
    }

    private fun respond(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ) {
        served?.let { it.server.sendResponse(device, requestId, status, offset, value) }
    }

    /** An open server, its peripheral-to-central characteristic and its notifications in flight. */
    private class Served(
        val server: BluetoothGattServer,
        val toCentral: BluetoothGattCharacteristic,
        emit: (RadioEvent) -> Unit,
    ) {
        val notifications = GattExchange(emit)
    }

    /** One central's link to this peripheral, on the server [served] that was open when it began. */
    private inner class PeripheralLink(
        private val device: BluetoothDevice,
        private val served: Served,
    ) : StreamLink(AndroidPeer(device)) {
        private val toCentral = served.toCentral

        override val attMtu: Int get() = mtus[device] ?: MeshGatt.DEFAULT_ATT_MTU

        override suspend fun send(chunk: ByteArray): Boolean {
            val answer =
                served.notifications.request(owner = this) {
                    if (isClosed) GattExchange.REFUSED else AndroidGatt.notify(served.server, device, toCentral, chunk)
                }
            return answer?.status == BluetoothGatt.GATT_SUCCESS
        }

        override fun release() {
            served.server.cancelConnection(device)
        }

        override fun closed() {
            served.notifications.abandon(this)
            links.remove(device, this)
            emit(RadioEvent.Disconnected(this))
        }
    }

    private inner class Callback : BluetoothGattServerCallback() {
        override fun onServiceAdded(
            status: Int,
            service: BluetoothGattService,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) emit(RadioEvent.ServeFailed(RadioFailure.RADIO_ERROR, status))
        }

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int,
        ) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mtus.remove(device)
                links[device]?.close(AndroidGatt.platformCode(status))
            }
        }

        override fun onMtuChanged(
            device: BluetoothDevice,
            mtu: Int,
        ) {
            mtus[device] = mtu
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = record
            if (characteristic.uuid == MeshGatt.CAPABILITY && offset <= value.size) {
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            } else {
                respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == MeshGatt.TO_PERIPHERAL) links[device]?.receive(value)
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val enable = descriptor.uuid == MeshGatt.CCCD && value.contentEquals(ENABLE_NOTIFICATION_VALUE)
            served?.takeIf { enable && !links.containsKey(device) }?.let { current ->
                val link = PeripheralLink(device, current)
                links[device] = link
                emit(RadioEvent.Connected(link))
            }
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        override fun onNotificationSent(
            device: BluetoothDevice,
            status: Int,
        ) {
            served?.let { it.notifications.answer(GattAnswer(status)) }
        }
    }
}

/** The waits before each retry of `openGattServer`, which returns null while the stack starts. */
private val OPEN_RETRY_MILLIS = listOf(250L, 500L, 1_000L, 2_000L)
