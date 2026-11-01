package bakeoff.ios

import bakeoff.BakeoffUuids
import bakeoff.ByteLink
import bakeoff.CentralConnection
import bakeoff.CentralDriver
import bakeoff.ConnectRequest
import bakeoff.GattChunkLink
import bakeoff.LinkClosedException
import bakeoff.PhaseClock
import bakeoff.ResultSink
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBAdvertisementDataIsConnectable
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataOverflowServiceUUIDsKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/** CoreBluetooth central on the main queue (queue = null), driven from Dispatchers.Main. */
class IosCentral(private val sink: ResultSink) : CentralDriver {
    private var poweredOn = CompletableDeferred<Unit>()
    private var discovery: CompletableDeferred<CBPeripheral>? = null
    private var excluded: Set<String> = emptySet()
    private val connections = mutableMapOf<String, IosCentralConnection>()

    private val delegate = object : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            sink.log("central state ${central.state}")
            if (central.state == CBManagerStatePoweredOn) poweredOn.complete(Unit)
            else if (poweredOn.isCompleted) poweredOn = CompletableDeferred()
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            survey?.invoke(didDiscoverPeripheral, advertisementData, RSSI)
            if (didDiscoverPeripheral.identifier.UUIDString in excluded) return
            discovery?.complete(didDiscoverPeripheral)
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            connections[didConnectPeripheral.identifier.UUIDString]?.onConnected()
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            connections[didFailToConnectPeripheral.identifier.UUIDString]?.onDisconnected("connect failed ${error?.describe()}")
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            connections.remove(didDisconnectPeripheral.identifier.UUIDString)?.onDisconnected("disconnected ${error?.describe()}")
        }
    }

    private val manager = CBCentralManager(delegate = delegate, queue = null)

    private var survey: ((CBPeripheral, Map<Any?, *>, NSNumber) -> Unit)? = null

    /**
     * Diagnostic scan (no connect): reports every advertiser seen in [seconds], with or without the service
     * filter, and whether the service UUID arrived in the main or the overflow service list.
     */
    suspend fun survey(seconds: Int, filtered: Boolean, emit: (Map<String, Any?>) -> Unit) {
        withTimeoutOrNull(60_000) { poweredOn.await() } ?: throw IllegalStateException("central not powered on")
        val seen = linkedMapOf<String, MutableMap<String, Any?>>()
        val start = TimeSource.Monotonic.markNow()
        survey = { peripheral, data, rssi ->
            val id = peripheral.identifier.UUIDString
            val entry = seen.getOrPut(id) {
                mutableMapOf("identifier" to id, "first_ms" to start.elapsedNow().inWholeMilliseconds, "count" to 0)
            }
            entry["count"] = (entry["count"] as Int) + 1
            entry["rssi"] = rssi.intValue
            fun uuids(key: Any?) = (data[key] as? List<*>)?.mapNotNull { (it as? CBUUID)?.UUIDString }
            uuids(CBAdvertisementDataServiceUUIDsKey)?.let { entry["service_uuids"] = it }
            uuids(CBAdvertisementDataOverflowServiceUUIDsKey)?.let { entry["overflow_service_uuids"] = it }
            (data[CBAdvertisementDataLocalNameKey] as? String)?.let { entry["local_name"] = it }
            (data[CBAdvertisementDataIsConnectable] as? NSNumber)?.let { entry["connectable"] = it.boolValue }
            entry["keys"] = data.keys.map { it.toString() }.sorted()
            peripheral.name?.let { entry["gap_name"] = it }
        }
        manager.scanForPeripheralsWithServices(
            if (filtered) listOf(cbuuid(BakeoffUuids.SERVICE)) else null,
            options = mapOf<Any?, Any?>(CBCentralManagerScanOptionAllowDuplicatesKey to true),
        )
        try {
            delay(seconds * 1000L)
        } finally {
            manager.stopScan()
            survey = null
        }
        seen.values.forEach { entry ->
            val all = (entry["service_uuids"] as? List<*>).orEmpty() + (entry["overflow_service_uuids"] as? List<*>).orEmpty()
            entry["has_bakeoff_service"] = all.any { (it as String).equals(BakeoffUuids.SERVICE, ignoreCase = true) }
            emit(entry)
        }
    }

    override suspend fun connect(request: ConnectRequest, clock: PhaseClock): CentralConnection {
        withTimeoutOrNull(60_000) { poweredOn.await() }
            ?: throw IllegalStateException("central not powered on (state ${manager.state}; Bluetooth permission?)")
        clock.mark("scan_start")
        excluded = request.excludedAddresses
        val found = CompletableDeferred<CBPeripheral>()
        discovery = found
        manager.scanForPeripheralsWithServices(listOf(cbuuid(BakeoffUuids.SERVICE)), options = null)
        val peripheral = try {
            withTimeoutOrNull(request.scanTimeoutMs) { found.await() } ?: throw IllegalStateException("scan timeout")
        } finally {
            manager.stopScan()
            discovery = null
        }
        clock.mark("discovered")
        val connection = IosCentralConnection(manager, peripheral)
        connections[connection.address] = connection
        try {
            connection.open(clock)
        } catch (failure: Throwable) {
            connection.disconnect()
            connections.remove(connection.address)
            throw failure
        }
        return connection
    }
}

