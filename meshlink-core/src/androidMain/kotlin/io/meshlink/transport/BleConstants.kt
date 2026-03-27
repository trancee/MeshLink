package io.meshlink.transport

/**
 * Android-specific BLE constants for MeshLink transport.
 */
object BleConstants {

    // --- Scanning ---

    /** ScanSettings.SCAN_MODE_LOW_LATENCY (highest duty cycle, fastest discovery) */
    const val SCAN_MODE = 2

    /** Batch scan report delay in milliseconds (0 = immediate delivery) */
    const val SCAN_REPORT_DELAY_MS = 0L

    // --- Advertising ---

    /** AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY (highest frequency) */
    const val ADVERTISE_MODE = 2

    /** AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM (balanced range/power) */
    const val ADVERTISE_TX_POWER = 2

    /** Advertise as connectable so peers can open GATT connections */
    const val ADVERTISE_CONNECTABLE = true

    // --- Peer Tracking ---

    /** Duration in ms after which a peer is considered lost if no scan result is received */
    const val PEER_LOST_TIMEOUT_MS = 10_000L

    /** Interval in ms between peer timeout sweeps */
    const val PEER_TIMEOUT_CHECK_INTERVAL_MS = 2_000L

    // --- GATT Connection ---

    /** Timeout in ms for establishing a GATT client connection and discovering services */
    const val GATT_CONNECTION_TIMEOUT_MS = 10_000L

    /** Timeout in ms for a single GATT characteristic write operation */
    const val GATT_WRITE_TIMEOUT_MS = 5_000L

    /** Preferred ATT MTU (negotiated down by the remote if unsupported) */
    const val PREFERRED_MTU = 512

    // --- Local Peer ID ---

    /** SharedPreferences file name for persisting the local peer identifier */
    const val PREFS_NAME = "meshlink_ble"

    /** SharedPreferences key for the hex-encoded local peer ID */
    const val PREFS_KEY_LOCAL_PEER_ID = "local_peer_id"

    /** Length of the randomly generated local peer ID in bytes */
    const val LOCAL_PEER_ID_LENGTH = 16

    // --- BLE Standard UUIDs ---

    /** Client Characteristic Configuration Descriptor UUID (Bluetooth SIG) */
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
}
