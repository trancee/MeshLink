package ch.trancee.meshlink.routing

data class PeerInfo(
    val peerId: ByteArray,
    val powerMode: Byte,
    val rssi: Int,
    val lossRate: Double,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerInfo) return false
        return peerId.contentEquals(other.peerId) &&
            powerMode == other.powerMode &&
            rssi == other.rssi &&
            lossRate == other.lossRate
    }

    override fun hashCode(): Int {
        var result = peerId.contentHashCode()
        result = 31 * result + powerMode.hashCode()
        result = 31 * result + rssi.hashCode()
        result = 31 * result + lossRate.hashCode()
        return result
    }
}
