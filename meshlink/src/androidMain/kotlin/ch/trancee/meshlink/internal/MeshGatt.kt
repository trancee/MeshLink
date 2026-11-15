package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.util.UUID

/** The MeshLink GATT service and advertisement UUIDs (spec `docs/spec/discovery.md`, D-12 and D-13). */
internal object MeshGatt {
    /** The MESH service UUID, `0x4D455348` in its 128-bit form; advertised and scanned for. */
    val MESH: UUID = UUID.fromString("4D455348-0000-1000-8000-00805F9B34FB")

    /**
     * The served GATT service. It is off the Bluetooth Base UUID so no stack can shorten it: a vendor
     * stack that sends [MESH] in its 32-bit form, which ATT forbids, hides the service from every central.
     */
    val SERVICE: UUID = UUID.fromString("4D455348-4D4C-4E4B-8000-000000000000")

    /** The capability characteristic: read, no encryption. */
    val CAPABILITY: UUID = UUID.fromString("4D455348-4D4C-4E4B-8001-000000000001")

    /** The central-to-peripheral stream: write without response. */
    val TO_PERIPHERAL: UUID = UUID.fromString("4D455348-4D4C-4E4B-8001-000000000002")

    /** The peripheral-to-central stream: notify. */
    val TO_CENTRAL: UUID = UUID.fromString("4D455348-4D4C-4E4B-8001-000000000003")

    /** The Client Characteristic Configuration descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** The ATT MTU before any exchange (Core Specification, the LE default ATT_MTU). */
    const val DEFAULT_ATT_MTU: Int = 23

    /** The ATT MTU a central asks for: the largest Android accepts. */
    const val REQUESTED_ATT_MTU: Int = 517

    /** [MESH] as the advertisement carries it. */
    val MESH_PARCEL: ParcelUuid = ParcelUuid(MESH)

    /** The service a peripheral serves: [CAPABILITY], [TO_PERIPHERAL] and [TO_CENTRAL] with its CCCD. */
    fun service(): BluetoothGattService =
        BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    CAPABILITY,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                ),
            )
            addCharacteristic(
                BluetoothGattCharacteristic(
                    TO_PERIPHERAL,
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
                ),
            )
            val toCentral = BluetoothGattCharacteristic(TO_CENTRAL, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
            toCentral.addDescriptor(
                BluetoothGattDescriptor(
                    CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
            addCharacteristic(toCentral)
        }

    /** The 16-byte [payload] as a 128-bit UUID in canonical byte order: byte 0 is the first two hex digits. */
    fun uuidOf(payload: ByteArray): UUID {
        val buffer = ByteBuffer.wrap(payload)
        return UUID(buffer.long, buffer.long)
    }

    /** The 16 bytes of [uuid] in canonical byte order; the inverse of [uuidOf]. */
    fun payloadOf(uuid: UUID): ByteArray =
        ByteBuffer
            .allocate(RadioPort.ADVERTISEMENT_LENGTH)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
}

/** A peer as Android knows it: the remote [device], equal by its address. */
internal data class AndroidPeer(
    val device: BluetoothDevice,
) : PeerHandle
