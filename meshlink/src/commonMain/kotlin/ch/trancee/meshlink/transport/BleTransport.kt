package ch.trancee.meshlink.transport

import kotlinx.coroutines.flow.Flow

interface BleTransport {
    val localPeerId: ByteArray
    var advertisementServiceData: ByteArray

    suspend fun startAdvertisingAndScanning()

    suspend fun stopAll()

    val advertisementEvents: Flow<AdvertisementEvent>
    val peerLostEvents: Flow<PeerLostEvent>

    suspend fun sendToPeer(peerId: ByteArray, data: ByteArray): SendResult

    suspend fun disconnect(peerId: ByteArray)

    val incomingData: Flow<IncomingData>
}

sealed interface SendResult {
    data object Success : SendResult

    data class Failure(val reason: String) : SendResult
}

data class AdvertisementEvent(val peerId: ByteArray, val serviceData: ByteArray, val rssi: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdvertisementEvent) return false
        return peerId.contentEquals(other.peerId) &&
            serviceData.contentEquals(other.serviceData) &&
            rssi == other.rssi
    }

    override fun hashCode(): Int =
        31 * (31 * peerId.contentHashCode() + serviceData.contentHashCode()) + rssi.hashCode()
}

enum class PeerLostReason {
    CONNECTION_LOST,
    TIMEOUT,
    MANUAL_DISCONNECT,
}

data class PeerLostEvent(val peerId: ByteArray, val reason: PeerLostReason) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerLostEvent) return false
        return peerId.contentEquals(other.peerId) && reason == other.reason
    }

    override fun hashCode(): Int = 31 * peerId.contentHashCode() + reason.hashCode()
}

data class IncomingData(val peerId: ByteArray, val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingData) return false
        return peerId.contentEquals(other.peerId) && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * peerId.contentHashCode() + data.contentHashCode()
}
