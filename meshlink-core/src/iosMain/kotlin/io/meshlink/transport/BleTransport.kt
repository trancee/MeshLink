@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.meshlink.transport

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.refTo
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerStatePoweredOn
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSLog
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlinx.cinterop.ObjCSignatureOverride
import kotlin.coroutines.resume

// -- NSData <-> ByteArray conversion extensions --

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return result
}

private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

private fun ByteArray.toHexString(): String =
    joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }

// -- Peer tracking for timeout detection --

private data class TrackedPeer(
    val peerId: ByteArray,
    val peripheral: CBPeripheral,
    var lastSeenMs: Long,
)

/**
 * iOS BLE transport implementation using CoreBluetooth.
 *
 * Acts as both a BLE peripheral (advertising the MeshLink GATT service)
 * and a BLE central (scanning for other MeshLink peripherals).
 */
class IosBleTransport(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : BleTransport {

    companion object {
        private const val TAG = "BleTransport"
        private const val PEER_TIMEOUT_MS = 10_000L
        private const val PEER_SWEEP_INTERVAL_MS = 3_000L
        private const val USER_DEFAULTS_PEER_ID_KEY = "io.meshlink.localPeerId"
    }

    // -- GATT UUIDs --

    private val serviceUUID = CBUUID.UUIDWithString(GattConstants.SERVICE_UUID)
    private val controlWriteUUID = CBUUID.UUIDWithString(GattConstants.CONTROL_WRITE_UUID)
    private val controlNotifyUUID = CBUUID.UUIDWithString(GattConstants.CONTROL_NOTIFY_UUID)
    private val dataWriteUUID = CBUUID.UUIDWithString(GattConstants.DATA_WRITE_UUID)
    private val dataNotifyUUID = CBUUID.UUIDWithString(GattConstants.DATA_NOTIFY_UUID)

    // -- Local peer ID --

    override val localPeerId: ByteArray = loadOrGeneratePeerId()

    // -- CoreBluetooth managers (initialized on start) --

    private var centralManager: CBCentralManager? = null
    private var peripheralManager: CBPeripheralManager? = null

    // -- Event flows --

    private val _advertisementEvents = MutableSharedFlow<AdvertisementEvent>(extraBufferCapacity = 64)
    override val advertisementEvents: Flow<AdvertisementEvent> = _advertisementEvents.asSharedFlow()

    private val _peerLostEvents = MutableSharedFlow<PeerLostEvent>(extraBufferCapacity = 64)
    override val peerLostEvents: Flow<PeerLostEvent> = _peerLostEvents.asSharedFlow()

    private val _incomingData = MutableSharedFlow<IncomingData>(extraBufferCapacity = 64)
    override val incomingData: Flow<IncomingData> = _incomingData.asSharedFlow()

    // -- Peer tracking --

    private val knownPeers = mutableMapOf<String, TrackedPeer>() // keyed by peripheral identifier
    private var peerSweepJob: Job? = null

    // -- Connection state for writing to peers --

    private val connectedPeripherals = mutableMapOf<String, CBPeripheral>() // peerId hex -> peripheral
    private val discoveredWriteCharacteristics = mutableMapOf<String, CBCharacteristic>() // peripheral UUID -> char

    // -- Peripheral manager state --

    private var peripheralManagerReady = false
    private var centralManagerReady = false
    private var gattService: CBMutableService? = null

    // -- Delegates (must be retained as strong references) --

    private val centralDelegate = CentralDelegate()
    private val peripheralMgrDelegate = PeripheralManagerDelegate()
    private val peripheralDelegates = mutableMapOf<String, PeripheralDelegate>()

    // ========================
    // BleTransport interface
    // ========================

    override suspend fun startAdvertisingAndScanning() {
        NSLog("$TAG: Starting advertising and scanning")

        centralManager = CBCentralManager(
            delegate = centralDelegate,
            queue = dispatch_get_main_queue(),
        )
        peripheralManager = CBPeripheralManager(
            delegate = peripheralMgrDelegate,
            queue = dispatch_get_main_queue(),
        )

        startPeerSweep()
    }

    override suspend fun stopAll() {
        NSLog("$TAG: Stopping all BLE activity")

        peerSweepJob?.cancel()
        peerSweepJob = null

        centralManager?.stopScan()
        for ((_, peer) in knownPeers) {
            if (peer.peripheral.state == CBPeripheralStateConnected) {
                centralManager?.cancelPeripheralConnection(peer.peripheral)
            }
        }

        peripheralManager?.stopAdvertising()
        gattService?.let { peripheralManager?.removeService(it) }

        knownPeers.clear()
        connectedPeripherals.clear()
        discoveredWriteCharacteristics.clear()
        peripheralDelegates.clear()
        peripheralManagerReady = false
        centralManagerReady = false
        centralManager = null
        peripheralManager = null
    }

    override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray) {
        val peerIdHex = peerId.toHexString()
        NSLog("$TAG: sendToPeer $peerIdHex, ${data.size} bytes")

        val peripheral = connectedPeripherals[peerIdHex]
            ?: findPeripheralForPeer(peerId)
            ?: run {
                NSLog("$TAG: No peripheral found for peer $peerIdHex")
                return
            }

        // Connect and discover if not already connected
        if (peripheral.state != CBPeripheralStateConnected) {
            connectAndDiscover(peripheral)
        }

        val peripheralUUID = peripheral.identifier.UUIDString
        val writeChar = discoveredWriteCharacteristics[peripheralUUID]
            ?: run {
                NSLog("$TAG: No write characteristic found for peripheral $peripheralUUID")
                return
            }

        peripheral.writeValue(
            data.toNSData(),
            forCharacteristic = writeChar,
            type = CBCharacteristicWriteWithoutResponse,
        )
    }

    // ========================
    // Peer ID persistence
    // ========================

    private fun loadOrGeneratePeerId(): ByteArray {
        val defaults = NSUserDefaults.standardUserDefaults
        val existing = defaults.stringForKey(USER_DEFAULTS_PEER_ID_KEY)

        if (existing != null && existing.length == 32) {
            return existing.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        val uuid = NSUUID()
        val uuidUBytes = UByteArray(16)
        uuidUBytes.usePinned { pinned ->
            uuid.getUUIDBytes(pinned.addressOf(0))
        }
        val uuidBytes = uuidUBytes.toByteArray()
        val hexString = uuidBytes.toHexString()
        defaults.setObject(hexString, forKey = USER_DEFAULTS_PEER_ID_KEY)
        defaults.synchronize()

        NSLog("$TAG: Generated new peer ID: $hexString")
        return uuidBytes
    }

    // ========================
    // Peer sweep (timeout detection)
    // ========================

    private fun startPeerSweep() {
        peerSweepJob = scope.launch {
            while (isActive) {
                delay(PEER_SWEEP_INTERVAL_MS)
                sweepTimedOutPeers()
            }
        }
    }

    private fun sweepTimedOutPeers() {
        val now = io.meshlink.util.currentTimeMillis()
        val timedOut = knownPeers.entries.filter { (_, peer) ->
            now - peer.lastSeenMs > PEER_TIMEOUT_MS
        }
        for ((key, peer) in timedOut) {
            NSLog("$TAG: Peer timed out: ${peer.peerId.toHexString()}")
            knownPeers.remove(key)
            connectedPeripherals.values.removeAll { it.identifier.UUIDString == key }
            _peerLostEvents.tryEmit(PeerLostEvent(peer.peerId))
        }
    }

    // ========================
    // Peripheral lookup for sendToPeer
    // ========================

    private fun findPeripheralForPeer(peerId: ByteArray): CBPeripheral? {
        val peerIdHex = peerId.toHexString()
        return knownPeers.values.firstOrNull {
            it.peerId.toHexString() == peerIdHex
        }?.peripheral
    }

    private suspend fun connectAndDiscover(peripheral: CBPeripheral) {
        val cm = centralManager ?: return
        suspendCancellableCoroutine { cont ->
            val delegate = getOrCreatePeripheralDelegate(peripheral) {
                if (cont.isActive) cont.resume(Unit)
            }
            peripheral.delegate = delegate
            cm.connectPeripheral(peripheral, options = null)
        }
    }

    private fun getOrCreatePeripheralDelegate(
        peripheral: CBPeripheral,
        onReady: (() -> Unit)? = null,
    ): PeripheralDelegate {
        val id = peripheral.identifier.UUIDString
        return peripheralDelegates.getOrPut(id) {
            PeripheralDelegate(onReady)
        }.also {
            it.onReady = onReady
        }
    }

    // ========================
    // GATT Service setup
    // ========================

    private fun setupGattService() {
        val controlWriteChar = CBMutableCharacteristic(
            type = controlWriteUUID,
            properties = CBCharacteristicPropertyWrite,
            value = null,
            permissions = 0x02u, // CBAttributePermissionsWriteable
        )

        val controlNotifyChar = CBMutableCharacteristic(
            type = controlNotifyUUID,
            properties = CBCharacteristicPropertyRead or CBCharacteristicPropertyNotify,
            value = null,
            permissions = 0x01u, // CBAttributePermissionsReadable
        )

        val dataWriteChar = CBMutableCharacteristic(
            type = dataWriteUUID,
            properties = CBCharacteristicPropertyWriteWithoutResponse,
            value = null,
            permissions = 0x02u, // CBAttributePermissionsWriteable
        )

        val dataNotifyChar = CBMutableCharacteristic(
            type = dataNotifyUUID,
            properties = CBCharacteristicPropertyRead or CBCharacteristicPropertyNotify,
            value = null,
            permissions = 0x01u, // CBAttributePermissionsReadable
        )

        val service = CBMutableService(type = serviceUUID, primary = true)
        service.setCharacteristics(
            listOf(controlWriteChar, controlNotifyChar, dataWriteChar, dataNotifyChar),
        )

        gattService = service
        peripheralManager?.addService(service)
    }

    private fun startAdvertising() {
        peripheralManager?.startAdvertising(
            mapOf<Any?, Any?>(
                CBAdvertisementDataServiceUUIDsKey to listOf(serviceUUID),
            ),
        )
        NSLog("$TAG: Started advertising")
    }

    private fun startScanning() {
        centralManager?.scanForPeripheralsWithServices(
            serviceUUIDs = listOf(serviceUUID),
            options = null,
        )
        NSLog("$TAG: Started scanning")
    }

    // ========================
    // CBCentralManagerDelegateProtocol
    // ========================

    private inner class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            NSLog("$TAG: Central manager state: ${central.state}")
            if (central.state == CBCentralManagerStatePoweredOn) {
                centralManagerReady = true
                startScanning()
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val peripheralUUID = didDiscoverPeripheral.identifier.UUIDString
            val now = io.meshlink.util.currentTimeMillis()

            val advertisementPayload = extractAdvertisementPayload(advertisementData)
            val peerId = derivePeerIdFromPeripheral(didDiscoverPeripheral)

            // Track this peer
            val existing = knownPeers[peripheralUUID]
            if (existing != null) {
                existing.lastSeenMs = now
            } else {
                knownPeers[peripheralUUID] = TrackedPeer(
                    peerId = peerId,
                    peripheral = didDiscoverPeripheral,
                    lastSeenMs = now,
                )
                connectedPeripherals[peerId.toHexString()] = didDiscoverPeripheral
            }

            _advertisementEvents.tryEmit(
                AdvertisementEvent(
                    peerId = peerId,
                    advertisementPayload = advertisementPayload,
                ),
            )
        }

        override fun centralManager(
            central: CBCentralManager,
            didConnectPeripheral: CBPeripheral,
        ) {
            NSLog("$TAG: Connected to ${didConnectPeripheral.identifier.UUIDString}")
            didConnectPeripheral.discoverServices(listOf(serviceUUID))
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            NSLog("$TAG: Failed to connect: ${didFailToConnectPeripheral.identifier.UUIDString}, error: ${error?.localizedDescription}")
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            val id = didDisconnectPeripheral.identifier.UUIDString
            NSLog("$TAG: Disconnected from $id, error: ${error?.localizedDescription}")
            discoveredWriteCharacteristics.remove(id)
        }
    }

    // ========================
    // CBPeripheralManagerDelegateProtocol
    // ========================

    private inner class PeripheralManagerDelegate : NSObject(), CBPeripheralManagerDelegateProtocol {

        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            NSLog("$TAG: Peripheral manager state: ${peripheral.state}")
            if (peripheral.state == CBPeripheralManagerStatePoweredOn) {
                peripheralManagerReady = true
                setupGattService()
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didAddService: CBService,
            error: NSError?,
        ) {
            if (error != null) {
                NSLog("$TAG: Failed to add service: ${error.localizedDescription}")
                return
            }
            NSLog("$TAG: Service added successfully")
            startAdvertising()
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveWriteRequests: List<*>,
        ) {
            for (request in didReceiveWriteRequests) {
                val attRequest = request as? CBATTRequest ?: continue
                val data = attRequest.value?.toByteArray() ?: continue
                val centralId = attRequest.central.identifier.UUIDString

                val peerId = derivePeerIdFromUUID(attRequest.central.identifier)

                NSLog("$TAG: Received write from $centralId, ${data.size} bytes")

                _incomingData.tryEmit(
                    IncomingData(peerId = peerId, data = data),
                )
            }

            // Respond to the first request (required for write-with-response)
            val firstRequest = didReceiveWriteRequests.firstOrNull() as? CBATTRequest
            if (firstRequest != null) {
                peripheral.respondToRequest(firstRequest, withResult = CBATTErrorSuccess)
            }
        }

        override fun peripheralManagerDidStartAdvertising(
            peripheral: CBPeripheralManager,
            error: NSError?,
        ) {
            if (error != null) {
                NSLog("$TAG: Failed to start advertising: ${error.localizedDescription}")
            } else {
                NSLog("$TAG: Advertising started successfully")
            }
        }
    }

    // ========================
    // CBPeripheralDelegateProtocol
    // ========================

    private inner class PeripheralDelegate(
        var onReady: (() -> Unit)? = null,
    ) : NSObject(), CBPeripheralDelegateProtocol {

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (didDiscoverServices != null) {
                NSLog("$TAG: Error discovering services: ${didDiscoverServices.localizedDescription}")
                onReady?.invoke()
                onReady = null
                return
            }

            val services = peripheral.services ?: emptyList<Any>()
            for (service in services) {
                val cbService = service as? CBService ?: continue
                if (cbService.UUID == serviceUUID) {
                    peripheral.discoverCharacteristics(
                        listOf(controlWriteUUID, dataWriteUUID),
                        forService = cbService,
                    )
                }
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            if (error != null) {
                NSLog("$TAG: Error discovering characteristics: ${error.localizedDescription}")
                onReady?.invoke()
                onReady = null
                return
            }

            val characteristics = didDiscoverCharacteristicsForService.characteristics
                ?: emptyList<Any>()
            for (characteristic in characteristics) {
                val cbChar = characteristic as? CBCharacteristic ?: continue
                if (cbChar.UUID == dataWriteUUID || cbChar.UUID == controlWriteUUID) {
                    discoveredWriteCharacteristics[peripheral.identifier.UUIDString] = cbChar
                    NSLog("$TAG: Found write characteristic on ${peripheral.identifier.UUIDString}")
                }
            }
            onReady?.invoke()
            onReady = null
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didWriteValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (error != null) {
                NSLog("$TAG: Write error: ${error.localizedDescription}")
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (error != null) {
                NSLog("$TAG: Notification error: ${error.localizedDescription}")
                return
            }

            val data = didUpdateValueForCharacteristic.value?.toByteArray() ?: return
            val peerId = derivePeerIdFromPeripheral(peripheral)

            _incomingData.tryEmit(
                IncomingData(peerId = peerId, data = data),
            )
        }
    }

    // ========================
    // Helpers
    // ========================

    @Suppress("UNCHECKED_CAST")
    private fun extractAdvertisementPayload(advertisementData: Map<Any?, *>): ByteArray {
        // Try service data
        val serviceData = advertisementData["kCBAdvDataServiceData"] as? Map<Any?, *>
        if (serviceData != null) {
            val data = serviceData[serviceUUID] as? NSData
            if (data != null) return data.toByteArray()
        }

        // Try manufacturer data
        val mfgData = advertisementData["kCBAdvDataManufacturerData"] as? NSData
        if (mfgData != null) return mfgData.toByteArray()

        return ByteArray(0)
    }

    private fun derivePeerIdFromPeripheral(peripheral: CBPeripheral): ByteArray =
        derivePeerIdFromUUID(peripheral.identifier)

    private fun derivePeerIdFromUUID(uuid: NSUUID): ByteArray {
        val uBytes = UByteArray(16)
        uBytes.usePinned { pinned ->
            uuid.getUUIDBytes(pinned.addressOf(0))
        }
        return uBytes.toByteArray()
    }
}
