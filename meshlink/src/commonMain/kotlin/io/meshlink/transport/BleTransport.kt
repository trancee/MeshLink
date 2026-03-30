package io.meshlink.transport

import kotlinx.coroutines.flow.Flow

/**
 * System boundary abstraction for BLE hardware.
 * Production: AndroidBleTransport (androidMain) / IosBleTransport (iosMain).
 * Tests: VirtualMeshTransport.
 */
interface BleTransport {

    /** The local peer identifier for this device. */
    val localPeerId: ByteArray

    /**
     * Service data to include in BLE advertisements (encoded via [io.meshlink.wire.AdvertisementCodec]).
     * Set this before calling [startAdvertisingAndScanning]. Transports include this payload
     * in scan response data (Android/Linux) or advertisement data (iOS/macOS).
     */
    var advertisementServiceData: ByteArray

    /** Start advertising and scanning. */
    suspend fun startAdvertisingAndScanning()

    /** Stop all BLE activity. */
    suspend fun stopAll()

    /** Flow of discovered peer advertisement payloads. */
    val advertisementEvents: Flow<AdvertisementEvent>

    /** Flow of peer loss events (peer stopped advertising / timed out). */
    val peerLostEvents: Flow<PeerLostEvent>

    /** Send raw bytes to a connected peer. */
    suspend fun sendToPeer(peerId: ByteArray, data: ByteArray)

    /** Flow of raw bytes received from connected peers. */
    val incomingData: Flow<IncomingData>
}

data class AdvertisementEvent(
    val peerId: ByteArray,
    val advertisementPayload: ByteArray,
)

data class IncomingData(
    val peerId: ByteArray,
    val data: ByteArray,
)

data class PeerLostEvent(
    val peerId: ByteArray,
)
