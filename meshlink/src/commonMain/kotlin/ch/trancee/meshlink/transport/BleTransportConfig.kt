package ch.trancee.meshlink.transport

/**
 * Transport-level configuration shared by AndroidBleTransport and IosBleTransport.
 *
 * Holds only transport concerns (appId for mesh-hash derivation, connection limits, and force
 * flags). This is an internal type; the public API configuration (MeshLinkConfig) is introduced in
 * M004/S01.
 *
 * @param appId Application identifier used to derive the 16-bit mesh hash (FNV-1a XOR-fold).
 * @param maxConnections Maximum simultaneous peer connections (must be ≥ 1).
 * @param forceL2cap When true, skip GATT fallback and always open L2CAP even if PSM advertised as
 *   0x00.
 * @param forceGatt When true, skip L2CAP and always use GATT even if PSM is advertised.
 */
data class BleTransportConfig(
    val appId: String,
    val maxConnections: Int = 6,
    val forceL2cap: Boolean = false,
    val forceGatt: Boolean = false,
) {
    companion object {
        /** Protocol version embedded in every advertisement (spec §7, byte 0 [7:5]). */
        const val PROTOCOL_VERSION = 1
    }

    init {
        if (maxConnections < 1)
            throw IllegalArgumentException("maxConnections must be >= 1, got $maxConnections")
    }
}
