package ch.trancee.lab.host

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import bakeoff.BakeoffUuids
import bakeoff.ByteLink
import bakeoff.CentralConnection
import bakeoff.CentralDriver
import bakeoff.ConnectRequest
import bakeoff.GattChunkLink
import bakeoff.LinkClosedException
import bakeoff.PhaseClock
import bakeoff.Phy
import bakeoff.ResultSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/** AOSP default scan quota is 5 starts per 30 s; pace starts so long attempt loops never hit it. */
private const val MIN_SCAN_START_INTERVAL_MS = 6_500L

@SuppressLint("MissingPermission")
class AndroidCentral(private val context: Context, private val adapter: BluetoothAdapter, private val sink: ResultSink) : CentralDriver {
    private var lastScanStart = 0L

    override suspend fun connect(request: ConnectRequest, clock: PhaseClock): CentralConnection {
        val wait = lastScanStart + MIN_SCAN_START_INTERVAL_MS - SystemClock.elapsedRealtime()
        if (lastScanStart != 0L && wait > 0) delay(wait)
        lastScanStart = SystemClock.elapsedRealtime()
        clock.mark("scan_start")
        val device = withTimeoutOrNull(request.scanTimeoutMs) { scan(request.excludedAddresses) }
            ?: throw IllegalStateException("scan timeout")
        clock.mark("discovered")
        val connection = AndroidCentralConnection(context, adapter, device, sink)
        try {
            connection.open(request.phy, clock)
        } catch (failure: Throwable) {
            connection.disconnect()
            throw failure
        }
        return connection
    }

    private suspend fun scan(excluded: Set<String>): BluetoothDevice = suspendCancellableCoroutine { continuation ->
        val scanner = adapter.bluetoothLeScanner ?: throw IllegalStateException("no LE scanner")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address in excluded || !continuation.isActive) return
                runCatching { scanner.stopScan(this) }
                continuation.resume(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("scan failed $errorCode")))
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(BakeoffUuids.SERVICE))).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        continuation.invokeOnCancellation { runCatching { scanner.stopScan(callback) } }
    }
}

