package io.meshlink.transport

/**
 * BLE GATT service and characteristic UUIDs for MeshLink.
 *
 * Single service with 4 characteristics:
 * - Control Write/Notify: handshake + gossip (write-with-response)
 * - Data Write/Notify: chunked data transfer (write-without-response)
 */
object GattConstants {
    /** MeshLink GATT service UUID (short form: 0x7F3A) */
    const val SERVICE_UUID = "00007f3a-0000-1000-8000-00805f9b34fb"

    /** Control Write characteristic — handshake + gossip inbound (write-with-response) */
    const val CONTROL_WRITE_UUID = "00007f3b-0000-1000-8000-00805f9b34fb"

    /** Control Notify characteristic — handshake + gossip outbound (notify) */
    const val CONTROL_NOTIFY_UUID = "00007f3c-0000-1000-8000-00805f9b34fb"

    /** Data Write characteristic — chunk inbound (write-without-response) */
    const val DATA_WRITE_UUID = "00007f3d-0000-1000-8000-00805f9b34fb"

    /** Data Notify characteristic — chunk outbound (notify) */
    const val DATA_NOTIFY_UUID = "00007f3e-0000-1000-8000-00805f9b34fb"
}
