package ch.trancee.meshlink.platform.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import java.util.UUID

internal interface AndroidGattConnectionAdapter {
    val address: String

    fun requestHighConnectionPriority(): Unit

    fun requestFastPhy(): Unit

    fun requestMtu(mtu: Int): Boolean

    fun discoverServices(): Unit

    fun findService(uuid: String): AndroidGattServiceAdapter?

    fun setCharacteristicNotification(
        characteristic: AndroidGattCharacteristicAdapter,
        enabled: Boolean,
    ): Unit

    fun writeDescriptor(descriptor: AndroidGattDescriptorAdapter): Boolean

    fun writeCharacteristic(characteristic: AndroidGattCharacteristicAdapter): Boolean

    fun close(): Unit
}

internal interface AndroidGattServiceAdapter {
    fun findCharacteristic(uuid: String): AndroidGattCharacteristicAdapter?
}

internal interface AndroidGattCharacteristicAdapter {
    val uuid: String

    fun findDescriptor(uuid: String): AndroidGattDescriptorAdapter?

    fun setWriteTypeDefault(): Unit

    fun setValue(value: ByteArray): Unit
}

internal interface AndroidGattDescriptorAdapter {
    val uuid: String

    fun setEnableNotificationValue(): Unit
}

@SuppressLint("MissingPermission", "ObsoleteSdkInt")
internal class AndroidPlatformGattConnectionAdapter(private val gatt: BluetoothGatt) :
    AndroidGattConnectionAdapter {
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

    override fun findService(uuid: String): AndroidGattServiceAdapter? {
        return gatt.getService(UUID.fromString(uuid))?.let(::AndroidPlatformGattServiceAdapter)
    }

    @Suppress("DEPRECATION")
    override fun setCharacteristicNotification(
        characteristic: AndroidGattCharacteristicAdapter,
        enabled: Boolean,
    ): Unit {
        val platformCharacteristic =
            (characteristic as AndroidPlatformGattCharacteristicAdapter).delegate
        gatt.setCharacteristicNotification(platformCharacteristic, enabled)
    }

    @Suppress("DEPRECATION")
    override fun writeDescriptor(descriptor: AndroidGattDescriptorAdapter): Boolean {
        val platformDescriptor = (descriptor as AndroidPlatformGattDescriptorAdapter).delegate
        return gatt.writeDescriptor(platformDescriptor)
    }

    @Suppress("DEPRECATION")
    override fun writeCharacteristic(characteristic: AndroidGattCharacteristicAdapter): Boolean {
        val platformCharacteristic =
            (characteristic as AndroidPlatformGattCharacteristicAdapter).delegate
        return gatt.writeCharacteristic(platformCharacteristic)
    }

    override fun close(): Unit {
        gatt.close()
    }
}

internal class AndroidPlatformGattServiceAdapter(
    val delegate: android.bluetooth.BluetoothGattService
) : AndroidGattServiceAdapter {
    override fun findCharacteristic(uuid: String): AndroidGattCharacteristicAdapter? {
        return delegate
            .getCharacteristic(UUID.fromString(uuid))
            ?.let(::AndroidPlatformGattCharacteristicAdapter)
    }
}

internal class AndroidPlatformGattCharacteristicAdapter(val delegate: BluetoothGattCharacteristic) :
    AndroidGattCharacteristicAdapter {
    override val uuid: String
        get() = delegate.uuid.toString()

    override fun findDescriptor(uuid: String): AndroidGattDescriptorAdapter? {
        return delegate
            .getDescriptor(UUID.fromString(uuid))
            ?.let(::AndroidPlatformGattDescriptorAdapter)
    }

    @Suppress("DEPRECATION")
    override fun setWriteTypeDefault(): Unit {
        delegate.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    }

    @Suppress("DEPRECATION")
    override fun setValue(value: ByteArray): Unit {
        delegate.value = value
    }
}

internal class AndroidPlatformGattDescriptorAdapter(val delegate: BluetoothGattDescriptor) :
    AndroidGattDescriptorAdapter {
    override val uuid: String
        get() = delegate.uuid.toString()

    override fun setEnableNotificationValue(): Unit {
        delegate.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    }
}
