package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothManager
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
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BlePowerMode
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class AndroidBleTransport(
    private val context: Context,
    appId: String,
    advertisementKeyHash: ByteArray,
) : BleTransport {
    private val discoveryPayload = BleDiscoveryPayload(
        protocolVersion = 1,
        powerMode = BlePowerMode.BALANCED,
        meshHash = BleDiscoveryContract.computeMeshHash(appId),
        l2capPsm = 0u,
        keyHash = advertisementKeyHash,
    )
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 16)
    private val discoveredPeers: MutableMap<String, TransportMode> = linkedMapOf()
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var started: Boolean = false

    override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

    private val advertiseCallback = object : AdvertiseCallback() {}

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            result.scanRecord?.serviceUuids
                ?.map { it.uuid.toString() }
                ?.let(::handleAdvertisedUuids)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                result.scanRecord?.serviceUuids
                    ?.map { it.uuid.toString() }
                    ?.let(::handleAdvertisedUuids)
            }
        }
    }

    override suspend fun start(): Unit {
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            ?: error("BluetoothManager is unavailable")
        val adapter = bluetoothManager.adapter ?: error("BluetoothAdapter is unavailable")
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner
        scanner?.startScan(scanFilters(), scanSettings(), scanCallback)
        advertiser?.startAdvertising(advertiseSettings(), advertiseData(), advertiseCallback)
        started = true
    }

    override suspend fun pause(): Unit {
        stopScanningAndAdvertising()
        started = false
    }

    override suspend fun resume(): Unit {
        if (!started) {
            start()
        }
    }

    override suspend fun stop(): Unit {
        stopScanningAndAdvertising()
        discoveredPeers.clear()
        started = false
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        return when {
            !started -> TransportSendResult.Dropped("Android BLE direct transport is not started")
            frame.peerId.value !in discoveredPeers -> TransportSendResult.Dropped("Android BLE peer has not been discovered")
            else -> TransportSendResult.Dropped("Android BLE discovery is active but direct connection transport is not implemented")
        }
    }

    private fun advertiseData(): AdvertiseData {
        return AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid.fromString(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID))
            .addServiceUuid(ParcelUuid.fromString(discoveryPayload.payloadUuidString()))
            .build()
    }

    private fun advertiseSettings(): AdvertiseSettings {
        return AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
    }

    private fun scanFilters(): List<ScanFilter> {
        return listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID))
                .build(),
        )
    }

    private fun scanSettings(): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
    }

    private fun handleAdvertisedUuids(serviceUuids: List<String>): Unit {
        val payloadUuid = serviceUuids.firstOrNull { uuid -> uuid != BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID } ?: return
        val payload = runCatching { BleDiscoveryPayload.fromUuidString(payloadUuid) }.getOrNull() ?: return
        if (payload.meshHash != discoveryPayload.meshHash) {
            return
        }
        if (payload.keyHash.contentEquals(discoveryPayload.keyHash)) {
            return
        }
        val peerId = PeerId(payload.keyHash.toHexString())
        val mode = if (payload.l2capPsm.toInt() == 0) TransportMode.GATT else TransportMode.L2CAP
        val previousMode = discoveredPeers.put(peerId.value, mode)
        if (previousMode == null) {
            mutableEvents.tryEmit(TransportEvent.PeerDiscovered(peerId = peerId, transportMode = mode))
        } else if (previousMode != mode) {
            mutableEvents.tryEmit(TransportEvent.TransportModeChanged(peerId = peerId, transportMode = mode))
        }
    }

    private fun stopScanningAndAdvertising(): Unit {
        scanner?.stopScan(scanCallback)
        advertiser?.stopAdvertising(advertiseCallback)
    }
}
