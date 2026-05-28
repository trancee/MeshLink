package ch.trancee.meshlink.platform.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.Build
import java.util.UUID

internal interface GattConnectionAdapter {
    val address: String

    fun requestHighConnectionPriority(): Unit

    fun requestFastPhy(): Unit

    fun requestMtu(mtu: Int): Boolean

    fun discoverServices(): Unit

    fun findService(uuid: String): GattServiceAdapter?

    fun setCharacteristicNotification(
        characteristic: GattCharacteristicAdapter,
        enabled: Boolean,
    ): Unit

    fun writeDescriptor(descriptor: GattDescriptorAdapter, value: ByteArray): Boolean

    fun writeCharacteristic(
        characteristic: GattCharacteristicAdapter,
        value: ByteArray,
        writeType: Int,
    ): Boolean

    fun close(): Unit
}

internal interface GattServiceAdapter {
    fun findCharacteristic(uuid: String): GattCharacteristicAdapter?
}

internal interface GattCharacteristicAdapter {
    val uuid: String

    fun findDescriptor(uuid: String): GattDescriptorAdapter?
}

internal interface GattDescriptorAdapter {
    val uuid: String
}

@SuppressLint("MissingPermission", "ObsoleteSdkInt")
internal class PlatformGattConnectionAdapter(
    private val gatt: BluetoothGatt,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : GattConnectionAdapter {
    override val address: String
        get() = gatt.device.address

    override fun requestHighConnectionPriority(): Unit {
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    override fun requestFastPhy(): Unit {
        gatt.setPreferredPhy(
            BluetoothDevice.PHY_LE_2M_MASK,
            BluetoothDevice.PHY_LE_2M_MASK,
            BluetoothDevice.PHY_OPTION_NO_PREFERRED,
        )
    }

    override fun requestMtu(mtu: Int): Boolean {
        return gatt.requestMtu(mtu)
    }

    override fun discoverServices(): Unit {
        gatt.discoverServices()
    }

    override fun findService(uuid: String): GattServiceAdapter? {
        return gatt.getService(UUID.fromString(uuid))?.let(::PlatformGattServiceAdapter)
    }

    override fun setCharacteristicNotification(
        characteristic: GattCharacteristicAdapter,
        enabled: Boolean,
    ): Unit {
        val platformCharacteristic = (characteristic as PlatformGattCharacteristicAdapter).delegate
        gatt.setCharacteristicNotification(platformCharacteristic, enabled)
    }

    override fun writeDescriptor(descriptor: GattDescriptorAdapter, value: ByteArray): Boolean {
        val platformDescriptor = (descriptor as PlatformGattDescriptorAdapter).delegate
        return writeAndroidGattDescriptor(
            sdkInt = sdkInt,
            api33Write = { gatt.writeDescriptor(platformDescriptor, value.copyOf()) },
            legacyWrite = { writeAndroidGattDescriptorLegacy(gatt, platformDescriptor, value) },
        )
    }

    override fun writeCharacteristic(
        characteristic: GattCharacteristicAdapter,
        value: ByteArray,
        writeType: Int,
    ): Boolean {
        val platformCharacteristic = (characteristic as PlatformGattCharacteristicAdapter).delegate
        return writeAndroidGattCharacteristic(
            sdkInt = sdkInt,
            api33Write = {
                gatt.writeCharacteristic(platformCharacteristic, value.copyOf(), writeType)
            },
            legacyWrite = {
                writeAndroidGattCharacteristicLegacy(gatt, platformCharacteristic, value, writeType)
            },
        )
    }

    override fun close(): Unit {
        gatt.close()
    }
}

internal class PlatformGattServiceAdapter(val delegate: BluetoothGattService) : GattServiceAdapter {
    override fun findCharacteristic(uuid: String): GattCharacteristicAdapter? {
        return delegate
            .getCharacteristic(UUID.fromString(uuid))
            ?.let(::PlatformGattCharacteristicAdapter)
    }
}

internal class PlatformGattCharacteristicAdapter(val delegate: BluetoothGattCharacteristic) :
    GattCharacteristicAdapter {
    override val uuid: String
        get() = delegate.uuid.toString()

    override fun findDescriptor(uuid: String): GattDescriptorAdapter? {
        return delegate.getDescriptor(UUID.fromString(uuid))?.let(::PlatformGattDescriptorAdapter)
    }
}

internal class PlatformGattDescriptorAdapter(val delegate: BluetoothGattDescriptor) :
    GattDescriptorAdapter {
    override val uuid: String
        get() = delegate.uuid.toString()
}
