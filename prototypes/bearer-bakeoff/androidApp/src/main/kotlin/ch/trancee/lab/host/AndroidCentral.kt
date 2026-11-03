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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** AOSP default scan quota is 5 starts per 30 s; pace starts so long attempt loops never hit it. */
private const val MIN_SCAN_START_INTERVAL_MS = 6_500L
private const val BUSY_RETRIES = 80
private const val BUSY_RETRY_DELAY_MS = 25L

@SuppressLint("MissingPermission")
/** Opt-in mitigation knobs for the 147/133 and legacy write-stall investigations (all off by default). */
class CentralTuning(
    val connectDelayMs: Long = 0,
    val connectRetries: Int = 0,
    val writeAckEvery: Int = 0,
    val paceMs: Long = 0,
    val requestMtu: Int = 517,
    val maxChunk: Int = 512,
    /** Energy prototype: `high` (bake-off default), `balanced`, `low_power`, or `none` (no request). */
    val priority: String = "high",
) {
    companion object {
        fun from(raw: Map<String, String>): CentralTuning = CentralTuning(
            connectDelayMs = raw["connect_delay_ms"]?.toLong() ?: 0,
            connectRetries = raw["connect_retries"]?.toInt() ?: 0,
            writeAckEvery = raw["write_ack_every"]?.toInt() ?: 0,
            paceMs = raw["pace_ms"]?.toLong() ?: 0,
            requestMtu = raw["req_mtu"]?.toInt() ?: 517,
            maxChunk = raw["max_chunk"]?.toInt() ?: 512,
            priority = raw["prio"] ?: "high",
        )
    }
}

class AndroidCentral(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val sink: ResultSink,
    private val tuning: CentralTuning = CentralTuning(),
) : CentralDriver {
    private var lastScanStart = 0L

    override suspend fun connect(request: ConnectRequest, clock: PhaseClock): CentralConnection {
        val wait = lastScanStart + MIN_SCAN_START_INTERVAL_MS - SystemClock.elapsedRealtime()
        if (lastScanStart != 0L && wait > 0) delay(wait)
        lastScanStart = SystemClock.elapsedRealtime()
        clock.mark("scan_start")
        val device = withTimeoutOrNull(request.scanTimeoutMs) { scan(request.excludedAddresses) }
            ?: throw IllegalStateException("scan timeout")
        clock.mark("discovered")
        if (tuning.connectDelayMs > 0) delay(tuning.connectDelayMs)
        var retry = 0
        while (true) {
            val connection = AndroidCentralConnection(context, adapter, device, sink, tuning)
            try {
                connection.open(request.phy, clock)
                return connection
            } catch (failure: Throwable) {
                connection.disconnect()
                val retryable = failure is IllegalStateException && failure.message?.startsWith("disconnected status=") == true
                if (!retryable || retry >= tuning.connectRetries) throw failure
                retry++
                sink.log("connect retry $retry after ${failure.message}")
                clock.mark("retry${retry}_${failure.message?.substringAfter("status=")}")
                delay(500L * retry)
            }
        }
    }

    private suspend fun scan(excluded: Set<String>): BluetoothDevice {
        val scanner = adapter.bluetoothLeScanner ?: throw IllegalStateException("no LE scanner")
        val found = CompletableDeferred<BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address !in excluded) found.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(IllegalStateException("scan failed $errorCode"))
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(BakeoffUuids.SERVICE))).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // Every startScan is paired with stopScan (result, failure, timeout, cancellation); a failed or late
        // registration otherwise leaks a scanner slot in the stack (observed on the XCover 4, Android 9).
        try {
            scanner.startScan(listOf(filter), settings, callback)
            return found.await()
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    /**
     * Diagnostic scan (no connect): reports every advertiser carrying the bake-off service seen in [seconds],
     * with or without a service filter, plus the total number of distinct advertisers.
     */
    suspend fun survey(seconds: Int, filtered: Boolean, emit: (Map<String, Any?>) -> Unit): Int {
        val scanner = adapter.bluetoothLeScanner ?: throw IllegalStateException("no LE scanner")
        val service = ParcelUuid(UUID.fromString(BakeoffUuids.SERVICE))
        val start = SystemClock.elapsedRealtime()
        val seen = linkedMapOf<String, MutableMap<String, Any?>>()
        val failed = CompletableDeferred<Int>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                synchronized(seen) {
                    val entry = seen.getOrPut(result.device.address) {
                        mutableMapOf("identifier" to result.device.address, "first_ms" to SystemClock.elapsedRealtime() - start, "count" to 0)
                    }
                    entry["count"] = (entry["count"] as Int) + 1
                    entry["rssi"] = result.rssi
                    entry["connectable"] = result.isConnectable
                    entry["has_bakeoff_service"] = result.scanRecord?.serviceUuids?.contains(service) == true
                }
            }

            override fun onScanFailed(errorCode: Int) {
                failed.complete(errorCode)
            }
        }
        val filters = if (filtered) listOf(ScanFilter.Builder().setServiceUuid(service).build()) else emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, callback)
            withTimeoutOrNull(seconds * 1000L) { failed.await() }?.let { throw IllegalStateException("scan failed $it") }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        val entries = synchronized(seen) { seen.values.map { it.toMap() } }
        entries.filter { it["has_bakeoff_service"] == true }.forEach(emit)
        return entries.size
    }
}

