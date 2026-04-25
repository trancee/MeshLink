package ch.trancee.meshlink.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestCoroutineScheduler

data class VirtualLink(
    val rssi: Int = -50,
    val packetLoss: Double = 0.0,
    val latencyMillis: Long = 0L,
)

data class SentFrame(val destination: ByteArray, val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SentFrame) return false
        return destination.contentEquals(other.destination) && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * destination.contentHashCode() + data.contentHashCode()
}

class VirtualMeshTransport(
    override val localPeerId: ByteArray,
    private val testScheduler: TestCoroutineScheduler,
) : BleTransport {

    override var advertisementServiceData: ByteArray = ByteArray(16)

    private val _advertisementEvents =
        MutableSharedFlow<AdvertisementEvent>(replay = 0, extraBufferCapacity = 64)
    override val advertisementEvents: Flow<AdvertisementEvent> = _advertisementEvents

    private val _peerLostEvents =
        MutableSharedFlow<PeerLostEvent>(replay = 0, extraBufferCapacity = 64)
    override val peerLostEvents: Flow<PeerLostEvent> = _peerLostEvents

    private val _incomingData =
        MutableSharedFlow<IncomingData>(replay = 0, extraBufferCapacity = 64)
    override val incomingData: Flow<IncomingData> = _incomingData

    private val links = mutableMapOf<ByteArrayKey, Pair<VirtualMeshTransport, VirtualLink>>()
    private val writeFailures = mutableSetOf<ByteArrayKey>()

    private val _sentFrames = mutableListOf<SentFrame>()
    val sentFrames: List<SentFrame>
        get() = _sentFrames.toList()

    private class ByteArrayKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ByteArrayKey) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** Creates a bidirectional link between this transport and [other]. */
    fun linkTo(other: VirtualMeshTransport, link: VirtualLink = VirtualLink()) {
        links[ByteArrayKey(other.localPeerId)] = Pair(other, link)
        other.links[ByteArrayKey(localPeerId)] = Pair(this, link)
    }

    /** Removes the bidirectional link between this transport and [other]. */
    fun unlink(other: VirtualMeshTransport) {
        links.remove(ByteArrayKey(other.localPeerId))
        other.links.remove(ByteArrayKey(localPeerId))
    }

    /** Emits an [AdvertisementEvent] on [advertisementEvents] as if the peer were discovered. */
    suspend fun simulateDiscovery(peerId: ByteArray, serviceData: ByteArray, rssi: Int) {
        _advertisementEvents.emit(AdvertisementEvent(peerId, serviceData, rssi))
    }

    /** Emits a [PeerLostEvent] on [peerLostEvents] as if the peer connection were dropped. */
    suspend fun simulatePeerLost(
        peerId: ByteArray,
        reason: PeerLostReason = PeerLostReason.CONNECTION_LOST,
    ) {
        _peerLostEvents.emit(PeerLostEvent(peerId, reason))
    }

    /**
     * Marks [peerId] so that the next [sendToPeer] call targeting it returns [SendResult.Failure].
     */
    fun simulateWriteFailure(peerId: ByteArray) {
        writeFailures.add(ByteArrayKey(peerId))
    }

    /** Clears a previously set write-failure for [peerId]. */
    fun clearWriteFailure(peerId: ByteArray) {
        writeFailures.remove(ByteArrayKey(peerId))
    }

    override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray): SendResult {
        if (ByteArrayKey(peerId) in writeFailures) {
            return SendResult.Failure("Simulated write failure")
        }
        val (targetTransport, _) =
            links[ByteArrayKey(peerId)]
                ?: return SendResult.Failure("Peer not linked: ${peerId.contentToString()}")
        val frame = L2capFrameCodec.encode(FrameType.DATA, data)
        _sentFrames.add(SentFrame(peerId.copyOf(), frame.copyOf()))
        targetTransport._incomingData.emit(IncomingData(localPeerId.copyOf(), data))
        return SendResult.Success
    }

    /**
     * Advances the [TestCoroutineScheduler] to an absolute [timeMillis] timestamp. Throws
     * [IllegalArgumentException] if [timeMillis] is in the past.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun advanceTo(timeMillis: Long) {
        if (timeMillis < testScheduler.currentTime) {
            throw IllegalArgumentException("Cannot go back in time")
        }
        testScheduler.advanceTimeBy(timeMillis - testScheduler.currentTime)
    }

    override suspend fun startAdvertisingAndScanning() {}

    override suspend fun stopAll() {}
}
