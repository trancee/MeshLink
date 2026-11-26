package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.content.Context

/**
 * The GATT calls that Android SDK 37 deprecates but that are the only API below their
 * replacement's level. Constitution Q2's exception: [AndroidGatt] and the GATT callbacks reach each
 * function only under an `SDK_INT` guard below that level; `config/legacy-platform-apis.txt` lists
 * this file and `checkSuppressions` enforces it.
 */
internal object LegacyGatt {
    /**
     * API 26–36: connects directly over LE, initiating on the 1M and 2M PHYs. Replaced on API 37 by
     * `BluetoothDevice.connectGatt(BluetoothGattConnectionSettings, Executor, BluetoothGattCallback)`.
     */
    @Suppress("DEPRECATION")
    fun connect(
        context: Context,
        device: BluetoothDevice,
        callback: BluetoothGattCallback,
    ): BluetoothGatt? =
        device.connectGatt(
            context,
            false,
            callback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK,
        )

    /**
     * API 26–32: writes [value] to [characteristic] without response. Replaced on API 33 by
     * `BluetoothGatt.writeCharacteristic(BluetoothGattCharacteristic, ByteArray, Int)`.
     */
    @Suppress("DEPRECATION")
    fun write(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = value
        return gatt.writeCharacteristic(characteristic)
    }

    /**
     * API 26–32: writes [value] to [descriptor]. Replaced on API 33 by
     * `BluetoothGatt.writeDescriptor(BluetoothGattDescriptor, ByteArray)`.
     */
    @Suppress("DEPRECATION")
    fun write(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        descriptor.value = value
        return gatt.writeDescriptor(descriptor)
    }

    /**
     * API 26–32: notifies [device] of [value] without confirmation. Replaced on API 33 by the
     * `BluetoothGattServer.notifyCharacteristicChanged` overload that takes the value as a `ByteArray`.
     */
    @Suppress("DEPRECATION")
    fun notify(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        characteristic.value = value
        return server.notifyCharacteristicChanged(device, characteristic, false)
    }

    /**
     * API 26–32: the value a read or notification callback left in [characteristic]. Replaced on
     * API 33 by the value parameter of `onCharacteristicRead` and `onCharacteristicChanged`.
     */
    @Suppress("DEPRECATION")
    fun value(characteristic: BluetoothGattCharacteristic): ByteArray = characteristic.value ?: ByteArray(0)
}

/**
 * A GATT client callback that takes reads and notifications with their value from either overload:
 * API 33+ calls the ones that carry it, API 26–32 the ones deprecated on API 33, which take it from
 * [LegacyGatt.value]. Neither calls the other, because API 26–32 lacks the newer ones.
 */
internal abstract class ValueGattCallback : BluetoothGattCallback() {
    /** A read of [characteristic] ended with [status] and [value]. */
    abstract fun read(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    )

    /** [characteristic] notified [value]. */
    abstract fun changed(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    )

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        read(characteristic, value.copyOf(), status)
    }

    @Deprecated("API 26–32 only; API 33+ calls the overload that carries the value.")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        read(characteristic, LegacyGatt.value(characteristic), status)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        changed(characteristic, value.copyOf())
    }

    @Deprecated("API 26–32 only; API 33+ calls the overload that carries the value.")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        changed(characteristic, LegacyGatt.value(characteristic))
    }
}