@SuppressLint("MissingPermission")
class AndroidCentralConnection(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
    private val sink: ResultSink,
    private val tuning: CentralTuning,
) : CentralConnection {
    private val opMutex = Mutex()
    private var writesCompleted = 0
    private var busyRetriesTotal = 0
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
        override fun maxValueSize(): Int = minOf(mtu - 3, 512, tuning.maxChunk)
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

    /** Busy retries per operation name, surfaced as `busy_<name>_x<n>` timing marks. */
    private val busyCounts = mutableMapOf<String, Int>()

    /** Reads BluetoothGatt.mDeviceBusy (diagnostic only; null if hidden-API access is refused). */
    private fun deviceBusy(gatt: BluetoothGatt): Boolean? = runCatching {
        val field = BluetoothGatt::class.java.getDeclaredField("mDeviceBusy")
        field.isAccessible = true
        field.get(gatt) as? Boolean
    }.getOrNull()

    /**
     * Runs one GATT operation at a time; Android drops a second request while one is outstanding.
     * A false return from the start call (stack busy) is retried like writes, up to [busyRetries] times.
     */
    private suspend fun <T> operation(
        name: String,
        timeoutMs: Long = 10_000,
        busyRetries: Int = BUSY_RETRIES,
        start: (BluetoothGatt) -> Boolean,
    ): T {
        val currentGatt = gatt ?: throw LinkClosedException("not connected")
        return opMutex.withLock {
            if (disconnected.isCompleted) throw LinkClosedException(disconnected.getCompleted())
            val deferred = CompletableDeferred<Any?>()
            pending = deferred
            try {
                var retries = 0
                while (!start(currentGatt)) {
                    if (disconnected.isCompleted) throw LinkClosedException(disconnected.getCompleted())
                    if (retries >= busyRetries) {
                        val detail = if (busyRetries > 0) " after $retries busy retries (mDeviceBusy=${deviceBusy(currentGatt)})" else ""
                        throw IllegalStateException("$name not started$detail")
                    }
                    if (retries == 0) sink.log("$name not started, mDeviceBusy=${deviceBusy(currentGatt)}; retrying")
                    retries++
                    delay(BUSY_RETRY_DELAY_MS)
                }
                if (retries > 0) busyCounts[name] = (busyCounts[name] ?: 0) + retries
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
        // Initiating PHYs must include 1M (legacy adverts are on 1M); 2M is requested after connection via setPreferredPhy.
        val initiatingMask = BluetoothDevice.PHY_LE_1M_MASK or (if (phy == Phy.LE_2M) BluetoothDevice.PHY_LE_2M_MASK else 0)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, initiatingMask)
        withTimeout(30_000) { connected.await() }
        clock.mark("connected")
        operation<Unit>("discover") { it.discoverServices() }
        service = gatt?.getService(UUID.fromString(BakeoffUuids.SERVICE)) ?: throw IllegalStateException("service missing")
        clock.mark("services")
        runCatching { operation<Int>("mtu", 5_000) { it.requestMtu(tuning.requestMtu) } }
        clock.mark("mtu")
        when (tuning.priority) {
            "high" -> gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            "balanced" -> gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
            "low_power" -> gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
        }
        // LE PHY HCI commands need a BT 5 controller. On the P10 lite (Android 8.0, BT 4.2) readPhy() aborts the
        // Bluetooth process (gatt_api.cc "Assert failed: len == 5"), so skip PHY commands when 2M is unsupported.
        if (adapter.isLe2MPhySupported) {
            if (phy != Phy.DEFAULT) {
                phyUpdate = CompletableDeferred()
                gatt?.setPreferredPhy(phyMask, phyMask, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                withTimeoutOrNull(2_000) { phyUpdate.await() }
            }
            runCatching { operation<Unit>("readPhy", 3_000) { it.readPhy(); true } }
        } else {
            clock.mark("phy_commands_skipped")
        }
        clock.mark("phy")
        val notify = service.getCharacteristic(UUID.fromString(BakeoffUuids.DATA_NOTIFY))
        gatt?.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(UUID.fromString(BakeoffUuids.CCCD))
        operation<Unit>("cccd") { writeDescriptor(it, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
        clock.mark("subscribed")
        busyCounts.forEach { (op, n) -> clock.mark("busy_${op}_x$n") }
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
            val writeType = if (tuning.writeAckEvery > 0 && (writesCompleted + 1) % tuning.writeAckEvery == 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            val started = runCatching {
                operation<Unit>("write", 5_000, busyRetries = 0) { gatt ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
                    } else {
                        characteristic.writeType = writeType
                        characteristic.value = value
                        gatt.writeCharacteristic(characteristic)
                    }
                }
            }
            val failure = started.exceptionOrNull()
            if (failure == null) {
                writesCompleted++
                if (tuning.paceMs > 0) delay(tuning.paceMs)
                if (writesCompleted % 64 == 0) sink.log("central writes completed=$writesCompleted busy_retries_total=$busyRetriesTotal")
                return
            }
            busyRetriesTotal++
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
