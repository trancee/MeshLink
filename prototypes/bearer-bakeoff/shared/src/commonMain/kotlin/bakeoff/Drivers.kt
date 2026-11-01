package bakeoff

class ConnectRequest(
    val phy: Phy,
    val excludedAddresses: Set<String>,
    val scanTimeoutMs: Long,
)

/** Platform central role: scan for the bakeoff service and bring up a GATT data link. */
interface CentralDriver {
    /**
     * Marks on [clock]: `scan_start`, `discovered`, `connected`, `services`, `mtu`, `subscribed`.
     * Throws on failure; the message is recorded verbatim.
     */
    suspend fun connect(request: ConnectRequest, clock: PhaseClock): CentralConnection
}

interface CentralConnection {
    val address: String
    val mtu: Int
    val gattLink: GattChunkLink

    /** Human-readable PHY report (Android onPhyRead/onPhyUpdate), or `unavailable` (iOS). */
    fun phyReport(): String

    suspend fun readControl(): ByteArray
    suspend fun readPsm(): Int
    suspend fun openL2cap(psm: Int, secure: Boolean): ByteLink
    suspend fun disconnect()
    val isConnected: Boolean
}

interface PeripheralListener {
    fun onGattLink(peerAddress: String, link: GattChunkLink)
    fun onL2capLink(peerAddress: String, link: ByteLink)
    fun onEvent(name: String, peerAddress: String?, detail: String)
}

/** Platform peripheral role: advertise the service UUID, serve the GATT characteristics and an L2CAP server. */
interface PeripheralDriver {
    /** Returns the PSM of the published L2CAP channel, or null when unsupported. */
    suspend fun start(identity: PeerIdentity, secureL2cap: Boolean, listener: PeripheralListener): Int?
    fun stop()
}
