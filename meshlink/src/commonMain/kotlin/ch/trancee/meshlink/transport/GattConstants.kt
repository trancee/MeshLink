package ch.trancee.meshlink.transport

/**
 * GATT UUIDs for the MeshLink BLE service (spec §3).
 *
 * All UUIDs share the `4d455348` ("MESH" in ASCII hex) prefix. The Advertisement UUID uses the
 * Bluetooth Base UUID suffix (`00805f9b34fb`); the Service and Characteristic UUIDs use
 * `000000000000`.
 */
internal object GattConstants {
    /** 32-bit Bluetooth Base UUID for BLE advertisement service data. */
    const val ADVERTISEMENT_UUID = "4d455348-0000-1000-8000-00805f9b34fb"

    /** Primary GATT service UUID. */
    const val SERVICE_UUID = "4d455348-0001-1000-8000-000000000000"

    /** Control channel — write characteristic (write-without-response). */
    const val CONTROL_WRITE_UUID = "4d455348-0002-1000-8000-000000000000"

    /** Control channel — notify characteristic. */
    const val CONTROL_NOTIFY_UUID = "4d455348-0003-1000-8000-000000000000"

    /** Data channel — write characteristic (write-without-response). */
    const val DATA_WRITE_UUID = "4d455348-0004-1000-8000-000000000000"

    /** Data channel — notify characteristic. */
    const val DATA_NOTIFY_UUID = "4d455348-0005-1000-8000-000000000000"
}
