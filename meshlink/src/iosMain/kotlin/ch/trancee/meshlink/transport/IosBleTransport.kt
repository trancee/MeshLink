@file:Suppress("WildcardImport", "TooManyFunctions", "LargeClass")
@file:OptIn(ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package ch.trancee.meshlink.transport

import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.power.PowerTier
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreBluetooth.*
import platform.Foundation.*
import platform.darwin.*
import platform.posix.memcpy

/**
 * iOS BLE transport: dual-role CoreBluetooth (CBCentralManager scanning + CBPeripheralManager
 * advertising/GATT server), L2CAP-first data plane via CBL2CAPChannel, GATT fallback, and State
 * Preservation/Restoration via willRestoreState.
 *
 * Implements [BleTransport] for iOS. Excluded from Kover per D024 — correctness proven by S04
 * two-device integration test on real hardware.
 *
 * **State P&R note:** Force-quit by the user clears iOS restoration state; S&R is effective only
 * for OS-initiated terminations (low memory etc.). This is an iOS platform limitation.
 *
 * Must be `internal` because the constructor takes the `internal` [Identity] type (MEM156).
 */
internal class IosBleTransport(
    private val config: BleTransportConfig,
    @Suppress("UnusedPrivateProperty") private val cryptoProvider: CryptoProvider,
    private val identity: Identity,
    private val scope: CoroutineScope,
    private val powerTierFlow: MutableStateFlow<PowerTier>,
    private val restorationIdentifier: String = "ch.trancee.meshlink",
) : BleTransport {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "IosBleTransport"
        private const val KEEPALIVE_INTERVAL_MS = 10_000L
        private const val SCAN_RESTART_INTERVAL_MS = 25L * 60L * 1_000L
        private const val GATT_WRITE_RETRIES = 3
        private const val MIN_EFFECTIVE_WRITE_LENGTH = 100
        private const val STOP_ALL_TIMEOUT_MS = 5_000L
        private const val BLE_STACK_UNRESPONSIVE = "BLE_STACK_UNRESPONSIVE"
        private const val L2CAP_RUNLOOP_POLL_SEC = 0.5
    }

    // ── CBUUID singletons ─────────────────────────────────────────────────────

    private val advertisementCBUUID = CBUUID.UUIDWithString(GattConstants.ADVERTISEMENT_UUID)
    private val serviceCBUUID = CBUUID.UUIDWithString(GattConstants.SERVICE_UUID)
    private val controlWriteCBUUID = CBUUID.UUIDWithString(GattConstants.CONTROL_WRITE_UUID)
    private val controlNotifyCBUUID = CBUUID.UUIDWithString(GattConstants.CONTROL_NOTIFY_UUID)
    private val dataWriteCBUUID = CBUUID.UUIDWithString(GattConstants.DATA_WRITE_UUID)
    private val dataNotifyCBUUID = CBUUID.UUIDWithString(GattConstants.DATA_NOTIFY_UUID)

    // ── BleTransport properties ───────────────────────────────────────────────

    override val localPeerId: ByteArray = identity.keyHash.copyOf()
    override var advertisementServiceData: ByteArray = ByteArray(0)

    // ── Flows ─────────────────────────────────────────────────────────────────

    private val _advertisementEvents =
        MutableSharedFlow<AdvertisementEvent>(replay = 0, extraBufferCapacity = 64)
    private val _peerLostEvents =
        MutableSharedFlow<PeerLostEvent>(replay = 0, extraBufferCapacity = 64)
    private val _incomingData =
        MutableSharedFlow<IncomingData>(replay = 0, extraBufferCapacity = 256)

    override val advertisementEvents: Flow<AdvertisementEvent> = _advertisementEvents
    override val peerLostEvents: Flow<PeerLostEvent> = _peerLostEvents
    override val incomingData: Flow<IncomingData> = _incomingData

    // ── Running flag ──────────────────────────────────────────────────────────

    private val isRunning = AtomicInt(0)

    // ── BLE serial queue ──────────────────────────────────────────────────────

    private val bleQueue: dispatch_queue_t =
        dispatch_queue_create("ch.trancee.meshlink.ble", null)!!

    // ── CoreBluetooth managers ────────────────────────────────────────────────

    private var centralManager: CBCentralManager? = null
    private var peripheralManager: CBPeripheralManager? = null

    // ── Mutable GATT objects (peripheral role) ────────────────────────────────

    private var gattService: CBMutableService? = null
    private var controlWriteChar: CBMutableCharacteristic? = null
    private var controlNotifyChar: CBMutableCharacteristic? = null
    private var dataWriteChar: CBMutableCharacteristic? = null
    private var dataNotifyChar: CBMutableCharacteristic? = null

    // ── Published L2CAP PSM ───────────────────────────────────────────────────

    @Volatile private var publishedPsm: CBL2CAPPSM = 0u

    // ── State P&R pending state ───────────────────────────────────────────────

    @Volatile private var pendingCentralRestoreState: Map<Any?, *>? = null
    @Volatile private var pendingPeripheralRestoreState: Map<Any?, *>? = null

    // ── Connection tracking ───────────────────────────────────────────────────

    private val peripheralsByKeyHashHex = HashMap<String, CBPeripheral>()
    private val seenPayloadsByUuid = HashMap<String, ByteArray>()
    private val l2capConnections = HashMap<String, L2capConnection>()
    private val gattCentrals = HashMap<String, GattCentralState>()
    private val subscribedCentralsByKeyHashHex = HashMap<String, CBCentral>()
    private val pendingL2capPsms = HashMap<String, Int>()
    private val connectionJobs = HashMap<String, Job>()
    private val lruOrder = LinkedHashMap<String, Unit>()
    private val localMeshHash: UShort = MeshHashFilter.computeMeshHash(config.appId)
    private val l2capProbeCache = OemL2capProbeCache()

    // ── Background jobs / observers ───────────────────────────────────────────

    private var scanRestartJob: Job? = null
    private var powerObserverJob: Job? = null
    private var thermalObserver: Any? = null
    private var memoryObserver: Any? = null

    // ── Notify backpressure queue ─────────────────────────────────────────────

    private val notifyQueueLock = Mutex()
    private val notifyQueue = ArrayDeque<Pair<NSData, CBMutableCharacteristic>>()

    // ── Inner data holders ────────────────────────────────────────────────────

    private inner class L2capConnection(
        val channel: CBL2CAPChannel,
        val writeMutex: Mutex = Mutex(),
        val latencyTracker: WriteLatencyTracker =
            WriteLatencyTracker(
                clock = { (NSDate.timeIntervalSinceReferenceDate * 1_000).toLong() }
            ),
        val retryScheduler: L2capRetryScheduler = L2capRetryScheduler(),
    ) {
        val outputStream: NSOutputStream = channel.outputStream!!
        val streamDelegate = L2capStreamDelegate(channel.inputStream!!)
    }

    private inner class GattCentralState(
        val peripheral: CBPeripheral,
        val writeMutex: Mutex = Mutex(),
    ) {
        @Volatile var controlWriteChar: CBCharacteristic? = null
        @Volatile var dataWriteChar: CBCharacteristic? = null
        @Volatile var maxWriteLength: Int = 20
    }

    // ── NSStreamDelegate ──────────────────────────────────────────────────────

    /**
     * Receives NSInputStream callbacks for one L2CAP channel. Accumulates bytes and parses
     * 3-byte-header L2CAP frames, dispatching DATA payloads to [_incomingData].
     */
    private inner class L2capStreamDelegate(private val inputStream: NSInputStream) :
        NSObject(), NSStreamDelegateProtocol {

        @Volatile var keyHashHex: String = ""
        private var accumBuffer = ByteArray(0)

        override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
            when {
                handleEvent == NSStreamEventHasBytesAvailable -> readAvailable()
                handleEvent == NSStreamEventEndEncountered -> {
                    println("$TAG L2CAP stream ended keyHash=$keyHashHex")
                    scope.launch { onL2capDisconnected(keyHashHex) }
                }
                handleEvent == NSStreamEventErrorOccurred -> {
                    val err = aStream.streamError?.localizedDescription ?: "unknown"
                    println("$TAG L2CAP stream error=$err keyHash=$keyHashHex")
                    scope.launch { onL2capDisconnected(keyHashHex) }
                }
            }
        }

        private fun readAvailable() {
            val tmpBuf = ByteArray(4096)
            tmpBuf.usePinned { pinned ->
                while (inputStream.hasBytesAvailable) {
                    val n =
                        inputStream.read(pinned.addressOf(0).reinterpret(), 4096.toULong()).toInt()
                    if (n <= 0) break
                    accumBuffer += tmpBuf.copyOf(n)
                    parseFrames()
                }
            }
        }

        private fun parseFrames() {
            while (accumBuffer.size >= 3) {
                val frameType = FrameType.fromByte(accumBuffer[0]) ?: break
                val length =
                    (accumBuffer[1].toInt() and 0xFF) or ((accumBuffer[2].toInt() and 0xFF) shl 8)
                if (accumBuffer.size < 3 + length) break
                val payload = accumBuffer.copyOfRange(3, 3 + length)
                accumBuffer = accumBuffer.copyOfRange(3 + length, accumBuffer.size)
                when (frameType) {
                    FrameType.DATA -> {
                        if (payload.isNotEmpty()) {
                            val peerId = hexToBytes(keyHashHex) ?: continue
                            scope.launch { _incomingData.emit(IncomingData(peerId, payload)) }
                        }
                    }
                    FrameType.CLOSE -> {
                        scope.launch { onL2capDisconnected(keyHashHex) }
                        return
                    }
                    FrameType.ACK -> {
                        /* no-op */
                    }
                }
            }
        }
    }

    // ── CBCentralManagerDelegate ──────────────────────────────────────────────

    private inner class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            println("$TAG central state=${central.state}")
            when (central.state) {
                CBManagerStatePoweredOn -> scope.launch { onCentralPoweredOn() }
                CBManagerStatePoweredOff,
                CBManagerStateUnauthorized,
                CBManagerStateResetting -> scope.launch { onBlePowerDown() }
                else -> Unit
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            processDiscoveredPeripheral(didDiscoverPeripheral, advertisementData, RSSI.intValue)
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            val hex = peripheralToKeyHashHex(didConnectPeripheral) ?: return
            println("$TAG GATT/L2CAP connected keyHash=$hex")
            didConnectPeripheral.delegate = PeripheralDelegate(hex)
            val psm = pendingL2capPsms.remove(hex) ?: 0
            if (!config.forceGatt && (psm != 0 || config.forceL2cap)) {
                didConnectPeripheral.openL2CAPChannel(psm.toUShort())
            } else {
                didConnectPeripheral.discoverServices(listOf(serviceCBUUID))
                scope.launch { initiateGattCentral(didConnectPeripheral, hex) }
            }
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            val hex = peripheralToKeyHashHex(didFailToConnectPeripheral) ?: return
            println("$TAG connect failed keyHash=$hex error=${error?.localizedDescription}")
            scope.launch {
                gattCentrals.remove(hex)
                hexToBytes(hex)?.let {
                    _peerLostEvents.emit(PeerLostEvent(it, PeerLostReason.CONNECTION_LOST))
                }
            }
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            val hex = peripheralToKeyHashHex(didDisconnectPeripheral) ?: return
            println("$TAG disconnected keyHash=$hex error=${error?.localizedDescription}")
            scope.launch {
                gattCentrals.remove(hex)
                if (isRunning.value != 0) {
                    hexToBytes(hex)?.let {
                        _peerLostEvents.emit(PeerLostEvent(it, PeerLostReason.CONNECTION_LOST))
                    }
                }
            }
        }

        override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) {
            println("$TAG willRestoreState (central) invoked")
            pendingCentralRestoreState = willRestoreState
        }
    }

    // ── CBPeripheralManagerDelegate ───────────────────────────────────────────

    private inner class PeripheralManagerDelegate :
        NSObject(), CBPeripheralManagerDelegateProtocol {

        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            println("$TAG peripheral manager state=${peripheral.state}")
            when (peripheral.state) {
                CBManagerStatePoweredOn -> scope.launch { onPeripheralManagerPoweredOn() }
                CBManagerStatePoweredOff,
                CBManagerStateUnauthorized,
                CBManagerStateResetting -> scope.launch { onBlePowerDown() }
                else -> Unit
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didAddService: CBService,
            error: NSError?,
        ) {
            if (error != null) println("$TAG service add failed: ${error.localizedDescription}")
            else println("$TAG GATT service added uuid=${didAddService.UUID}")
        }

        override fun peripheralManagerDidStartAdvertising(
            peripheral: CBPeripheralManager,
            error: NSError?,
        ) {
            if (error != null) println("$TAG advertising failed: ${error.localizedDescription}")
            else println("$TAG advertising started psm=$publishedPsm meshHash=$localMeshHash")
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didPublishL2CAPChannel: CBL2CAPPSM,
            error: NSError?,
        ) {
            if (error != null) {
                println("$TAG L2CAP publish failed: ${error.localizedDescription}")
                return
            }
            publishedPsm = didPublishL2CAPChannel
            println("$TAG L2CAP channel published PSM=$didPublishL2CAPChannel")
            updateAdvertisementPayload()
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didOpenL2CAPChannel: CBL2CAPChannel?,
            error: NSError?,
        ) {
            if (error != null || didOpenL2CAPChannel == null) {
                println("$TAG inbound L2CAP open failed: ${error?.localizedDescription}")
                return
            }
            println("$TAG L2CAP inbound channel opened PSM=${didOpenL2CAPChannel.PSM}")
            scope.launch { registerInboundL2cap(didOpenL2CAPChannel) }
        }

        @ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didSubscribeToCharacteristic: CBCharacteristic,
        ) {
            println("$TAG central subscribed uuid=${didSubscribeToCharacteristic.UUID}")
        }

        @ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didUnsubscribeFromCharacteristic: CBCharacteristic,
        ) {
            println("$TAG central unsubscribed uuid=${didUnsubscribeFromCharacteristic.UUID}")
        }

        override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
            println("$TAG ready to update subscribers — draining notify queue")
            scope.launch { drainNotifyQueue() }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveReadRequest: CBATTRequest,
        ) {
            peripheral.respondToRequest(didReceiveReadRequest, withResult = CBATTErrorSuccess)
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveWriteRequests: List<*>,
        ) {
            @Suppress("UNCHECKED_CAST") val reqs = didReceiveWriteRequests as List<CBATTRequest>
            for (req in reqs) {
                val data = req.value?.toByteArray() ?: continue
                scope.launch { _incomingData.emit(IncomingData(ByteArray(12), data)) }
            }
            reqs.firstOrNull()?.let {
                peripheral.respondToRequest(it, withResult = CBATTErrorSuccess)
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            willRestoreState: Map<Any?, *>,
        ) {
            println("$TAG willRestoreState (peripheral) invoked")
            pendingPeripheralRestoreState = willRestoreState
        }
    }

    // ── CBPeripheralDelegate ──────────────────────────────────────────────────

    private inner class PeripheralDelegate(private val keyHashHex: String) :
        NSObject(), CBPeripheralDelegateProtocol {

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (didDiscoverServices != null) {
                println(
                    "$TAG service discovery failed $keyHashHex: ${didDiscoverServices.localizedDescription}"
                )
                scope.launch { closeGattCentral(keyHashHex) }
                return
            }
            val service =
                peripheral.services?.filterIsInstance<CBService>()?.firstOrNull {
                    it.UUID == serviceCBUUID
                }
                    ?: run {
                        println("$TAG MeshLink service not found on $keyHashHex")
                        scope.launch { closeGattCentral(keyHashHex) }
                        return
                    }
            peripheral.discoverCharacteristics(
                listOf(controlWriteCBUUID, controlNotifyCBUUID, dataWriteCBUUID, dataNotifyCBUUID),
                forService = service,
            )
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            if (error != null) {
                println("$TAG char discovery failed $keyHashHex: ${error.localizedDescription}")
                scope.launch { closeGattCentral(keyHashHex) }
                return
            }
            val state = gattCentrals[keyHashHex] ?: return
            didDiscoverCharacteristicsForService.characteristics
                ?.filterIsInstance<CBCharacteristic>()
                ?.forEach { char ->
                    when (char.UUID) {
                        controlWriteCBUUID -> state.controlWriteChar = char
                        dataWriteCBUUID -> state.dataWriteChar = char
                        controlNotifyCBUUID,
                        dataNotifyCBUUID ->
                            peripheral.setNotifyValue(true, forCharacteristic = char)
                    }
                }
            state.maxWriteLength =
                peripheral
                    .maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse)
                    .toInt()
            if (state.maxWriteLength < MIN_EFFECTIVE_WRITE_LENGTH) {
                println(
                    "$TAG GATT write length ${state.maxWriteLength} < $MIN_EFFECTIVE_WRITE_LENGTH — disconnecting $keyHashHex"
                )
                scope.launch { closeGattCentral(keyHashHex) }
                return
            }
            println("$TAG GATT central ready $keyHashHex maxWrite=${state.maxWriteLength}")
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (error != null) return
            val data = didUpdateValueForCharacteristic.value?.toByteArray() ?: return
            hexToBytes(keyHashHex)?.let { peerId ->
                scope.launch { _incomingData.emit(IncomingData(peerId, data)) }
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didOpenL2CAPChannel: CBL2CAPChannel?,
            error: NSError?,
        ) {
            if (error != null || didOpenL2CAPChannel == null) {
                println("$TAG L2CAP open failed $keyHashHex: ${error?.localizedDescription}")
                // Fallback: discover GATT services instead
                peripheral.discoverServices(listOf(serviceCBUUID))
                scope.launch { initiateGattCentral(peripheral, keyHashHex) }
                return
            }
            println(
                "$TAG L2CAP opened (central) keyHash=$keyHashHex PSM=${didOpenL2CAPChannel.PSM}"
            )
            scope.launch { registerL2capConnection(keyHashHex, didOpenL2CAPChannel) }
        }
    }

    // ── BleTransport implementation ───────────────────────────────────────────

    override suspend fun startAdvertisingAndScanning() {
        if (!isRunning.compareAndSet(0, 1)) {
            throw IllegalStateException("IosBleTransport is already running")
        }

        val centralDelegate = CentralDelegate()
        val peripheralDelegate = PeripheralManagerDelegate()

        centralManager =
            CBCentralManager(
                delegate = centralDelegate,
                queue = bleQueue,
                options =
                    mapOf(
                        CBCentralManagerOptionRestoreIdentifierKey to
                            "${restorationIdentifier}.central",
                        CBCentralManagerOptionShowPowerAlertKey to false,
                    ),
            )
        peripheralManager =
            CBPeripheralManager(
                delegate = peripheralDelegate,
                queue = bleQueue,
                options =
                    mapOf(
                        CBPeripheralManagerOptionRestoreIdentifierKey to
                            "${restorationIdentifier}.peripheral",
                        CBPeripheralManagerOptionShowPowerAlertKey to false,
                    ),
            )

        powerObserverJob = scope.launch {
            powerTierFlow.collect { tier ->
                if (isRunning.value != 0) {
                    println("$TAG power tier → $tier")
                    centralManager?.stopScan()
                    startScan(tier)
                }
            }
        }

        registerEnvironmentalObservers()

        scanRestartJob = scope.launch {
            delay(SCAN_RESTART_INTERVAL_MS)
            while (isRunning.value != 0) {
                println("$TAG restarting scan (25-min timer)")
                centralManager?.stopScan()
                startScan(powerTierFlow.value)
                delay(SCAN_RESTART_INTERVAL_MS)
            }
        }

        println("$TAG startAdvertisingAndScanning — awaiting poweredOn callbacks")
    }

    override suspend fun stopAll() {
        if (!isRunning.compareAndSet(1, 0)) return
        println("$TAG stopAll() begin")

        val t0 = (NSDate.timeIntervalSinceReferenceDate * 1_000).toLong()

        scanRestartJob?.cancel()
        powerObserverJob?.cancel()
        scanRestartJob = null
        powerObserverJob = null

        unregisterEnvironmentalObservers()

        centralManager?.stopScan()
        peripheralManager?.stopAdvertising()
        peripheralManager?.removeAllServices()

        l2capConnections.keys.toList().forEach { closeL2capConnection(it) }
        gattCentrals.keys.toList().forEach { closeGattCentral(it) }
        connectionJobs.values.forEach { it.cancel() }
        connectionJobs.clear()

        centralManager?.delegate = null
        peripheralManager?.delegate = null
        centralManager = null
        peripheralManager = null

        peripheralsByKeyHashHex.clear()
        seenPayloadsByUuid.clear()
        subscribedCentralsByKeyHashHex.clear()
        pendingL2capPsms.clear()
        l2capProbeCache.clear()
        lruLock.withLock { lruOrder.clear() }

        val elapsed = (NSDate.timeIntervalSinceReferenceDate * 1_000).toLong() - t0
        if (elapsed > STOP_ALL_TIMEOUT_MS) {
            println("$TAG $BLE_STACK_UNRESPONSIVE stopAll() took ${elapsed}ms")
        }
        println("$TAG stopAll() complete")
    }

    override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray): SendResult {
        val hex = bytesToHex(peerId)
        l2capConnections[hex]?.let {
            return sendViaL2cap(hex, it, data)
        }
        gattCentrals[hex]?.let {
            return sendViaGattCentral(hex, it, data)
        }
        subscribedCentralsByKeyHashHex[hex]?.let {
            return sendViaGattPeripheral(data)
        }
        // Initiate on-demand connection
        peripheralsByKeyHashHex[hex]?.let { peripheral ->
            scope.launch { initiateConnection(peripheral, hex) }
        }
        return SendResult.Failure("No active connection to peer $hex — initiating")
    }

    override suspend fun disconnect(peerId: ByteArray) {
        val hex = bytesToHex(peerId)
        l2capConnections[hex]?.let { conn ->
            runCatching {
                val frame = L2capFrameCodec.encode(FrameType.CLOSE, ByteArray(0))
                conn.writeMutex.withLock { writeToStream(conn.outputStream, frame) }
            }
            closeL2capConnection(hex)
        }
        closeGattCentral(hex)
        _peerLostEvents.emit(PeerLostEvent(peerId, PeerLostReason.MANUAL_DISCONNECT))
    }

    override suspend fun requestConnectionPriority(peerId: ByteArray, highPriority: Boolean) {
        // requestConnectionPriority is an Android API with no direct iOS equivalent.
        // iOS connection latency is managed by CBPeripheralManager.setDesiredConnectionLatency
        // which is advisory and not strictly required. Documented no-op on iOS.
        println("$TAG requestConnectionPriority is a no-op on iOS (platform limitation)")
    }

    // ── BLE power-on handlers ─────────────────────────────────────────────────

    private fun onCentralPoweredOn() {
        println("$TAG central powered on")
        // State Restoration: re-assign delegates to restored peripherals
        pendingCentralRestoreState?.let { state ->
            @Suppress("UNCHECKED_CAST")
            val restored = state[CBCentralManagerRestoredStatePeripheralsKey] as? List<CBPeripheral>
            restored?.forEach { peripheral ->
                val hex = peripheral.identifier.UUIDString
                println("$TAG S&R reconnecting $hex")
                peripheralsByKeyHashHex[hex] = peripheral
                peripheral.delegate = PeripheralDelegate(hex)
            }
            pendingCentralRestoreState = null
        }
        startScan(powerTierFlow.value)
    }

    private fun onPeripheralManagerPoweredOn() {
        println("$TAG peripheral manager powered on")
        val alreadyRestored =
            pendingPeripheralRestoreState?.let { state ->
                state[CBPeripheralManagerRestoredStateServicesKey] != null
            } ?: false

        if (!alreadyRestored) {
            buildAndAddGattService()
        } else {
            println("$TAG S&R service tree restored — skipping addService()")
            pendingPeripheralRestoreState = null
        }
        // Always re-publish L2CAP channel: the OS assigns a new PSM on each launch
        peripheralManager?.publishL2CAPChannelWithEncryption(false)
    }

    private suspend fun onBlePowerDown() {
        println("$TAG BLE power down — emitting PeerLostEvent for all active peers")
        val affected = (l2capConnections.keys + gattCentrals.keys).toSet()
        for (hex in affected) {
            hexToBytes(hex)?.let {
                _peerLostEvents.emit(PeerLostEvent(it, PeerLostReason.CONNECTION_LOST))
            }
        }
        centralManager?.stopScan()
        peripheralManager?.stopAdvertising()
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    private fun startScan(tier: PowerTier) {
        val allowDuplicates = tier == PowerTier.PERFORMANCE
        centralManager?.scanForPeripheralsWithServices(
            serviceUUIDs = listOf(advertisementCBUUID),
            options = mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to allowDuplicates),
        )
        println("$TAG BLE scan started allowDuplicates=$allowDuplicates tier=$tier")
    }

    // ── Peer discovery ────────────────────────────────────────────────────────

    private fun processDiscoveredPeripheral(
        peripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        rssi: Int,
    ) {
        val uuid = peripheral.identifier.UUIDString

        @Suppress("UNCHECKED_CAST")
        val serviceDataDict = advertisementData[CBAdvertisementDataServiceDataKey] as? Map<Any?, *>
        val serviceNSData = serviceDataDict?.get(advertisementCBUUID) as? NSData ?: return
        val serviceData = serviceNSData.toByteArray()
        if (serviceData.size != 16) return

        seenPayloadsByUuid[uuid]?.let { if (it.contentEquals(serviceData)) return }
        seenPayloadsByUuid[uuid] = serviceData

        val payload = runCatching { AdvertisementCodec.decode(serviceData) }.getOrNull() ?: return
        if (payload.protocolVersion != BleTransportConfig.PROTOCOL_VERSION) return
        if (!MeshHashFilter.matches(payload.meshHash, localMeshHash)) return
        if (payload.keyHash.contentEquals(identity.keyHash)) return

        val keyHashHex = bytesToHex(payload.keyHash)
        peripheralsByKeyHashHex[keyHashHex] = peripheral

        scope.launch {
            _advertisementEvents.emit(
                AdvertisementEvent(
                    peerId = payload.keyHash.copyOf(),
                    serviceData = serviceData,
                    rssi = rssi,
                )
            )
        }

        if (
            !connectionJobs.containsKey(keyHashHex) &&
                !l2capConnections.containsKey(keyHashHex) &&
                !gattCentrals.containsKey(keyHashHex) &&
                ConnectionInitiationPolicy.shouldInitiate(
                    identity.keyHash,
                    powerTierFlow.value.ordinal,
                    payload.keyHash,
                    payload.powerMode,
                )
        ) {
            val stagger =
                ConnectionInitiationPolicy.staggerDelayMillis(identity.keyHash, payload.keyHash)
            val psm = payload.l2capPsm.toInt()
            val job = scope.launch {
                if (stagger > 0) delay(stagger)
                evictLruIfFull()
                initiateConnection(peripheral, keyHashHex, psm)
            }
            connectionJobs[keyHashHex] = job
            job.invokeOnCompletion { connectionJobs.remove(keyHashHex) }
        }
    }

    // ── Connection initiation ─────────────────────────────────────────────────

    private fun initiateConnection(peripheral: CBPeripheral, keyHashHex: String, psm: Int = 0) {
        val cm = centralManager ?: return
        if (!config.forceGatt && (psm != 0 || config.forceL2cap)) {
            pendingL2capPsms[keyHashHex] = psm
        }
        cm.connectPeripheral(peripheral, options = null)
        println("$TAG connecting to $keyHashHex psm=$psm")
    }

    private suspend fun initiateGattCentral(peripheral: CBPeripheral, keyHashHex: String) {
        val state = GattCentralState(peripheral)
        gattCentrals[keyHashHex] = state
        trackLru(keyHashHex)
        println("$TAG GATT central state created $keyHashHex")
    }

    // ── L2CAP connections ─────────────────────────────────────────────────────

    private suspend fun registerL2capConnection(keyHashHex: String, channel: CBL2CAPChannel) {
        val conn = L2capConnection(channel)
        conn.streamDelegate.keyHashHex = keyHashHex
        l2capConnections[keyHashHex] = conn
        trackLru(keyHashHex)
        startL2capStreamLoop(keyHashHex, conn)
        scope.launch { l2capKeepalive(keyHashHex, conn) }
        println("$TAG L2CAP connection registered keyHash=$keyHashHex")
    }

    private suspend fun registerInboundL2cap(channel: CBL2CAPChannel) {
        val tempKey =
            "inbound-${channel.PSM}-${(NSDate.timeIntervalSinceReferenceDate * 1_000).toLong()}"
        val conn = L2capConnection(channel)
        conn.streamDelegate.keyHashHex = tempKey
        l2capConnections[tempKey] = conn
        startL2capStreamLoop(tempKey, conn)
        println("$TAG inbound L2CAP registered key=$tempKey")
    }

    /**
     * Starts a dedicated single-thread coroutine that drives the NSRunLoop for one L2CAP channel.
     * The NSStreamDelegate receives data callbacks on this thread.
     */
    private fun startL2capStreamLoop(keyHashHex: String, conn: L2capConnection) {
        val ctx = newSingleThreadContext("ch.trancee.meshlink.l2cap.$keyHashHex")
        scope.launch(ctx) {
            val runLoop = NSRunLoop.currentRunLoop()
            val inputStream = conn.channel.inputStream!!
            conn.outputStream.open()
            inputStream.setDelegate(conn.streamDelegate)
            inputStream.scheduleInRunLoop(runLoop, forMode = NSDefaultRunLoopMode)
            inputStream.open()
            println("$TAG L2CAP stream loop started keyHash=$keyHashHex")

            // Drive RunLoop until connection closes
            while (isRunning.value != 0 && l2capConnections.containsKey(keyHashHex)) {
                runLoop.runMode(
                    NSDefaultRunLoopMode,
                    beforeDate = NSDate.dateWithTimeIntervalSinceNow(L2CAP_RUNLOOP_POLL_SEC),
                )
            }

            inputStream.removeFromRunLoop(runLoop, forMode = NSDefaultRunLoopMode)
            inputStream.setDelegate(null)
            inputStream.close()
            conn.outputStream.close()
            ctx.close()
            println("$TAG L2CAP stream loop exited keyHash=$keyHashHex")
        }
    }

    private suspend fun l2capKeepalive(keyHashHex: String, conn: L2capConnection) {
        while (isRunning.value != 0 && l2capConnections.containsKey(keyHashHex)) {
            delay(KEEPALIVE_INTERVAL_MS)
            if (!l2capConnections.containsKey(keyHashHex)) break
            val frame = L2capFrameCodec.encode(FrameType.DATA, ByteArray(0))
            runCatching { conn.writeMutex.withLock { writeToStream(conn.outputStream, frame) } }
        }
    }

    private suspend fun onL2capDisconnected(keyHashHex: String) {
        closeL2capConnection(keyHashHex)
        if (isRunning.value != 0) {
            hexToBytes(keyHashHex)?.let {
                _peerLostEvents.emit(PeerLostEvent(it, PeerLostReason.CONNECTION_LOST))
            }
        }
    }

    private fun closeL2capConnection(keyHashHex: String) {
        l2capConnections.remove(keyHashHex)?.also {
            println("$TAG L2CAP closed keyHash=$keyHashHex")
        }
    }

    // ── Send helpers ──────────────────────────────────────────────────────────

    private suspend fun sendViaL2cap(
        keyHashHex: String,
        conn: L2capConnection,
        data: ByteArray,
    ): SendResult {
        val frame = L2capFrameCodec.encode(FrameType.DATA, data)
        val t0 = (NSDate.timeIntervalSinceReferenceDate * 1_000).toLong()
        val ok =
            runCatching { conn.writeMutex.withLock { writeToStream(conn.outputStream, frame) } }
                .getOrElse { false }
        if (!ok) {
            closeL2capConnection(keyHashHex)
            hexToBytes(keyHashHex)?.let {
                _peerLostEvents.emit(PeerLostEvent(it, PeerLostReason.CONNECTION_LOST))
            }
            return SendResult.Failure("L2CAP write error $keyHashHex")
        }
        val duration = (NSDate.timeIntervalSinceReferenceDate * 1_000).toLong() - t0
        if (conn.latencyTracker.recordWrite(duration)) {
            println("$TAG L2CAP backpressure $keyHashHex — GATT fallback")
            closeL2capConnection(keyHashHex)
            hexToBytes(keyHashHex)?.let {
                _peerLostEvents.emit(PeerLostEvent(it, PeerLostReason.CONNECTION_LOST))
            }
            return SendResult.Failure("L2CAP backpressure $keyHashHex")
        }
        return SendResult.Success
    }

    private suspend fun sendViaGattCentral(
        keyHashHex: String,
        state: GattCentralState,
        data: ByteArray,
    ): SendResult {
        val char =
            state.dataWriteChar ?: return SendResult.Failure("No data write char for $keyHashHex")
        for (attempt in 1..GATT_WRITE_RETRIES) {
            val ok =
                runCatching {
                        state.writeMutex.withLock {
                            state.peripheral.writeValue(
                                data.toNSData(),
                                forCharacteristic = char,
                                type = CBCharacteristicWriteWithoutResponse,
                            )
                        }
                    }
                    .isSuccess
            if (ok) return SendResult.Success
            delay(100L shl (attempt - 1))
        }
        return SendResult.Failure(
            "GATT write failed after $GATT_WRITE_RETRIES attempts for $keyHashHex"
        )
    }

    private suspend fun sendViaGattPeripheral(data: ByteArray): SendResult {
        val pm = peripheralManager ?: return SendResult.Failure("Peripheral manager not ready")
        val char = dataNotifyChar ?: return SendResult.Failure("Data notify char not ready")
        val nsData = data.toNSData()
        val sent = pm.updateValue(nsData, forCharacteristic = char, onSubscribedCentrals = null)
        if (!sent) {
            notifyQueueLock.withLock { notifyQueue.addLast(nsData to char) }
            return SendResult.Failure("GATT notify queue full — queued for retry")
        }
        return SendResult.Success
    }

    private suspend fun drainNotifyQueue() {
        val pm = peripheralManager ?: return
        notifyQueueLock.withLock {
            val iter = notifyQueue.iterator()
            while (iter.hasNext()) {
                val (data, char) = iter.next()
                val sent =
                    pm.updateValue(data, forCharacteristic = char, onSubscribedCentrals = null)
                if (sent) iter.remove() else break
            }
        }
    }

    private suspend fun closeGattCentral(keyHashHex: String) {
        gattCentrals.remove(keyHashHex)?.let { state ->
            centralManager?.cancelPeripheralConnection(state.peripheral)
            println("$TAG GATT central closed keyHash=$keyHashHex")
        }
    }

    // ── GATT service construction ─────────────────────────────────────────────

    private fun buildAndAddGattService() {
        val ctrlWrite =
            CBMutableCharacteristic(
                type = controlWriteCBUUID,
                properties = CBCharacteristicPropertyWriteWithoutResponse,
                value = null,
                permissions = CBAttributePermissionsWriteable,
            )
        val ctrlNotify =
            CBMutableCharacteristic(
                type = controlNotifyCBUUID,
                properties = CBCharacteristicPropertyNotify,
                value = null,
                permissions = CBAttributePermissionsReadable,
            )
        val dataWrite =
            CBMutableCharacteristic(
                type = dataWriteCBUUID,
                properties = CBCharacteristicPropertyWriteWithoutResponse,
                value = null,
                permissions = CBAttributePermissionsWriteable,
            )
        val dataNot =
            CBMutableCharacteristic(
                type = dataNotifyCBUUID,
                properties = CBCharacteristicPropertyNotify,
                value = null,
                permissions = CBAttributePermissionsReadable,
            )
        controlWriteChar = ctrlWrite
        controlNotifyChar = ctrlNotify
        dataWriteChar = dataWrite
        dataNotifyChar = dataNot

        val service = CBMutableService(type = serviceCBUUID, primary = true)
        // CBMutableService.characteristics is read-only in K/N (inherited from CBService).
        // Use Key-Value Coding to invoke the Obj-C readwrite setter directly.
        (service as NSObject).setValue(
            listOf(ctrlWrite, ctrlNotify, dataWrite, dataNot),
            forKey = "characteristics",
        )
        gattService = service
        peripheralManager?.addService(service)
    }

    // ── Advertisement payload ─────────────────────────────────────────────────

    private fun updateAdvertisementPayload() {
        val payload =
            AdvertisementCodec.AdvertisementPayload(
                protocolVersion = BleTransportConfig.PROTOCOL_VERSION,
                powerMode = powerTierFlow.value.ordinal,
                meshHash = localMeshHash,
                l2capPsm = publishedPsm.toUByte(),
                keyHash = identity.keyHash,
            )
        val encoded = AdvertisementCodec.encode(payload)
        advertisementServiceData = encoded
        val pm = peripheralManager ?: return
        if (pm.isAdvertising) pm.stopAdvertising()
        pm.startAdvertising(
            mapOf(CBAdvertisementDataServiceUUIDsKey to listOf(advertisementCBUUID))
        )
        println("$TAG advertising updated PSM=$publishedPsm meshHash=$localMeshHash")
    }

    // ── Environmental monitoring ──────────────────────────────────────────────

    private fun registerEnvironmentalObservers() {
        val nc = NSNotificationCenter.defaultCenter
        thermalObserver =
            nc.addObserverForName(
                name = NSProcessInfoThermalStateDidChangeNotification,
                `object` = null,
                queue = null,
            ) { _ ->
                val state = NSProcessInfo.processInfo.thermalState
                if (
                    state == NSProcessInfoThermalState.NSProcessInfoThermalStateSerious ||
                        state == NSProcessInfoThermalState.NSProcessInfoThermalStateCritical
                ) {
                    println("$TAG thermal pressure=$state → POWER_SAVER")
                    scope.launch { powerTierFlow.value = PowerTier.POWER_SAVER }
                }
            }
        memoryObserver =
            nc.addObserverForName(
                name = "UIApplicationDidReceiveMemoryWarningNotification",
                `object` = null,
                queue = null,
            ) { _ ->
                println("$TAG memory warning → POWER_SAVER")
                scope.launch { powerTierFlow.value = PowerTier.POWER_SAVER }
            }
    }

    private fun unregisterEnvironmentalObservers() {
        val nc = NSNotificationCenter.defaultCenter
        thermalObserver?.let { nc.removeObserver(it) }
        memoryObserver?.let { nc.removeObserver(it) }
        thermalObserver = null
        memoryObserver = null
    }

    // ── LRU eviction ─────────────────────────────────────────────────────────

    private val lruLock = Mutex()

    private suspend fun trackLru(keyHashHex: String) {
        lruLock.withLock {
            // Simulate access-order LRU: remove and re-insert moves entry to the end
            lruOrder.remove(keyHashHex)
            lruOrder[keyHashHex] = Unit
        }
    }

    private suspend fun evictLruIfFull() {
        val evict: String? = lruLock.withLock {
            if (
                l2capConnections.size + gattCentrals.size >= config.maxConnections &&
                    lruOrder.isNotEmpty()
            )
                lruOrder.keys.first().also { lruOrder.remove(it) }
            else null
        }
        evict?.let { hex ->
            println("$TAG LRU eviction: $hex")
            closeL2capConnection(hex)
            scope.launch {
                closeGattCentral(hex)
                hexToBytes(hex)?.let {
                    _peerLostEvents.emit(PeerLostEvent(it, PeerLostReason.CONNECTION_LOST))
                }
            }
        }
    }

    // ── Helper: peripheral → keyHashHex ──────────────────────────────────────

    private fun peripheralToKeyHashHex(peripheral: CBPeripheral): String? =
        peripheralsByKeyHashHex.entries.firstOrNull { it.value === peripheral }?.key

    // ── NSOutputStream write helper ───────────────────────────────────────────

    private fun writeToStream(stream: NSOutputStream, data: ByteArray): Boolean {
        if (data.isEmpty()) return true
        var offset = 0
        return memScoped {
            while (offset < data.size) {
                val ptr = data.refTo(offset).getPointer(this).reinterpret<UByteVar>()
                val n = stream.write(ptr, (data.size - offset).toULong()).toInt()
                if (n <= 0) return false
                offset += n
            }
            true
        }
    }

    // ── NSData ↔ ByteArray bridge ─────────────────────────────────────────────

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        if (size == 0) return ByteArray(0)
        val result = ByteArray(size)
        result.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
        return result
    }

    private fun ByteArray.toNSData(): NSData {
        if (isEmpty()) return NSData.data()
        return usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), size.toULong()) }
    }

    // ── Hex / bytes helpers ───────────────────────────────────────────────────

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return runCatching {
                ByteArray(hex.length / 2) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }
            .getOrNull()
    }
}