class IosCentralConnection(private val manager: CBCentralManager, private val peripheral: CBPeripheral) : CentralConnection {
    private val connected = CompletableDeferred<Unit>()
    private val disconnected = CompletableDeferred<String>()
    private var servicesDiscovered = CompletableDeferred<Unit>()
    private var characteristicsDiscovered = CompletableDeferred<Unit>()
    private var notifyState = CompletableDeferred<Unit>()
    private var pendingRead: CompletableDeferred<ByteArray>? = null
    private var writeReady: CompletableDeferred<Unit>? = null
    private var l2capOpened: CompletableDeferred<CBL2CAPChannel>? = null
    private val readMutex = Mutex()
    private val characteristics = mutableMapOf<String, CBCharacteristic>()
    private val channels = mutableListOf<StreamByteLink>()

    override val address: String = peripheral.identifier.UUIDString
    override var mtu: Int = 23
        private set
    override val isConnected: Boolean get() = connected.isCompleted && !disconnected.isCompleted

    override val gattLink: GattChunkLink = object : GattChunkLink("gatt-central:$address") {
        override fun maxValueSize(): Int = minOf(writeLength(), 512)
        override suspend fun sendValue(value: ByteArray) = writeData(value)
        override fun close(reason: String) = closeInbound(reason)
    }

