package ch.trancee.meshlink.platform.ios

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
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSNumber
import platform.darwin.NSObject

internal class IosBleTransport(
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
    private val centralDelegate = IosCentralDelegate(this)
    private val peripheralDelegate = IosPeripheralDelegate(this)
    private var centralManager: CBCentralManager? = null
    private var peripheralManager: CBPeripheralManager? = null
    private var started: Boolean = false

    override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

    override suspend fun start(): Unit {
        started = true
        centralManager = CBCentralManager(delegate = centralDelegate, queue = null)
        peripheralManager = CBPeripheralManager(delegate = peripheralDelegate, queue = null)
    }

    override suspend fun pause(): Unit {
        centralManager?.stopScan()
        peripheralManager?.stopAdvertising()
        started = false
    }

    override suspend fun resume(): Unit {
        if (!started) {
            start()
        }
    }

    override suspend fun stop(): Unit {
        centralManager?.stopScan()
        peripheralManager?.stopAdvertising()
        discoveredPeers.clear()
        started = false
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        return when {
            !started -> TransportSendResult.Dropped("iOS BLE direct transport is not started")
            frame.peerId.value !in discoveredPeers -> TransportSendResult.Dropped("iOS BLE peer has not been discovered")
            else -> TransportSendResult.Dropped("iOS BLE discovery is active but direct connection transport is not implemented")
        }
    }

    internal fun startScanIfReady(central: CBCentralManager): Unit {
        if (!started || central.state != CBManagerStatePoweredOn) {
            return
        }
        central.scanForPeripheralsWithServices(
            serviceUUIDs = listOf(CBUUID.UUIDWithString(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID)),
            options = null,
        )
    }

    internal fun startAdvertisingIfReady(peripheral: CBPeripheralManager): Unit {
        if (!started || peripheral.state != CBManagerStatePoweredOn) {
            return
        }
        peripheral.startAdvertising(
            advertisementData = mapOf(
                CBAdvertisementDataServiceUUIDsKey to listOf(
                    CBUUID.UUIDWithString(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID),
                    CBUUID.UUIDWithString(discoveryPayload.payloadUuidString()),
                ),
            ),
        )
    }

    internal fun handleDiscoveredPeripheralServiceUuids(serviceUuids: List<CBUUID>): Unit {
        val encodedUuids = serviceUuids.map { uuid -> uuid.UUIDString.lowercase() }
        val payloadUuid = encodedUuids.firstOrNull { uuid -> uuid != BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID } ?: return
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
}

private class IosCentralDelegate(
    private val owner: IosBleTransport,
) : NSObject(), CBCentralManagerDelegateProtocol {
    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        owner.startScanIfReady(central)
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        val serviceUuids = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<CBUUID> ?: return
        owner.handleDiscoveredPeripheralServiceUuids(serviceUuids)
    }
}

private class IosPeripheralDelegate(
    private val owner: IosBleTransport,
) : NSObject(), CBPeripheralManagerDelegateProtocol {
    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        owner.startAdvertisingIfReady(peripheral)
    }
}
