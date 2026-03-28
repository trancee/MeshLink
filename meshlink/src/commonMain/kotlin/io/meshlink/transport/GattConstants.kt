package io.meshlink.transport

/**
 * BLE GATT service and characteristic UUIDs for MeshLink.
 *
 * Uses random 128-bit UUIDs (not Bluetooth SIG Base UUID pattern) to ensure
 * cross-platform BLE advertisement/discovery works correctly. 16-bit UUIDs
 * in the SIG base range are reserved for assigned services and may be silently
 * dropped by Android/iOS BLE stacks.
 *
 * Single service with 4 characteristics:
 * - Control Write/Notify: handshake + gossip (write-with-response)
 * - Data Write/Notify: chunked data transfer (write-without-response)
 */
object GattConstants {
    /** MeshLink GATT service UUID */
    const val SERVICE_UUID = "c64fb997-82f5-4de2-a557-78f80b8dde20"

    /** Control Write characteristic — handshake + gossip inbound (write-with-response) */
    const val CONTROL_WRITE_UUID = "cb5b5efd-82f5-4de2-a557-78f80b8dde20"

    /** Control Notify characteristic — handshake + gossip outbound (notify) */
    const val CONTROL_NOTIFY_UUID = "cd9ad24c-82f5-4de2-a557-78f80b8dde20"

    /** Data Write characteristic — chunk inbound (write-without-response) */
    const val DATA_WRITE_UUID = "5460dbad-82f5-4de2-a557-78f80b8dde20"

    /** Data Notify characteristic — chunk outbound (notify) */
    const val DATA_NOTIFY_UUID = "6c3d7c37-82f5-4de2-a557-78f80b8dde20"
}