    private val delegate = object : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (didDiscoverServices != null) servicesDiscovered.completeExceptionally(IllegalStateException(didDiscoverServices.describe()))
            else servicesDiscovered.complete(Unit)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
            if (error != null) characteristicsDiscovered.completeExceptionally(IllegalStateException(error.describe()))
            else characteristicsDiscovered.complete(Unit)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
            val value = didUpdateValueForCharacteristic.value?.toByteArray() ?: ByteArray(0)
            if (didUpdateValueForCharacteristic.UUID.matches(BakeoffUuids.DATA_NOTIFY)) {
                gattLink.onValueReceived(value)
                return
            }
            val read = pendingRead ?: return
            if (error != null) read.completeExceptionally(IllegalStateException(error.describe())) else read.complete(value)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) {
            if (error != null) notifyState.completeExceptionally(IllegalStateException(error.describe())) else notifyState.complete(Unit)
        }

        override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
            writeReady?.complete(Unit)
        }

        override fun peripheral(peripheral: CBPeripheral, didOpenL2CAPChannel: CBL2CAPChannel?, error: NSError?) {
            val waiter = l2capOpened ?: return
            if (didOpenL2CAPChannel == null || error != null) waiter.completeExceptionally(IllegalStateException("l2cap open failed ${error?.describe()}"))
            else waiter.complete(didOpenL2CAPChannel)
        }
    }

    fun onConnected() {
        connected.complete(Unit)
    }

    fun onDisconnected(reason: String) {
        val failure = LinkClosedException(reason)
        connected.completeExceptionally(failure)
        disconnected.complete(reason)
        listOfNotNull(servicesDiscovered, characteristicsDiscovered, notifyState, pendingRead, l2capOpened).forEach {
            it.completeExceptionally(failure)
        }
        writeReady?.complete(Unit)
        gattLink.close(reason)
        channels.forEach { it.close(reason) }
    }

    private fun writeLength(): Int = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse).toInt()

    suspend fun open(clock: PhaseClock) {
        peripheral.delegate = delegate
        manager.connectPeripheral(peripheral, options = null)
        withTimeout(30_000) { connected.await() }
        clock.mark("connected")
        peripheral.discoverServices(listOf(cbuuid(BakeoffUuids.SERVICE)))
        withTimeout(10_000) { servicesDiscovered.await() }
        val service = peripheral.services?.filterIsInstance<CBService>()?.firstOrNull { it.UUID.matches(BakeoffUuids.SERVICE) }
            ?: throw IllegalStateException("service missing")
        peripheral.discoverCharacteristics(null, forService = service)
        withTimeout(10_000) { characteristicsDiscovered.await() }
        service.characteristics?.filterIsInstance<CBCharacteristic>()?.forEach { characteristics[it.UUID.UUIDString.lowercase()] = it }
        clock.mark("services")
        // iOS negotiates the ATT MTU itself during connection setup; report what it settled on.
        mtu = writeLength() + 3
        clock.mark("mtu")
        clock.mark("phy")
        peripheral.setNotifyValue(true, forCharacteristic = characteristic(BakeoffUuids.DATA_NOTIFY))
        withTimeout(10_000) { notifyState.await() }
        clock.mark("subscribed")
        mtu = writeLength() + 3
    }

    private fun characteristic(uuid: String): CBCharacteristic =
        characteristics[uuid.lowercase()] ?: throw IllegalStateException("characteristic $uuid missing")

    private suspend fun writeData(value: ByteArray) {
        val target = characteristic(BakeoffUuids.DATA_WRITE)
        while (!peripheral.canSendWriteWithoutResponse) {
            if (disconnected.isCompleted) throw LinkClosedException(disconnected.getCompleted())
            val waiter = CompletableDeferred<Unit>()
            writeReady = waiter
            withTimeoutOrNull(100) { waiter.await() }
            writeReady = null
        }
        if (disconnected.isCompleted) throw LinkClosedException(disconnected.getCompleted())
        peripheral.writeValue(value.toNSData(), forCharacteristic = target, type = CBCharacteristicWriteWithoutResponse)
    }

    private suspend fun read(uuid: String): ByteArray = readMutex.withLock {
        val waiter = CompletableDeferred<ByteArray>()
        pendingRead = waiter
        try {
            peripheral.readValueForCharacteristic(characteristic(uuid))
            withTimeout(10_000) { waiter.await() }
        } finally {
            pendingRead = null
        }
    }

    override fun phyReport(): String = "unavailable(ios)"

    override suspend fun readControl(): ByteArray = read(BakeoffUuids.CONTROL)

    override suspend fun readPsm(): Int {
        val value = read(BakeoffUuids.PSM)
        if (value.size < 2) throw IllegalStateException("psm value ${value.size} bytes")
        return ((value[0].toInt() and 0xff) shl 8) or (value[1].toInt() and 0xff)
    }

    override suspend fun openL2cap(psm: Int, secure: Boolean): ByteLink {
        // iOS has no per-call security flag: encryption follows the peripheral's channel requirement.
        val waiter = CompletableDeferred<CBL2CAPChannel>()
        l2capOpened = waiter
        try {
            peripheral.openL2CAPChannel(psm.toUShort())
            val channel = withTimeout(15_000) { waiter.await() }
            return StreamByteLink(channel, "l2cap-central:$address:$psm").also { channels += it }
        } finally {
            l2capOpened = null
        }
    }

    override suspend fun disconnect() {
        channels.forEach { it.close("disconnect") }
        channels.clear()
        if (!disconnected.isCompleted) {
            manager.cancelPeripheralConnection(peripheral)
            withTimeoutOrNull(3_000) { disconnected.await() }
        }
        gattLink.close("disconnect")
    }
}
