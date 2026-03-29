package io.meshlink.transport

import android.annotation.SuppressLint
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
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Production Android BLE transport for MeshLink.
 *
 * Uses BLE advertising + scanning for peer discovery and a GATT server/client
 * pair for bidirectional data exchange.  Permission checks are expected to be
 * performed by [PermissionChecker] before [startAdvertisingAndScanning] is called.
 *
 * @param context Android application or service context (used for BluetoothManager
 *                and SharedPreferences access).
 */
@SuppressLint("MissingPermission")
class AndroidBleTransport(
    private val context: Context,
) : BleTransport {

    companion object {
        private const val TAG = "MeshLink.BLE"

        /**
         * Enable verbose BLE debug logging.
         * Set to `true` in debug builds to trace scanning, advertising,
         * GATT connections, and data flow. Disable for release.
         *
         * ```kotlin
         * AndroidBleTransport.debugLogging = BuildConfig.DEBUG
         * ```
         */
        internal var debugLogging: Boolean = false
    }

    private inline fun logD(msg: () -> String) {
        if (debugLogging) Log.d(TAG, msg())
    }

    private inline fun logW(msg: () -> String) {
        if (debugLogging) Log.w(TAG, msg())
    }

    // --- System services ---

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    // --- Peer identity ---

    override val localPeerId: ByteArray = loadOrGenerateLocalPeerId()

    // --- Event flows ---

    private val _advertisementEvents = MutableSharedFlow<AdvertisementEvent>(extraBufferCapacity = 64)
    private val _incomingData = MutableSharedFlow<IncomingData>(extraBufferCapacity = 64)
    private val _peerLostEvents = MutableSharedFlow<PeerLostEvent>(extraBufferCapacity = 64)

    override val advertisementEvents: Flow<AdvertisementEvent> = _advertisementEvents.asSharedFlow()
    override val incomingData: Flow<IncomingData> = _incomingData.asSharedFlow()
    override val peerLostEvents: Flow<PeerLostEvent> = _peerLostEvents.asSharedFlow()

    // --- GATT UUIDs ---

    private val serviceUuid = UUID.fromString(GattConstants.SERVICE_UUID)
    private val controlWriteUuid = UUID.fromString(GattConstants.CONTROL_WRITE_UUID)
    private val controlNotifyUuid = UUID.fromString(GattConstants.CONTROL_NOTIFY_UUID)
    private val dataWriteUuid = UUID.fromString(GattConstants.DATA_WRITE_UUID)
    private val dataNotifyUuid = UUID.fromString(GattConstants.DATA_NOTIFY_UUID)
    private val cccdUuid = UUID.fromString(BleConstants.CCCD_UUID)

    // --- Peer tracking ---

    /** address → last-seen wall-clock ms */
    private val peerLastSeen = ConcurrentHashMap<String, Long>()

    /** address → BluetoothDevice (from scan or GATT server connection) */
    private val peerDevices = ConcurrentHashMap<String, BluetoothDevice>()

    // --- GATT server ---

    private var gattServer: BluetoothGattServer? = null

    // --- BLE advertiser & scanner ---

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    // --- GATT client connection pool ---

    private val activeConnections = ConcurrentHashMap<String, BluetoothGatt>()
    private val connectionMutexes = ConcurrentHashMap<String, Mutex>()
    private val pendingWrites = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    // --- Coroutine lifecycle ---

    private var scope: CoroutineScope? = null

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override suspend fun startAdvertisingAndScanning() {
        logD { "startAdvertisingAndScanning() — adapter enabled=${bluetoothAdapter?.isEnabled}" }
        val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = transportScope

        startGattServer()
        startAdvertising()
        startScanning()
        startPeerTimeoutMonitor(transportScope)
        logD { "BLE stack initialized — GATT server, advertiser, scanner all started" }
    }

    override suspend fun stopAll() {
        logD { "stopAll() — tearing down BLE stack" }
        stopScanning()
        stopAdvertising()
        stopGattServer()
        closeAllConnections()
        scope?.cancel()
        scope = null
        peerLastSeen.clear()
        peerDevices.clear()
    }

    // =========================================================================
    // Local Peer ID (persisted across restarts via SharedPreferences)
    // =========================================================================

    private fun loadOrGenerateLocalPeerId(): ByteArray {
        val prefs = context.getSharedPreferences(
            BleConstants.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val existing = prefs.getString(BleConstants.PREFS_KEY_LOCAL_PEER_ID, null)
        if (existing != null) {
            return hexToBytes(existing)
        }
        val newId = ByteArray(BleConstants.LOCAL_PEER_ID_LENGTH)
        SecureRandom().nextBytes(newId)
        prefs.edit()
            .putString(BleConstants.PREFS_KEY_LOCAL_PEER_ID, bytesToHex(newId))
            .apply()
        return newId
    }

    // =========================================================================
    // Advertising
    // =========================================================================

    private fun startAdvertising() {
        logD { "startAdvertising() entering..." }

        // Stop any prior advertising to avoid ALREADY_STARTED errors on restart
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            logW { "⚠️ stopAdvertising cleanup failed: ${e.message}" }
        }

        val adv = bluetoothAdapter.bluetoothLeAdvertiser
        if (adv == null) {
            logW { "⚠️ BLE advertiser unavailable — device may not support peripheral mode" }
            return
        }
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(BleConstants.ADVERTISE_MODE)
            .setTxPowerLevel(BleConstants.ADVERTISE_TX_POWER)
            .setConnectable(BleConstants.ADVERTISE_CONNECTABLE)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUuid))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        try {
            logD { "startAdvertising() — service=$serviceUuid, mode=${BleConstants.ADVERTISE_MODE}, connectable=true" }
            adv.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            logW { "⚠️ startAdvertising() threw: ${e::class.simpleName}: ${e.message}" }
        }
    }

    private fun stopAdvertising() {
        logD { "stopAdvertising()" }
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            logD {
                val mode = when (settingsInEffect?.mode) {
                    AdvertiseSettings.ADVERTISE_MODE_LOW_POWER -> "LOW_POWER"
                    AdvertiseSettings.ADVERTISE_MODE_BALANCED -> "BALANCED"
                    AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY -> "LOW_LATENCY"
                    else -> "unknown(${settingsInEffect?.mode})"
                }
                val tx = when (settingsInEffect?.txPowerLevel) {
                    AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW -> "ULTRA_LOW"
                    AdvertiseSettings.ADVERTISE_TX_POWER_LOW -> "LOW"
                    AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM -> "MEDIUM"
                    AdvertiseSettings.ADVERTISE_TX_POWER_HIGH -> "HIGH"
                    else -> "unknown(${settingsInEffect?.txPowerLevel})"
                }
                "✅ Advertising started — mode=$mode, txPower=$tx, connectable=${settingsInEffect?.isConnectable}, service=$serviceUuid"
            }
        }

        override fun onStartFailure(errorCode: Int) {
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
                ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                else -> "UNKNOWN($errorCode)"
            }
            logW { "⚠️ Advertising failed: $reason (code=$errorCode)" }
        }
    }

    // =========================================================================
    // Scanning
    // =========================================================================

    private fun startScanning() {
        logD { "startScanning() entering..." }

        // Check if Location Services are enabled — required for BLE scanning on most Android devices
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val locationEnabled = locationManager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true ||
            locationManager?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true
        if (!locationEnabled) {
            logW {
                "⚠️ Location Services are OFF — BLE scanning will not return results on most Android devices. Enable GPS/Location."
            }
        }

        // Stop any prior scan to avoid ALREADY_STARTED errors on restart
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            logW { "⚠️ stopScan cleanup failed: ${e.message}" }
        }

        val sc = bluetoothAdapter.bluetoothLeScanner
        if (sc == null) {
            logW { "⚠️ BLE scanner unavailable — Bluetooth may be off" }
            return
        }
        scanner = sc

        val settings = ScanSettings.Builder()
            .setScanMode(BleConstants.SCAN_MODE)
            .setReportDelay(BleConstants.SCAN_REPORT_DELAY_MS)
            .build()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()

        try {
            logD { "startScanning() — filter=service:$serviceUuid, mode=${BleConstants.SCAN_MODE}" }
            sc.startScan(listOf(filter), settings, scanCallback)
            logD { "✅ Scan started" }
        } catch (e: Exception) {
            logW { "⚠️ startScan() threw: ${e::class.simpleName}: ${e.message}" }
        }
    }

    private fun stopScanning() {
        logD { "stopScanning()" }
        scanner?.stopScan(scanCallback)
        scanner = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            logD { "onBatchScanResults: ${results.size} results" }
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "REGISTRATION_FAILED"
                SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                else -> "UNKNOWN($errorCode)"
            }
            logW { "⚠️ Scan failed: $reason (code=$errorCode)" }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val address = device.address
        val peerId = addressToBytes(address)
        val isNew = !peerLastSeen.containsKey(address)

        peerLastSeen[address] = System.currentTimeMillis()
        peerDevices[address] = device

        val scanRecord = result.scanRecord
        val payload = scanRecord
            ?.getServiceData(ParcelUuid(serviceUuid))
            ?: ByteArray(0)

        if (isNew) {
            val serviceUuids = scanRecord?.serviceUuids?.joinToString { it.uuid.toString() } ?: "none"
            val deviceName = scanRecord?.deviceName ?: "unknown"
            val serviceDataKeys = scanRecord?.serviceData?.keys?.joinToString { it.uuid.toString() } ?: "none"
            logD {
                "📡 NEW peer discovered: $address ($deviceName), RSSI=${result.rssi}, " +
                    "serviceUUIDs=[$serviceUuids], serviceDataKeys=[$serviceDataKeys], payload=${payload.size}B"
            }
        } else {
            // Suppress repeated "Peer seen" logs — they fire on every scan cycle
        }

        _advertisementEvents.tryEmit(AdvertisementEvent(peerId, payload))
    }

    // =========================================================================
    // Peer Timeout Monitor
    // =========================================================================

    private fun startPeerTimeoutMonitor(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(BleConstants.PEER_TIMEOUT_CHECK_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val timedOut = peerLastSeen.entries.filter { (_, lastSeen) ->
                    now - lastSeen > BleConstants.PEER_LOST_TIMEOUT_MS
                }
                for ((address, lastSeen) in timedOut) {
                    logD { "🔴 Peer timed out: $address (last seen ${now - lastSeen}ms ago)" }
                    peerLastSeen.remove(address)
                    peerDevices.remove(address)
                    _peerLostEvents.tryEmit(PeerLostEvent(addressToBytes(address)))
                    activeConnections.remove(address)?.close()
                }
                if (debugLogging && peerLastSeen.isNotEmpty()) {
                    logD { "Peer sweep: ${peerLastSeen.size} active peers tracked" }
                }
            }
        }
    }

    // =========================================================================
    // GATT Server (receives data from peers)
    // =========================================================================

    private fun startGattServer() {
        val server = bluetoothManager.openGattServer(context, gattServerCallback)
        if (server == null) {
            logW { "⚠️ Failed to open GATT server" }
            return
        }
        gattServer = server
        logD { "GATT server opened" }

        val service = BluetoothGattService(
            serviceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        service.addCharacteristic(
            BluetoothGattCharacteristic(
                controlWriteUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )

        service.addCharacteristic(
            BluetoothGattCharacteristic(
                controlNotifyUuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ).apply { addDescriptor(newCccdDescriptor()) },
        )

        service.addCharacteristic(
            BluetoothGattCharacteristic(
                dataWriteUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )

        service.addCharacteristic(
            BluetoothGattCharacteristic(
                dataNotifyUuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ).apply { addDescriptor(newCccdDescriptor()) },
        )

        server.addService(service)
        logD { "GATT service added: $serviceUuid (4 characteristics)" }
    }

    private fun newCccdDescriptor(): BluetoothGattDescriptor =
        BluetoothGattDescriptor(
            cccdUuid,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )

    private fun stopGattServer() {
        gattServer?.close()
        gattServer = null
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            logD { "GATT server: connection state change device=${device.address}, status=$status, newState=$newState" }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                peerDevices[device.address] = device
            }
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
            val data = value ?: return

            logD { "GATT server: write from ${device.address}, char=${characteristic.uuid}, ${data.size}B" }

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    data,
                )
            }

            when (characteristic.uuid) {
                controlWriteUuid, dataWriteUuid -> {
                    _incomingData.tryEmit(
                        IncomingData(addressToBytes(device.address), data),
                    )
                }
            }
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
            if (descriptor.uuid == cccdUuid && responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value,
                )
            }
        }
    }

    // =========================================================================
    // GATT Client (sends data to peers)
    // =========================================================================

    override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray) {
        val address = bytesToAddress(peerId)
        val mutex = connectionMutexes.getOrPut(address) { Mutex() }

        mutex.withLock {
            val gatt = activeConnections[address] ?: connectToDevice(address)
            writeToDataCharacteristic(gatt, address, data)
        }
    }

    private suspend fun connectToDevice(address: String): BluetoothGatt {
        logD { "connectToDevice: $address" }
        val device = peerDevices[address]
            ?: bluetoothAdapter.getRemoteDevice(address)

        val servicesReady = CompletableDeferred<BluetoothGatt>()

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                logD { "GATT client: $address state=$newState, status=$status" }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    activeConnections.remove(address)
                    gatt.close()
                    cancelPendingWrite(address, "GATT error (status=$status)")
                    if (!servicesReady.isCompleted) {
                        servicesReady.completeExceptionally(
                            BleTransportException("GATT error connecting to $address (status=$status)"),
                        )
                    }
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        gatt.requestMtu(BleConstants.PREFERRED_MTU)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        activeConnections.remove(address)
                        gatt.close()
                        cancelPendingWrite(address, "Disconnected from $address")
                        if (!servicesReady.isCompleted) {
                            servicesReady.completeExceptionally(
                                BleTransportException("Connection lost to $address"),
                            )
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                logD { "GATT client: MTU changed to $mtu for $address (status=$status)" }
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                logD { "GATT client: services discovered for $address (status=$status)" }
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    activeConnections[address] = gatt
                    servicesReady.complete(gatt)
                } else {
                    gatt.close()
                    servicesReady.completeExceptionally(
                        BleTransportException(
                            "Service discovery failed for $address (status=$status)",
                        ),
                    )
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                val deferred = pendingWrites.remove(address) ?: return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    deferred.complete(Unit)
                } else {
                    deferred.completeExceptionally(
                        BleTransportException("Write failed to $address (status=$status)"),
                    )
                }
            }
        }

        device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

        return withTimeout(BleConstants.GATT_CONNECTION_TIMEOUT_MS) {
            servicesReady.await()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun writeToDataCharacteristic(
        gatt: BluetoothGatt,
        address: String,
        data: ByteArray,
    ) {
        val service = gatt.getService(serviceUuid)
            ?: throw BleTransportException("MeshLink service not found on $address")
        val characteristic = service.getCharacteristic(dataWriteUuid)
            ?: throw BleTransportException("Data write characteristic not found on $address")

        val writeDeferred = CompletableDeferred<Unit>()
        pendingWrites[address] = writeDeferred

        characteristic.value = data
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        if (!gatt.writeCharacteristic(characteristic)) {
            pendingWrites.remove(address)
            throw BleTransportException("Write initiation failed for $address")
        }

        withTimeout(BleConstants.GATT_WRITE_TIMEOUT_MS) {
            writeDeferred.await()
        }
    }

    // =========================================================================
    // Connection cleanup
    // =========================================================================

    private fun closeAllConnections() {
        activeConnections.values.forEach { it.close() }
        activeConnections.clear()
        connectionMutexes.clear()
        pendingWrites.values.forEach {
            it.completeExceptionally(BleTransportException("Transport stopped"))
        }
        pendingWrites.clear()
    }

    private fun cancelPendingWrite(address: String, reason: String) {
        pendingWrites.remove(address)?.completeExceptionally(BleTransportException(reason))
    }

    // =========================================================================
    // Address ↔ ByteArray utilities
    // =========================================================================

    private fun addressToBytes(address: String): ByteArray =
        address.split(":").map { it.toInt(16).toByte() }.toByteArray()

    private fun bytesToAddress(bytes: ByteArray): String =
        bytes.joinToString(":") { "%02X".format(it) }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** Thrown when a BLE transport operation fails. */
class BleTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
