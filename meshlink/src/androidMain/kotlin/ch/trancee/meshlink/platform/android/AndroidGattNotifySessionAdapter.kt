package ch.trancee.meshlink.platform.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Build
import ch.trancee.meshlink.transport.BleDiscoveryContract

internal interface GattNotifySessionFactory {
    fun open(listener: GattNotifySessionListener): GattNotifySession
}

internal interface GattNotifySessionListener {
    fun onConnectionStateChange(address: String, status: Int, newState: Int): Unit

    fun onMtuChanged(mtu: Int, status: Int): Unit

    fun onPhyUpdate(txPhy: Int, rxPhy: Int, status: Int): Unit

    fun onServicesDiscovered(status: Int): Unit

    fun onDescriptorWrite(descriptorUuid: String, status: Int): Unit

    fun onCharacteristicChanged(characteristicUuid: String, value: ByteArray): Unit

    fun onCharacteristicWrite(characteristicUuid: String, status: Int): Unit
}

internal interface GattNotifySession {
    val address: String

    fun requestHighConnectionPriority(): Unit

    fun requestFastPhyIfSupported(): Unit

    fun requestMtu(mtu: Int): Boolean

    fun discoverServices(): Unit

    fun resolveFallbackCharacteristics(): GattNotifyCharacteristicResolution

    fun hasWriteCharacteristic(): Boolean

    fun enableNotifications(): GattNotifyEnableNotificationsResult

    fun writeChunk(chunk: ByteArray): Boolean

    fun close(): Unit
}

internal enum class GattNotifyCharacteristicResolution {
    READY,
    MISSING_SERVICE,
    MISSING_CHARACTERISTICS,
}

internal enum class GattNotifyEnableNotificationsResult {
    REQUESTED,
    MISSING_CCCD,
    REQUEST_FAILED,
}

@SuppressLint("MissingPermission", "ObsoleteSdkInt")
internal class BluetoothGattNotifySessionFactory(
    private val context: Any,
    private val device: Any,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : GattNotifySessionFactory {
    override fun open(listener: GattNotifySessionListener): GattNotifySession {
        val androidContext = context as Context
        val bluetoothDevice = device as BluetoothDevice
        val relay = GattNotifyCallbackRelay(listener)
        val callback = createAndroidGattNotifyCallback(sdkInt = sdkInt, relay = relay)
        val gatt =
            connectAndroidGattSession(
                sdkInt = sdkInt,
                leTransportFactory = {
                    bluetoothDevice.connectGatt(
                        androidContext,
                        false,
                        callback,
                        BluetoothDevice.TRANSPORT_LE,
                    )
                },
                legacyFactory = { bluetoothDevice.connectGatt(androidContext, false, callback) },
            )
        return BluetoothGattNotifySession(
            connection = PlatformGattConnectionAdapter(gatt = gatt, sdkInt = sdkInt),
            sdkInt = sdkInt,
        )
    }
}

internal class BluetoothGattNotifySession(
    private val connection: GattConnectionAdapter,
    private val sdkInt: Int,
) : GattNotifySession {
    private var notifyCharacteristic: GattCharacteristicAdapter? = null
    private var writeCharacteristic: GattCharacteristicAdapter? = null

    override val address: String
        get() = connection.address

    override fun requestHighConnectionPriority(): Unit {
        connection.requestHighConnectionPriority()
    }

    override fun requestFastPhyIfSupported(): Unit {
        if (sdkInt < Build.VERSION_CODES.O) {
            return
        }
        connection.requestFastPhy()
    }

    override fun requestMtu(mtu: Int): Boolean {
        return connection.requestMtu(mtu)
    }

    override fun discoverServices(): Unit {
        connection.discoverServices()
    }

    override fun resolveFallbackCharacteristics(): GattNotifyCharacteristicResolution {
        val service = connection.findService(BleDiscoveryContract.GATT_FALLBACK_SERVICE_UUID)
        if (service == null) {
            return GattNotifyCharacteristicResolution.MISSING_SERVICE
        }
        val notifyCharacteristic =
            service.findCharacteristic(BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID)
        val writeCharacteristic =
            service.findCharacteristic(BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID)
        if (notifyCharacteristic == null || writeCharacteristic == null) {
            return GattNotifyCharacteristicResolution.MISSING_CHARACTERISTICS
        }
        this.notifyCharacteristic = notifyCharacteristic
        this.writeCharacteristic = writeCharacteristic
        return GattNotifyCharacteristicResolution.READY
    }

    override fun hasWriteCharacteristic(): Boolean {
        return writeCharacteristic != null
    }

    override fun enableNotifications(): GattNotifyEnableNotificationsResult {
        val notifyCharacteristic =
            notifyCharacteristic ?: return GattNotifyEnableNotificationsResult.REQUEST_FAILED
        connection.setCharacteristicNotification(notifyCharacteristic, true)
        val cccd =
            notifyCharacteristic.findDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID)
                ?: return GattNotifyEnableNotificationsResult.MISSING_CCCD
        return if (connection.writeDescriptor(cccd, enableNotificationValue())) {
            GattNotifyEnableNotificationsResult.REQUESTED
        } else {
            GattNotifyEnableNotificationsResult.REQUEST_FAILED
        }
    }

    override fun writeChunk(chunk: ByteArray): Boolean {
        val writeCharacteristic = writeCharacteristic ?: return false
        return connection.writeCharacteristic(
            characteristic = writeCharacteristic,
            value = chunk,
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
    }

    override fun close(): Unit {
        connection.close()
    }

    private companion object {
        private const val CLIENT_CHARACTERISTIC_CONFIGURATION_UUID: String =
            "00002902-0000-1000-8000-00805f9b34fb"

        private fun enableNotificationValue(): ByteArray {
            return byteArrayOf(0x01, 0x00)
        }
    }
}
