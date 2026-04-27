@file:Suppress("DEPRECATION")

package ch.trancee.meshlink.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.power.PowerTier
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Android BLE transport: dual-role GATT server + Central, L2CAP-first with GATT fallback.
 *
 * Implements [BleTransport] for Android. Excluded from Kover per D024 — correctness proven by S04
 * two-device integration test on real hardware.
 */
@SuppressLint("MissingPermission")
internal class AndroidBleTransport(
    private val context: Context,
    private val config: BleTransportConfig,
    @Suppress("UnusedPrivateProperty") private val cryptoProvider: CryptoProvider,
    private val identity: Identity,
    private val scope: CoroutineScope,
    private val powerTierFlow: MutableStateFlow<PowerTier>,
    private val probeCache: OemL2capProbeCache,
    private val oemSlotTracker: OemSlotTracker,
    private val bootstrapMode: Boolean,
) : BleTransport {

    companion object {
        private const val TAG = "AndroidBleTransport"
        private val ADVERTISEMENT_PARCEL_UUID =
            ParcelUuid.fromString(GattConstants.ADVERTISEMENT_UUID)
        private val SERVICE_UUID = UUID.fromString(GattConstants.SERVICE_UUID)
        private val CONTROL_WRITE_UUID = UUID.fromString(GattConstants.CONTROL_WRITE_UUID)
        private val CONTROL_NOTIFY_UUID = UUID.fromString(GattConstants.CONTROL_NOTIFY_UUID)
        private val DATA_WRITE_UUID = UUID.fromString(GattConstants.DATA_WRITE_UUID)
        private val DATA_NOTIFY_UUID = UUID.fromString(GattConstants.DATA_NOTIFY_UUID)
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val KEEPALIVE_INTERVAL_MS = 10_000L
        private const val L2CAP_RECEIVE_TIMEOUT_MS = 15_000L
        private const val SCAN_RESTART_INTERVAL_MS = 25L * 60L * 1_000L
        private const val L2CAP_CONNECT_TIMEOUT_MS = 10_000L
        private const val GATT_CONNECT_TIMEOUT_MS = 15_000L
        private const val GATT_MTU_TIMEOUT_MS = 5_000L
        private const val GATT_DISCOVER_TIMEOUT_MS = 10_000L
        private const val STOP_ALL_TIMEOUT_MS = 5_000L
        private const val BOOTSTRAP_DURATION_MILLIS = 60_000L
        private const val GATT_WRITE_RETRIES = 3
        private const val MTU_REQUEST = 517
        private const val MIN_EFFECTIVE_MTU = 100
        private const val PERM_BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
        private const val PERM_BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
        private const val PERM_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        private const val PERM_BLUETOOTH = "android.permission.BLUETOOTH"
        private const val PERM_BLUETOOTH_ADMIN = "android.permission.BLUETOOTH_ADMIN"
        private const val BLE_STACK_UNRESPONSIVE = "BLE_STACK_UNRESPONSIVE"
    }

    private val btManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter: BluetoothAdapter = btManager.adapter

    override val localPeerId: ByteArray = identity.keyHash.copyOf()
    override var advertisementServiceData: ByteArray = ByteArray(0)

    private val localMeshHash: UShort = MeshHashFilter.computeMeshHash(config.appId)
    private val isRunning = AtomicBoolean(false)

    private val _advertisementEvents =
        MutableSharedFlow<AdvertisementEvent>(replay = 0, extraBufferCapacity = 64)
    private val _peerLostEvents =
        MutableSharedFlow<PeerLostEvent>(replay = 0, extraBufferCapacity = 64)
    private val _incomingData =
        MutableSharedFlow<IncomingData>(replay = 0, extraBufferCapacity = 256)

    override val advertisementEvents: Flow<AdvertisementEvent> = _advertisementEvents
    override val peerLostEvents: Flow<PeerLostEvent> = _peerLostEvents
    override val incomingData: Flow<IncomingData> = _incomingData

    private var advertisingCallback: AdvertisingSetCallback? = null
    @Suppress("unused") private var currentAdvertisingSet: AdvertisingSet? = null
    private var scanCallback: ScanCallback? = null
    private var l2capServerSocket: BluetoothServerSocket? = null
    private var gattServer: BluetoothGattServer? = null
    private var l2capAcceptJob: Job? = null
    private var scanRestartJob: Job? = null
    private var powerObserverJob: Job? = null
    private var btStateReceiver: BroadcastReceiver? = null
    private var bootstrapTimerJob: Job? = null
    private var bootstrapStartMillis: Long = 0L
    private val bootstrapActive = AtomicBoolean(false)
    @Volatile private var useSoftwareFilter: Boolean = false

    private val peerKeyHashByMac = ConcurrentHashMap<String, ByteArray>()
    private val macByKeyHashHex = ConcurrentHashMap<String, String>()
    private val seenPayloadsByMac = ConcurrentHashMap<String, ByteArray>()
    private val l2capConnections = ConcurrentHashMap<String, L2capConnection>()
    private val gattClients = ConcurrentHashMap<String, GattClientState>()
    private val phyGattByKeyHashHex = ConcurrentHashMap<String, BluetoothGatt>()
    private val gattServerSubscribersByMac = ConcurrentHashMap<String, Boolean>()
    private val connectionJobs = ConcurrentHashMap<String, Job>()
    private val lruOrder = LinkedHashMap<String, Unit>(16, 0.75f, true)
    private val lruLock = Any()

    private inner class L2capConnection(
        val socket: android.bluetooth.BluetoothSocket,
        val inputStream: InputStream = socket.inputStream,
        val outputStream: java.io.OutputStream = socket.outputStream,
        val writeMutex: Mutex = Mutex(),
        val latencyTracker: WriteLatencyTracker =
            WriteLatencyTracker(clock = { System.currentTimeMillis() }),
        val retryScheduler: L2capRetryScheduler = L2capRetryScheduler(),
    )

    private inner class GattClientState(val gatt: BluetoothGatt, val writeMutex: Mutex = Mutex()) {
        @Volatile var mtu: Int = 23
        @Volatile var controlWriteChar: BluetoothGattCharacteristic? = null
        @Volatile var dataWriteChar: BluetoothGattCharacteristic? = null
    }

    override suspend fun startAdvertisingAndScanning() {
        if (!isRunning.compareAndSet(false, true)) {
            throw IllegalStateException("AndroidBleTransport is already running")
        }
        if (btAdapter.state == BluetoothAdapter.STATE_TURNING_ON) awaitAdapterOn()
        if (btAdapter.state != BluetoothAdapter.STATE_ON) {
            isRunning.set(false)
            throw IllegalStateException("Bluetooth adapter not ON (state=${btAdapter.state})")
        }
        if (!hasRequiredPermissions()) {
            isRunning.set(false)
            throw IllegalStateException("Required BLE permissions not granted")
        }

        val server = btManager.openGattServer(context, GattServerCallback())
        if (server == null) {
            isRunning.set(false)
            throw IllegalStateException("openGattServer returned null")
        }
        gattServer = server
        server.addService(buildGattService())

        val serverSocket =
            withContext(Dispatchers.IO) { btAdapter.listenUsingInsecureL2capChannel() }
        l2capServerSocket = serverSocket
        val psm = serverSocket.psm

        val payload =
            AdvertisementCodec.AdvertisementPayload(
                protocolVersion = BleTransportConfig.PROTOCOL_VERSION,
                powerMode = powerTierFlow.value.ordinal,
                meshHash = localMeshHash,
                l2capPsm = psm.toUByte(),
                keyHash = identity.keyHash,
            )
        val encodedPayload = AdvertisementCodec.encode(payload)
        advertisementServiceData = encodedPayload

        val advCallback =
            object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(
                    advertisingSet: AdvertisingSet?,
                    txPower: Int,
                    status: Int,
                ) {
                    if (status == ADVERTISE_SUCCESS) {
                        currentAdvertisingSet = advertisingSet
                        Log.d(TAG, "Advertising started txPower=$txPower psm=$psm")
                    } else {
                        Log.e(TAG, "Advertising failed status=$status")
                    }
                }

                override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet) {
                    Log.d(TAG, "Advertising set stopped")
                }
            }
        advertisingCallback = advCallback

        btAdapter.bluetoothLeAdvertiser?.startAdvertisingSet(
            AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(true)
                .setScannable(true)
                .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
                .build(),
            AdvertiseData.Builder()
                .addServiceData(ADVERTISEMENT_PARCEL_UUID, encodedPayload)
                .setIncludeDeviceName(false)
                .build(),
            null,
            null,
            null,
            advCallback,
        )

        if (bootstrapMode) {
            bootstrapStartMillis = System.currentTimeMillis()
            bootstrapActive.set(true)
            Logger.d(TAG, "Bootstrap mode active duration=60000ms")
        }

        startScan(powerTierFlow.value)

        if (bootstrapActive.get()) {
            bootstrapTimerJob = scope.launch {
                delay(BOOTSTRAP_DURATION_MILLIS)
                if (bootstrapActive.compareAndSet(true, false)) {
                    val elapsedMillis = System.currentTimeMillis() - bootstrapStartMillis
                    Logger.d(TAG, "Bootstrap mode ended elapsed=${elapsedMillis}ms reason=timeout")
                    if (isRunning.get()) {
                        stopScan()
                        startScan(powerTierFlow.value)
                    }
                }
            }
        }

        scanRestartJob = scope.launch {
            delay(SCAN_RESTART_INTERVAL_MS)
            while (isRunning.get()) {
                Log.d(TAG, "Restarting scan (25-min timer)")
                stopScan()
                startScan(powerTierFlow.value)
                delay(SCAN_RESTART_INTERVAL_MS)
            }
        }

        powerObserverJob = scope.launch {
            powerTierFlow.collect { tier ->
                if (isRunning.get()) {
                    if (bootstrapActive.get()) {
                        Log.d(TAG, "Power tier → $tier (deferred, bootstrap active)")
                    } else {
                        Log.d(TAG, "Power tier → $tier")
                        stopScan()
                        startScan(tier)
                    }
                }
            }
        }

        registerBtStateReceiver()
        launchL2capAcceptLoop(serverSocket)
        Log.d(TAG, "startAdvertisingAndScanning complete psm=$psm meshHash=$localMeshHash")
    }

    override suspend fun stopAll() {
        if (!isRunning.compareAndSet(true, false)) return
        Log.d(TAG, "stopAll() begin")
        val result = runCatching {
            withTimeout(STOP_ALL_TIMEOUT_MS) {
                bootstrapTimerJob?.cancel()
                bootstrapTimerJob = null
                bootstrapActive.set(false)
                scanRestartJob?.cancel()
                powerObserverJob?.cancel()
                stopScan()
                advertisingCallback?.let {
                    btAdapter.bluetoothLeAdvertiser?.stopAdvertisingSet(it)
                    advertisingCallback = null
                }
                l2capConnections.keys.toList().forEach { closeL2capConnection(it) }
                phyGattByKeyHashHex.values.forEach {
                    it.disconnect()
                    it.close()
                }
                phyGattByKeyHashHex.clear()
                gattClients.keys.toList().forEach { closeGattClient(it) }
                gattServer?.close()
                gattServer = null
                l2capAcceptJob?.cancel()
                withContext(Dispatchers.IO) { l2capServerSocket?.close() }
                l2capServerSocket = null
                btStateReceiver?.let {
                    runCatching { context.unregisterReceiver(it) }
                    btStateReceiver = null
                }
            }
        }
        if (result.isFailure)
            Log.e(TAG, "$BLE_STACK_UNRESPONSIVE stopAll() timed out after ${STOP_ALL_TIMEOUT_MS}ms")
        peerKeyHashByMac.clear()
        macByKeyHashHex.clear()
        seenPayloadsByMac.clear()
        gattServerSubscribersByMac.clear()
        synchronized(lruLock) { lruOrder.clear() }
        Log.d(TAG, "stopAll() complete")
    }

    override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray): SendResult {
        val hex = bytesToHex(peerId)
        l2capConnections[hex]?.let {
            return sendViaL2cap(hex, it, data)
        }
        gattClients[hex]?.let {
            return sendViaGatt(hex, it, data)
        }
        return SendResult.Failure("No active connection to peer $hex")
    }

    override suspend fun disconnect(peerId: ByteArray) {
        val hex = bytesToHex(peerId)
        l2capConnections[hex]?.let { conn ->
            runCatching {
                withContext(Dispatchers.IO) {
                    conn.writeMutex.withLock {
                        conn.outputStream.write(
                            L2capFrameCodec.encode(FrameType.CLOSE, ByteArray(0))
                        )
                        conn.outputStream.flush()
                    }
                }
            }
            closeL2capConnection(hex)
        }
        closeGattClient(hex)
        _peerLostEvents.emit(PeerLostEvent(peerId, PeerLostReason.MANUAL_DISCONNECT))
    }

    override suspend fun requestConnectionPriority(peerId: ByteArray, highPriority: Boolean) {
        val hex = bytesToHex(peerId)
        val priority =
            if (highPriority) BluetoothGatt.CONNECTION_PRIORITY_HIGH
            else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        (phyGattByKeyHashHex[hex] ?: gattClients[hex]?.gatt)?.requestConnectionPriority(priority)
    }

    private inner class GattServerCallback : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT server: connected mac=${device.address}")
                    // Scan filter fallback: unknown device means hardware UUID filter failed
                    if (!seenPayloadsByMac.containsKey(device.address) && !useSoftwareFilter) {
                        Logger.d(TAG, "Scan filter fallback activated (hardware filter broken)")
                        useSoftwareFilter = true
                        scope.launch {
                            if (isRunning.get()) {
                                stopScan()
                                startScan(powerTierFlow.value)
                            }
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gattServerSubscribersByMac.remove(device.address)
                    peerKeyHashByMac[device.address]?.let { kh ->
                        scope.launch {
                            _peerLostEvents.emit(PeerLostEvent(kh, PeerLostReason.CONNECTION_LOST))
                        }
                    }
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS)
                Log.d(TAG, "GATT server: service added ${service.uuid}")
            else Log.e(TAG, "GATT server: service add failed status=$status")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                0,
                null,
            )
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (responseNeeded)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val data = value ?: return
            val peerId = peerKeyHashByMac[device.address] ?: return
            scope.launch { _incomingData.emit(IncomingData(peerId, data)) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (responseNeeded)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true)
                gattServerSubscribersByMac[device.address] = true
            else gattServerSubscribersByMac.remove(device.address)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS)
                Log.w(TAG, "GATT server: notify failed status=$status")
        }
    }

    private inner class GattClientCallback(
        private val keyHashHex: String,
        private val connectedDeferred: CompletableDeferred<Boolean>,
        private val mtuDeferred: CompletableDeferred<Int>,
        private val servicesDeferred: CompletableDeferred<Boolean>,
    ) : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT client connected keyHash=$keyHashHex")
                    if (!connectedDeferred.isCompleted)
                        connectedDeferred.complete(status == BluetoothGatt.GATT_SUCCESS)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT client disconnected keyHash=$keyHashHex status=$status")
                    if (!connectedDeferred.isCompleted) connectedDeferred.complete(false)
                    if (gattClients.containsKey(keyHashHex)) {
                        scope.launch {
                            hexToBytes(keyHashHex)?.let {
                                _peerLostEvents.emit(
                                    PeerLostEvent(it, PeerLostReason.CONNECTION_LOST)
                                )
                            }
                            closeGattClient(keyHashHex)
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gattClients[keyHashHex]?.mtu = mtu
            if (!mtuDeferred.isCompleted)
                mtuDeferred.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.getService(SERVICE_UUID)?.let { svc ->
                    gattClients[keyHashHex]?.also { state ->
                        state.controlWriteChar = svc.getCharacteristic(CONTROL_WRITE_UUID)
                        state.dataWriteChar = svc.getCharacteristic(DATA_WRITE_UUID)
                        svc.getCharacteristic(CONTROL_NOTIFY_UUID)?.let {
                            enableNotification(gatt, it)
                        }
                        svc.getCharacteristic(DATA_NOTIFY_UUID)?.let {
                            enableNotification(gatt, it)
                        }
                    }
                }
                if (!servicesDeferred.isCompleted) servicesDeferred.complete(true)
            } else {
                if (!servicesDeferred.isCompleted) servicesDeferred.complete(false)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            dispatchIncomingGattData(keyHashHex, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            dispatchIncomingGattData(keyHashHex, characteristic.value ?: return)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS)
                Log.w(TAG, "GATT write failed status=$status keyHash=$keyHashHex")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.d(TAG, "GATT descriptor write status=$status keyHash=$keyHashHex")
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) adaptPhy(gatt, rssi, keyHashHex)
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            Log.d(TAG, "PHY updated txPhy=$txPhy rxPhy=$rxPhy keyHash=$keyHashHex")
        }

        private fun enableNotification(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            gatt.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }
    }

    private fun buildScanCallback(): ScanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) =
                processScanResult(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach {
                processScanResult(it)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed errorCode=$errorCode")
            }
        }

    private fun startScan(powerTier: PowerTier) {
        val scanMode =
            if (bootstrapActive.get()) {
                ScanSettings.SCAN_MODE_LOW_LATENCY
            } else {
                when (powerTier) {
                    PowerTier.PERFORMANCE -> ScanSettings.SCAN_MODE_LOW_LATENCY
                    PowerTier.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
                    PowerTier.POWER_SAVER -> ScanSettings.SCAN_MODE_LOW_POWER
                }
            }
        val filters =
            if (useSoftwareFilter) {
                emptyList()
            } else {
                listOf(
                    ScanFilter.Builder()
                        .setServiceData(ADVERTISEMENT_PARCEL_UUID, null, null)
                        .build()
                )
            }
        val cb = buildScanCallback()
        scanCallback = cb
        btAdapter.bluetoothLeScanner?.startScan(
            filters,
            ScanSettings.Builder().setScanMode(scanMode).setReportDelay(0).build(),
            cb,
        )
        Log.d(TAG, "BLE scan started scanMode=$scanMode softwareFilter=$useSoftwareFilter")
    }

    private fun stopScan() {
        scanCallback?.let {
            btAdapter.bluetoothLeScanner?.stopScan(it)
            scanCallback = null
        }
        Log.d(TAG, "BLE scan stopped")
    }

    private fun processScanResult(result: ScanResult) {
        val serviceData = result.scanRecord?.getServiceData(ADVERTISEMENT_PARCEL_UUID) ?: return
        if (serviceData.size != 16) return
        val mac = result.device.address
        seenPayloadsByMac[mac]?.let { if (it.contentEquals(serviceData)) return }
        seenPayloadsByMac[mac] = serviceData
        val payload =
            runCatching { AdvertisementCodec.decode(serviceData) }
                .getOrElse {
                    return
                }
        if (payload.protocolVersion != BleTransportConfig.PROTOCOL_VERSION) return
        if (!MeshHashFilter.matches(payload.meshHash, localMeshHash)) return
        if (payload.keyHash.contentEquals(identity.keyHash)) return

        val keyHashHex = bytesToHex(payload.keyHash)
        peerKeyHashByMac[mac] = payload.keyHash.copyOf()
        macByKeyHashHex[keyHashHex] = mac

        scope.launch {
            _advertisementEvents.emit(
                AdvertisementEvent(
                    peerId = payload.keyHash.copyOf(),
                    serviceData = serviceData,
                    rssi = result.rssi,
                )
            )
            // End bootstrap mode on first peer discovered
            if (bootstrapActive.compareAndSet(true, false)) {
                val elapsedMillis = System.currentTimeMillis() - bootstrapStartMillis
                bootstrapTimerJob?.cancel()
                bootstrapTimerJob = null
                Logger.d(TAG, "Bootstrap mode ended elapsed=${elapsedMillis}ms reason=peer_found")
                if (isRunning.get()) {
                    stopScan()
                    startScan(powerTierFlow.value)
                }
            }
        }

        val effectiveSlots = oemSlotTracker.effectiveSlots(oemKey())
        val currentConnections = l2capConnections.size + gattClients.size
        if (
            !connectionJobs.containsKey(keyHashHex) &&
                !l2capConnections.containsKey(keyHashHex) &&
                !gattClients.containsKey(keyHashHex) &&
                effectiveSlots > currentConnections &&
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
            val device = result.device
            val job = scope.launch {
                if (stagger > 0) delay(stagger)
                evictLruIfFull()
                if (!config.forceGatt && (psm != 0 || config.forceL2cap))
                    initiateL2cap(device, psm, keyHashHex)
                else initiateGattClient(device, keyHashHex)
            }
            connectionJobs[keyHashHex] = job
            job.invokeOnCompletion { connectionJobs.remove(keyHashHex) }
        }
    }

    private fun launchL2capAcceptLoop(serverSocket: BluetoothServerSocket) {
        l2capAcceptJob =
            scope.launch(Dispatchers.IO) {
                Log.d(TAG, "L2CAP accept loop started psm=${serverSocket.psm}")
                while (isRunning.get()) {
                    val socket =
                        runCatching { serverSocket.accept() }
                            .getOrElse { e ->
                                if (isRunning.get()) Log.w(TAG, "L2CAP accept error: ${e.message}")
                                return@launch
                            }
                    handleIncomingL2cap(socket)
                }
            }
    }

    private fun handleIncomingL2cap(socket: android.bluetooth.BluetoothSocket) {
        val mac =
            socket.remoteDevice?.address
                ?: run {
                    socket.close()
                    return
                }
        val keyHash =
            peerKeyHashByMac[mac]
                ?: run {
                    Log.w(TAG, "L2CAP inbound unknown MAC $mac")
                    socket.close()
                    return
                }
        val hex = bytesToHex(keyHash)
        // OemSlotTracker gating: reject inbound if at effective slot capacity
        val effectiveSlots = oemSlotTracker.effectiveSlots(oemKey())
        val currentConnections = l2capConnections.size + gattClients.size
        if (effectiveSlots <= currentConnections) {
            Log.w(
                TAG,
                "OemSlotTracker: inbound L2CAP rejected at capacity" +
                    " oemKey=${oemKey()} effectiveSlots=$effectiveSlots" +
                    " connections=$currentConnections",
            )
            socket.close()
            return
        }
        Log.d(TAG, "L2CAP inbound from $hex mac=$mac")
        registerL2capConnection(hex, socket)
    }

    private suspend fun initiateL2cap(device: BluetoothDevice, psm: Int, keyHashHex: String) {
        val socket =
            runCatching {
                    withTimeout(L2CAP_CONNECT_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            device.createInsecureL2capChannel(psm).also { it.connect() }
                        }
                    }
                }
                .getOrElse { e ->
                    Log.w(TAG, "L2CAP connect failed $keyHashHex: ${e.message}")
                    oemSlotTracker.recordFailure(oemKey())
                    Logger.d(
                        TAG,
                        "OemSlotTracker reduced ${oemKey()}" +
                            " slots=${oemSlotTracker.effectiveSlots(oemKey())}",
                    )
                    scheduleL2capRetry(device, psm, keyHashHex)
                    return
                }
        registerL2capConnection(keyHashHex, socket)
    }

    private fun registerL2capConnection(
        keyHashHex: String,
        socket: android.bluetooth.BluetoothSocket,
    ) {
        val conn = L2capConnection(socket)
        l2capConnections[keyHashHex] = conn
        trackLru(keyHashHex)
        scope.launch { l2capReadLoop(keyHashHex, conn) }
        scope.launch { l2capKeepalive(keyHashHex, conn) }
        macByKeyHashHex[keyHashHex]?.let {
            launchPhyGatt(btAdapter.getRemoteDevice(it), keyHashHex)
        }
    }

    private suspend fun l2capReadLoop(keyHashHex: String, conn: L2capConnection) {
        try {
            val headerBuf = ByteArray(3)
            while (isRunning.get() && l2capConnections.containsKey(keyHashHex)) {
                val headerOk =
                    try {
                        withTimeout(L2CAP_RECEIVE_TIMEOUT_MS) {
                            withContext(Dispatchers.IO) { readFully(conn.inputStream, headerBuf) }
                        }
                    } catch (_: TimeoutCancellationException) {
                        Log.w(TAG, "L2CAP timeout $keyHashHex — GATT fallback")
                        hexToBytes(keyHashHex)?.let {
                            _peerLostEvents.emit(PeerLostEvent(it, PeerLostReason.CONNECTION_LOST))
                        }
                        break
                    }
                if (!headerOk) break
                val frameType = FrameType.fromByte(headerBuf[0]) ?: break
                val length =
                    (headerBuf[1].toInt() and 0xFF) or ((headerBuf[2].toInt() and 0xFF) shl 8)
                val payloadBytes =
                    if (length > 0) {
                        val buf = ByteArray(length)
                        if (!withContext(Dispatchers.IO) { readFully(conn.inputStream, buf) }) break
                        buf
                    } else ByteArray(0)
                when (frameType) {
                    FrameType.DATA ->
                        if (payloadBytes.isNotEmpty()) {
                            val peerId = hexToBytes(keyHashHex) ?: continue
                            _incomingData.emit(IncomingData(peerId, payloadBytes))
                        }
                    FrameType.ACK -> {
                        /* no-op */
                    }
                    FrameType.CLOSE -> {
                        Log.d(TAG, "L2CAP CLOSE frame from $keyHashHex")
                        break
                    }
                }
            }
        } catch (_: IOException) {
            Log.w(TAG, "L2CAP read error $keyHashHex")
        } finally {
            closeL2capConnection(keyHashHex)
            if (isRunning.get())
                hexToBytes(keyHashHex)?.let {
                    _peerLostEvents.emit(PeerLostEvent(it, PeerLostReason.CONNECTION_LOST))
                }
        }
    }

    private suspend fun l2capKeepalive(keyHashHex: String, conn: L2capConnection) {
        while (isRunning.get() && l2capConnections.containsKey(keyHashHex)) {
            delay(KEEPALIVE_INTERVAL_MS)
            if (!l2capConnections.containsKey(keyHashHex)) break
            runCatching {
                withContext(Dispatchers.IO) {
                    conn.writeMutex.withLock {
                        conn.outputStream.write(
                            L2capFrameCodec.encode(FrameType.DATA, ByteArray(0))
                        )
                        conn.outputStream.flush()
                    }
                }
            }
        }
    }

    private suspend fun sendViaL2cap(
        keyHashHex: String,
        conn: L2capConnection,
        data: ByteArray,
    ): SendResult =
        runCatching {
                val start = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    conn.writeMutex.withLock {
                        conn.outputStream.write(L2capFrameCodec.encode(FrameType.DATA, data))
                        conn.outputStream.flush()
                    }
                }
                if (conn.latencyTracker.recordWrite(System.currentTimeMillis() - start)) {
                    Log.w(TAG, "L2CAP backpressure $keyHashHex — GATT fallback")
                    closeL2capConnection(keyHashHex)
                    hexToBytes(keyHashHex)?.let {
                        _peerLostEvents.emit(PeerLostEvent(it, PeerLostReason.CONNECTION_LOST))
                    }
                    return SendResult.Failure("L2CAP backpressure $keyHashHex")
                }
                SendResult.Success
            }
            .getOrElse { e -> SendResult.Failure("L2CAP write error: ${e.message}") }

    private fun closeL2capConnection(keyHashHex: String) {
        l2capConnections.remove(keyHashHex)?.let { conn ->
            scope.launch(Dispatchers.IO) { runCatching { conn.socket.close() } }
            Log.d(TAG, "L2CAP closed $keyHashHex")
        }
    }

    private fun scheduleL2capRetry(device: BluetoothDevice, psm: Int, keyHashHex: String) {
        val delayMillis = L2capRetryScheduler().nextDelayMillis() ?: return
        scope.launch {
            delay(delayMillis)
            if (isRunning.get() && !l2capConnections.containsKey(keyHashHex))
                initiateL2cap(device, psm, keyHashHex)
        }
    }

    private suspend fun initiateGattClient(device: BluetoothDevice, keyHashHex: String) {
        val connDef = CompletableDeferred<Boolean>()
        val mtuDef = CompletableDeferred<Int>()
        val svcDef = CompletableDeferred<Boolean>()
        val gatt =
            device.connectGatt(
                context,
                false,
                GattClientCallback(keyHashHex, connDef, mtuDef, svcDef),
                BluetoothDevice.TRANSPORT_LE,
            )
                ?: run {
                    Log.e(TAG, "connectGatt null $keyHashHex")
                    return
                }
        gattClients[keyHashHex] = GattClientState(gatt)
        if (
            !runCatching { withTimeout(GATT_CONNECT_TIMEOUT_MS) { connDef.await() } }
                .getOrElse { false }
        ) {
            Log.w(TAG, "GATT connect failed $keyHashHex")
            closeGattClient(keyHashHex)
            return
        }
        gatt.requestMtu(MTU_REQUEST)
        val mtu =
            runCatching { withTimeout(GATT_MTU_TIMEOUT_MS) { mtuDef.await() } }.getOrElse { 23 }
        if (mtu < MIN_EFFECTIVE_MTU)
            Log.w(TAG, "GATT MTU $mtu < $MIN_EFFECTIVE_MTU for $keyHashHex")
        gatt.discoverServices()
        if (
            !runCatching { withTimeout(GATT_DISCOVER_TIMEOUT_MS) { svcDef.await() } }
                .getOrElse { false }
        ) {
            Log.w(TAG, "GATT service discovery failed $keyHashHex")
            closeGattClient(keyHashHex)
            return
        }
        trackLru(keyHashHex)
        Log.d(TAG, "GATT client ready $keyHashHex mtu=$mtu")
    }

    private suspend fun sendViaGatt(
        keyHashHex: String,
        state: GattClientState,
        data: ByteArray,
    ): SendResult {
        val char =
            state.dataWriteChar ?: return SendResult.Failure("No data write char for $keyHashHex")
        for (attempt in 1..GATT_WRITE_RETRIES) {
            val code =
                runCatching {
                        withContext(Dispatchers.IO) {
                            state.writeMutex.withLock {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                    state.gatt.writeCharacteristic(
                                        char,
                                        data,
                                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                                    )
                                else {
                                    char.writeType =
                                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                    char.value = data
                                    if (state.gatt.writeCharacteristic(char)) 0 else -1
                                }
                            }
                        }
                    }
                    .getOrElse { -1 }
            if (code == 0) return SendResult.Success
            delay(100L shl (attempt - 1))
        }
        return SendResult.Failure(
            "GATT write failed after $GATT_WRITE_RETRIES attempts for $keyHashHex"
        )
    }

    private fun dispatchIncomingGattData(keyHashHex: String, data: ByteArray) {
        hexToBytes(keyHashHex)?.let { peerId ->
            scope.launch { _incomingData.emit(IncomingData(peerId, data)) }
        }
    }

    private fun closeGattClient(keyHashHex: String) {
        gattClients.remove(keyHashHex)?.let {
            runCatching {
                it.gatt.disconnect()
                it.gatt.close()
            }
            Log.d(TAG, "GATT client closed $keyHashHex")
        }
    }

    private fun launchPhyGatt(device: BluetoothDevice, keyHashHex: String) {
        scope.launch {
            device.connectGatt(
                context,
                false,
                object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt,
                        status: Int,
                        newState: Int,
                    ) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                phyGattByKeyHashHex[keyHashHex] = gatt
                                scope.launch { rssiPollLoop(gatt, keyHashHex) }
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                phyGattByKeyHashHex.remove(keyHashHex)
                                gatt.close()
                            }
                        }
                    }

                    override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) adaptPhy(gatt, rssi, keyHashHex)
                    }

                    override fun onPhyUpdate(
                        gatt: BluetoothGatt,
                        txPhy: Int,
                        rxPhy: Int,
                        status: Int,
                    ) {
                        Log.d(TAG, "PHY updated txPhy=$txPhy rxPhy=$rxPhy keyHash=$keyHashHex")
                    }
                },
                BluetoothDevice.TRANSPORT_LE,
            )
        }
    }

    private suspend fun rssiPollLoop(gatt: BluetoothGatt, keyHashHex: String) {
        while (isRunning.get() && phyGattByKeyHashHex.containsKey(keyHashHex)) {
            delay(KEEPALIVE_INTERVAL_MS)
            if (isRunning.get() && phyGattByKeyHashHex.containsKey(keyHashHex))
                gatt.readRemoteRssi()
        }
    }

    private fun adaptPhy(gatt: BluetoothGatt, rssi: Int, keyHashHex: String) {
        // PHY_LE_2M_MASK=2, PHY_LE_1M_MASK=1, PHY_OPTION_NO_PREFERRED=0 (BluetoothDevice constants,
        // API 26+)
        val (txPhy, rxPhy) = if (rssi > -60) 2 to 2 else 1 to 1
        gatt.setPreferredPhy(txPhy, rxPhy, 0)
        Log.d(TAG, "PHY adapt $keyHashHex rssi=$rssi txPhy=$txPhy rxPhy=$rxPhy")
    }

    private fun buildGattService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                CONTROL_WRITE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                    CONTROL_NOTIFY_UUID,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                )
                .also {
                    it.addDescriptor(
                        BluetoothGattDescriptor(
                            CCCD_UUID,
                            BluetoothGattDescriptor.PERMISSION_READ or
                                BluetoothGattDescriptor.PERMISSION_WRITE,
                        )
                    )
                }
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                DATA_WRITE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                    DATA_NOTIFY_UUID,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                )
                .also {
                    it.addDescriptor(
                        BluetoothGattDescriptor(
                            CCCD_UUID,
                            BluetoothGattDescriptor.PERMISSION_READ or
                                BluetoothGattDescriptor.PERMISSION_WRITE,
                        )
                    )
                }
        )
        return service
    }

    private fun registerBtStateReceiver() {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    when (
                        intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    ) {
                        BluetoothAdapter.STATE_TURNING_OFF ->
                            scope.launch {
                                Log.w(TAG, "BT adapter turning off")
                                (l2capConnections.keys + gattClients.keys).toSet().forEach { hex ->
                                    hexToBytes(hex)?.let {
                                        _peerLostEvents.emit(
                                            PeerLostEvent(it, PeerLostReason.CONNECTION_LOST)
                                        )
                                    }
                                }
                                if (isRunning.get()) stopAll()
                            }
                        BluetoothAdapter.STATE_ON ->
                            scope.launch {
                                Log.d(TAG, "BT adapter ON — restarting")
                                if (!isRunning.get()) startAdvertisingAndScanning()
                            }
                    }
                }
            }
        btStateReceiver = receiver
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    private suspend fun awaitAdapterOn() {
        val deferred = CompletableDeferred<Unit>()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (
                        intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                            BluetoothAdapter.STATE_ON
                    ) {
                        deferred.complete(Unit)
                        ctx.unregisterReceiver(this)
                    }
                }
            }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        runCatching { withTimeout(10_000L) { deferred.await() } }
    }

    private fun trackLru(keyHashHex: String) {
        synchronized(lruLock) { lruOrder[keyHashHex] = Unit }
    }

    private fun evictLruIfFull() {
        val evict: String?
        synchronized(lruLock) {
            evict =
                if (
                    l2capConnections.size + gattClients.size >= config.maxConnections &&
                        lruOrder.isNotEmpty()
                )
                    lruOrder.keys.first().also { lruOrder.remove(it) }
                else null
        }
        evict?.let { hex ->
            Log.d(TAG, "LRU eviction: $hex")
            closeL2capConnection(hex)
            closeGattClient(hex)
            hexToBytes(hex)?.let {
                scope.launch {
                    _peerLostEvents.emit(PeerLostEvent(it, PeerLostReason.CONNECTION_LOST))
                }
            }
        }
    }

    private fun readFully(stream: InputStream, buf: ByteArray): Boolean {
        var offset = 0
        while (offset < buf.size) {
            val n = stream.read(buf, offset, buf.size - offset)
            if (n < 0) return false
            offset += n
        }
        return true
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return runCatching {
                ByteArray(hex.length / 2) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }
            .getOrNull()
    }

    private fun hasRequiredPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            hasPermission(PERM_BLUETOOTH_SCAN) &&
                hasPermission(PERM_BLUETOOTH_ADVERTISE) &&
                hasPermission(PERM_BLUETOOTH_CONNECT)
        else hasPermission(PERM_BLUETOOTH) && hasPermission(PERM_BLUETOOTH_ADMIN)

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun oemKey(): String = "${Build.MANUFACTURER}|${Build.MODEL}|${Build.VERSION.SDK_INT}"
}
