package ch.trancee.lab.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import bakeoff.BakeoffUuids
import bakeoff.GattChunkLink
import bakeoff.LinkClosedException
import bakeoff.PeerIdentity
import bakeoff.PeripheralDriver
import bakeoff.PeripheralListener
import bakeoff.ResultSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

@SuppressLint("MissingPermission")
class AndroidPeripheral(
    private val context: Context,
    private val manager: BluetoothManager,
    private val adapter: BluetoothAdapter,
    private val sink: ResultSink,
) : PeripheralDriver {
    private var server: BluetoothGattServer? = null
    private var serverSocket: BluetoothServerSocket? = null
    private lateinit var listener: PeripheralListener
    private lateinit var control: ByteArray
    private var psmValue = ByteArray(2)
    private val links = ConcurrentHashMap<String, ServerLink>()
    private val mtus = ConcurrentHashMap<String, Int>()
    private var writesReceived = 0
    private val notifyMutex = Mutex()
    private var pendingNotify: CompletableDeferred<Int>? = null
    private val serviceAdded = CompletableDeferred<Int>()
    private val advertiseStarted = CompletableDeferred<Int>()

    private val notifyCharacteristic = BluetoothGattCharacteristic(
        UUID.fromString(BakeoffUuids.DATA_NOTIFY),
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ,
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                UUID.fromString(BakeoffUuids.CCCD),
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
    }

    private inner class ServerLink(val device: BluetoothDevice) : GattChunkLink("gatt-peripheral:${device.address}") {
        @Volatile
        var mtu: Int = mtus[device.address] ?: 23

        override fun maxValueSize(): Int = minOf(mtu - 3, 512)

        override suspend fun sendValue(value: ByteArray) = notify(device, value)

        override fun close(reason: String) = closeInbound(reason)
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            serviceAdded.complete(status)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            listener.onEvent("connection_state", device.address, "status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mtus.remove(device.address)
                links.remove(device.address)?.close("disconnected status=$status")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtus[device.address] = mtu
            links[device.address]?.mtu = mtu
            listener.onEvent("mtu", device.address, "$mtu")
        }

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            listener.onEvent("phy_update", device.address, "tx=${phyName(txPhy)},rx=${phyName(rxPhy)},status=$status")
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val value = when (characteristic.uuid.toString()) {
                BakeoffUuids.CONTROL -> control
                BakeoffUuids.PSM -> psmValue
                else -> ByteArray(0)
            }
            val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (characteristic.uuid.toString() == BakeoffUuids.DATA_WRITE) {
                val link = links[device.address]
                if (link == null) listener.onEvent("write_without_link", device.address, "${value.size} bytes")
                link?.onValueReceived(value)
                writesReceived++
                if (writesReceived % 64 == 0) sink.log("writes received=$writesReceived last_seq=${value.firstOrNull()?.toInt()?.and(0xff)}")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            listener.onEvent("cccd", device.address, value.joinToString("") { "%02x".format(it) })
            // The link starts at subscription: the server callback also reports ACLs this node opened as central.
            if (value.isNotEmpty() && value[0].toInt() != 0) {
                // Holds the inbound ACL so a central's client close does not tear down the server side.
                server?.connect(device, false)
                val link = ServerLink(device)
                links.put(device.address, link)?.close("replaced")
                listener.onGattLink(device.address, link)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            pendingNotify?.complete(status)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun notify(device: BluetoothDevice, value: ByteArray) {
        notifyMutex.withLock {
            var busyRetries = 0
            while (true) {
                if (!links.containsKey(device.address)) throw LinkClosedException("peer gone")
                val deferred = CompletableDeferred<Int>()
                pendingNotify = deferred
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server?.notifyCharacteristicChanged(device, notifyCharacteristic, false, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    notifyCharacteristic.value = value
                    server?.notifyCharacteristicChanged(device, notifyCharacteristic, false) == true
                }
                if (!started) {
                    if (busyRetries++ > 200) throw IllegalStateException("notify not started")
                    delay(5)
                    continue
                }
                val status = withTimeout(5_000) { deferred.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("notify status $status")
                return
            }
        }
    }

    override suspend fun start(identity: PeerIdentity, secureL2cap: Boolean, listener: PeripheralListener): Int? {
        this.listener = listener
        control = identity.encodeControl()
        val gattServer = manager.openGattServer(context, callback) ?: throw IllegalStateException("openGattServer null")
        server = gattServer
        val psm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startL2capServer(secureL2cap) else null
        psmValue = byteArrayOf(((psm ?: 0) shr 8).toByte(), (psm ?: 0).toByte())
        val service = BluetoothGattService(UUID.fromString(BakeoffUuids.SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(UUID.fromString(BakeoffUuids.CONTROL), BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        service.addCharacteristic(notifyCharacteristic)
        service.addCharacteristic(BluetoothGattCharacteristic(UUID.fromString(BakeoffUuids.DATA_WRITE), BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        service.addCharacteristic(BluetoothGattCharacteristic(UUID.fromString(BakeoffUuids.PSM), BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        gattServer.addService(service)
        val status = withTimeout(5_000) { serviceAdded.await() }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("addService status $status")
        startAdvertising()
        return psm
    }

    private fun startL2capServer(secure: Boolean): Int {
        val socket = if (secure) adapter.listenUsingL2capChannel() else adapter.listenUsingInsecureL2capChannel()
        serverSocket = socket
        thread(name = "l2cap-accept", isDaemon = true) {
            while (true) {
                val accepted = try {
                    socket.accept()
                } catch (failure: Exception) {
                    sink.log("l2cap accept ended: ${failure.message}")
                    break
                }
                val address = accepted.remoteDevice.address
                listener.onEvent("l2cap_accept", address, "psm=${socket.psm}")
                listener.onL2capLink(address, SocketByteLink.accepted(accepted, "l2cap-peripheral:$address"))
            }
        }
        return socket.psm
    }

    private suspend fun startAdvertising() {
        val advertiser = adapter.bluetoothLeAdvertiser ?: throw IllegalStateException("advertising unsupported")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(BakeoffUuids.SERVICE)))
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
        val result = withTimeout(5_000) { advertiseStarted.await() }
        if (result != 0) throw IllegalStateException("advertise failed $result")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertiseStarted.complete(0)
        }

        override fun onStartFailure(errorCode: Int) {
            advertiseStarted.complete(errorCode)
        }
    }

    override fun stop() {
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { serverSocket?.close() }
        links.values.forEach { it.close("stopped") }
        runCatching { server?.close() }
    }
}