@SuppressLint("MissingPermission")
class AndroidCentralConnection(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
    private val sink: ResultSink,
) : CentralConnection {
    private val opMutex = Mutex()
    private var pending: CompletableDeferred<Any?>? = null
    private val connected = CompletableDeferred<Unit>()
    private val disconnected = CompletableDeferred<String>()
    private var phyUpdate = CompletableDeferred<Unit>()
    private var gatt: BluetoothGatt? = null
    private var txPhy = 0
    private var rxPhy = 0
    private var phyEvents = 0
    private lateinit var service: android.bluetooth.BluetoothGattService

    override val address: String = device.address
    override var mtu: Int = 23
        private set
    override val isConnected: Boolean get() = connected.isCompleted && !disconnected.isCompleted

    override val gattLink: GattChunkLink = object : GattChunkLink("gatt-central:${device.address}") {
        override fun maxValueSize(): Int = minOf(mtu - 3, 512)
        override suspend fun sendValue(value: ByteArray) = writeData(value)
        override fun close(reason: String) = closeInbound(reason)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val reason = "disconnected status=$status"
                connected.completeExceptionally(IllegalStateException(reason))
                disconnected.complete(reason)
                pending?.completeExceptionally(LinkClosedException(reason))
                gattLink.close(reason)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = complete(status, Unit)

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@AndroidCentralConnection.mtu = mtu
            complete(status, mtu)
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            recordPhy(txPhy, rxPhy)
            phyUpdate.complete(Unit)
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            recordPhy(txPhy, rxPhy)
            complete(status, Unit)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = complete(status, Unit)

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = complete(status, Unit)

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            complete(status, value)

        @Deprecated("API < 33 path")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) complete(status, characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            gattLink.onValueReceived(value)
        }

        @Deprecated("API < 33 path")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) gattLink.onValueReceived(characteristic.value)
        }
    }

    private fun recordPhy(tx: Int, rx: Int) {
        txPhy = tx
        rxPhy = rx
        phyEvents++
    }

    private fun complete(status: Int, value: Any?) {
        val deferred = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) deferred.complete(value) else deferred.completeExceptionally(IllegalStateException("gatt status $status"))
    }

    /** Runs one GATT operation at a time; Android drops a second request while one is outstanding. */
    private suspend fun <T> operation(name: String, timeoutMs: Long = 10_000, start: (BluetoothGatt) -> Boolean): T {
        val currentGatt = gatt ?: throw LinkClosedException("not connected")
        return opMutex.withLock {
            if (disconnected.isCompleted) throw LinkClosedException(disconnected.getCompleted())
            val deferred = CompletableDeferred<Any?>()
            pending = deferred
            try {
                if (!start(currentGatt)) throw IllegalStateException("$name not started")
                @Suppress("UNCHECKED_CAST")
                withTimeout(timeoutMs) { deferred.await() } as T
            } finally {
                pending = null
            }
        }
    }

    suspend fun open(phy: Phy, clock: PhaseClock) {
        val phyMask = when (phy) {
            Phy.LE_2M -> BluetoothDevice.PHY_LE_2M_MASK
            else -> BluetoothDevice.PHY_LE_1M_MASK
        }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, phyMask)
        withTimeout(30_000) { connected.await() }
        clock.mark("connected")
        operation<Unit>("discover") { it.discoverServices() }
        service = gatt?.getService(UUID.fromString(BakeoffUuids.SERVICE)) ?: throw IllegalStateException("service missing")
        clock.mark("services")
        runCatching { operation<Int>("mtu", 5_000) { it.requestMtu(517) } }
        clock.mark("mtu")
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (phy != Phy.DEFAULT) {
            phyUpdate = CompletableDeferred()
            gatt?.setPreferredPhy(phyMask, phyMask, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
            withTimeoutOrNull(2_000) { phyUpdate.await() }
        }
        runCatching { operation<Unit>("readPhy", 3_000) { it.readPhy(); true } }
        clock.mark("phy")
        val notify = service.getCharacteristic(UUID.fromString(BakeoffUuids.DATA_NOTIFY))
        gatt?.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(UUID.fromString(BakeoffUuids.CCCD))
        operation<Unit>("cccd") { writeDescriptor(it, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
        clock.mark("subscribed")
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }

    @Suppress("DEPRECATION")
    private suspend fun writeData(value: ByteArray) {
        val characteristic = service.getCharacteristic(UUID.fromString(BakeoffUuids.DATA_WRITE))
        var busyRetries = 0
        while (true) {
            val started = runCatching {
                operation<Unit>("write", 5_000) { gatt ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
                    } else {
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        characteristic.value = value
                        gatt.writeCharacteristic(characteristic)
                    }
                }
            }
            val failure = started.exceptionOrNull() ?: return
            if (failure is IllegalStateException && failure.message == "write not started" && busyRetries++ < 200) {
                delay(5)
                continue
            }
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun read(uuid: String): ByteArray {
        val characteristic = service.getCharacteristic(UUID.fromString(uuid))
        return operation("read") { it.readCharacteristic(characteristic) }
    }

    override fun phyReport(): String {
        val supports2m = adapter.isLe2MPhySupported
        return if (phyEvents == 0) "unknown(2m_supported=$supports2m)" else "tx=${phyName(txPhy)},rx=${phyName(rxPhy)},2m_supported=$supports2m"
    }

    override suspend fun readControl(): ByteArray = read(BakeoffUuids.CONTROL)

    override suspend fun readPsm(): Int {
        val value = read(BakeoffUuids.PSM)
        if (value.size < 2) throw IllegalStateException("psm value ${value.size} bytes")
        return ((value[0].toInt() and 0xff) shl 8) or (value[1].toInt() and 0xff)
    }

    override suspend fun openL2cap(psm: Int, secure: Boolean): ByteLink {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw UnsupportedOperationException("L2CAP CoC needs API 29")
        val socket = if (secure) device.createL2capChannel(psm) else device.createInsecureL2capChannel(psm)
        return SocketByteLink.connect(socket, "l2cap-central:${device.address}:$psm")
    }

    override suspend fun disconnect() {
        val currentGatt = gatt ?: return
        if (!disconnected.isCompleted) {
            currentGatt.disconnect()
            withTimeoutOrNull(3_000) { disconnected.await() }
        }
        currentGatt.close()
        gatt = null
        gattLink.close("disconnect")
    }
}

fun phyName(phy: Int): String = when (phy) {
    BluetoothDevice.PHY_LE_1M -> "1M"
    BluetoothDevice.PHY_LE_2M -> "2M"
    BluetoothDevice.PHY_LE_CODED -> "Coded"
    else -> "phy$phy"
}
