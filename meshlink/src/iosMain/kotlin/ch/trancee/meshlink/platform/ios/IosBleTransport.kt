package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BlePowerMode
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
    private val discoveryServiceUuid: String = BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID
    private val gattServiceUuid: String = BleDiscoveryContract.GATT_FALLBACK_SERVICE_UUID
    private val gattCharacteristicUuids: List<String> = BleDiscoveryContract.GATT_CHARACTERISTIC_UUIDS
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 16)
    private var started: Boolean = false

    override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

    override suspend fun start(): Unit {
        discoveryPayload.payloadUuidString()
        started = true
    }

    override suspend fun pause(): Unit {
        started = false
    }

    override suspend fun resume(): Unit {
        started = true
    }

    override suspend fun stop(): Unit {
        started = false
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        return if (started) {
            TransportSendResult.Dropped("iOS BLE discovery is active but direct connection transport is not implemented")
        } else {
            TransportSendResult.Dropped("iOS BLE direct transport is not started")
        }
    }
}
