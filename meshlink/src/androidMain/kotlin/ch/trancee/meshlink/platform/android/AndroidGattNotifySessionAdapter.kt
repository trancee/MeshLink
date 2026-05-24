package ch.trancee.meshlink.platform.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import android.os.Build
import ch.trancee.meshlink.transport.BleDiscoveryContract
import java.util.UUID

internal interface AndroidGattNotifySessionFactory {
    fun open(listener: AndroidGattNotifySessionListener): AndroidGattNotifySession
}

internal interface AndroidGattNotifySessionListener {
    fun onConnectionStateChange(address: String, status: Int, newState: Int): Unit

    fun onMtuChanged(mtu: Int, status: Int): Unit

    fun onPhyUpdate(txPhy: Int, rxPhy: Int, status: Int): Unit

    fun onServicesDiscovered(status: Int): Unit

    fun onDescriptorWrite(descriptorUuid: String, status: Int): Unit

    fun onCharacteristicChanged(characteristicUuid: String, value: ByteArray): Unit

    fun onCharacteristicWrite(characteristicUuid: String, status: Int): Unit
}

internal interface AndroidGattNotifySession {
    val address: String

    fun requestHighConnectionPriority(): Unit

    fun requestFastPhyIfSupported(): Unit

    fun requestMtu(mtu: Int): Boolean

    fun discoverServices(): Unit

    fun resolveFallbackCharacteristics(): AndroidGattNotifyCharacteristicResolution

    fun hasWriteCharacteristic(): Boolean

    fun enableNotifications(): AndroidGattNotifyEnableNotificationsResult

    fun writeChunk(chunk: ByteArray): Boolean

    fun close(): Unit
}

internal enum class AndroidGattNotifyCharacteristicResolution {
    READY,
    MISSING_SERVICE,
    MISSING_CHARACTERISTICS,
}

internal enum class AndroidGattNotifyEnableNotificationsResult {
    REQUESTED,
    MISSING_CCCD,
    REQUEST_FAILED,
}

@SuppressLint("MissingPermission", "ObsoleteSdkInt")
internal class AndroidBluetoothGattNotifySessionFactory(
    private val context: Any,
    private val device: Any,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : AndroidGattNotifySessionFactory {
    override fun open(listener: AndroidGattNotifySessionListener): AndroidGattNotifySession {
        val androidContext = context as Context
        val bluetoothDevice = device as BluetoothDevice
        val callback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    gatt: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    listener.onConnectionStateChange(
                        address = gatt.device.address,
                        status = status,
                        newState = newState,
                    )
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    listener.onMtuChanged(mtu = mtu, status = status)
                }

                override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
                    listener.onPhyUpdate(txPhy = txPhy, rxPhy = rxPhy, status = status)
                }

                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    listener.onServicesDiscovered(status = status)
                }

                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    listener.onDescriptorWrite(
                        descriptorUuid = descriptor.uuid.toString(),
                        status = status,
                    )
                }

                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    val value = characteristic.value ?: return
                    listener.onCharacteristicChanged(
                        characteristicUuid = characteristic.uuid.toString(),
                        value = value.copyOf(),
                    )
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    listener.onCharacteristicChanged(
                        characteristicUuid = characteristic.uuid.toString(),
                        value = value.copyOf(),
                    )
                }

                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    listener.onCharacteristicWrite(
                        characteristicUuid = characteristic.uuid.toString(),
                        status = status,
                    )
                }
            }
        val gatt =
            if (sdkInt >= Build.VERSION_CODES.M) {
                bluetoothDevice.connectGatt(
                    androidContext,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                )
            } else {
                bluetoothDevice.connectGatt(androidContext, false, callback)
            }
        return AndroidBluetoothGattNotifySession(gatt = gatt, sdkInt = sdkInt)
    }
}

@SuppressLint("MissingPermission", "ObsoleteSdkInt")
private class AndroidBluetoothGattNotifySession(
    private val gatt: BluetoothGatt,
    private val sdkInt: Int,
) : AndroidGattNotifySession {
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    override val address: String
        get() = gatt.device.address

    override fun requestHighConnectionPriority(): Unit {
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    override fun requestFastPhyIfSupported(): Unit {
        if (sdkInt < Build.VERSION_CODES.O) {
            return
        }
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

    override fun resolveFallbackCharacteristics(): AndroidGattNotifyCharacteristicResolution {
        val service =
            gatt.getService(UUID.fromString(BleDiscoveryContract.GATT_FALLBACK_SERVICE_UUID))
        if (service == null) {
            return AndroidGattNotifyCharacteristicResolution.MISSING_SERVICE
        }
        val notifyCharacteristic =
            service.getCharacteristic(
                UUID.fromString(BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID)
            )
        val writeCharacteristic =
            service.getCharacteristic(
                UUID.fromString(BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID)
            )
        if (notifyCharacteristic == null || writeCharacteristic == null) {
            return AndroidGattNotifyCharacteristicResolution.MISSING_CHARACTERISTICS
        }
        this.notifyCharacteristic = notifyCharacteristic
        this.writeCharacteristic = writeCharacteristic
        return AndroidGattNotifyCharacteristicResolution.READY
    }

    override fun hasWriteCharacteristic(): Boolean {
        return writeCharacteristic != null
    }

    @Suppress("DEPRECATION")
    override fun enableNotifications(): AndroidGattNotifyEnableNotificationsResult {
        val notifyCharacteristic =
            notifyCharacteristic ?: return AndroidGattNotifyEnableNotificationsResult.REQUEST_FAILED
        gatt.setCharacteristicNotification(notifyCharacteristic, true)
        val cccd =
            notifyCharacteristic.getDescriptor(
                UUID.fromString(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID)
            ) ?: return AndroidGattNotifyEnableNotificationsResult.MISSING_CCCD
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (gatt.writeDescriptor(cccd)) {
            AndroidGattNotifyEnableNotificationsResult.REQUESTED
        } else {
            AndroidGattNotifyEnableNotificationsResult.REQUEST_FAILED
        }
    }

    @Suppress("DEPRECATION")
    override fun writeChunk(chunk: ByteArray): Boolean {
        val writeCharacteristic = writeCharacteristic ?: return false
        writeCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        writeCharacteristic.value = chunk
        return gatt.writeCharacteristic(writeCharacteristic)
    }

    override fun close(): Unit {
        gatt.close()
    }

    private companion object {
        private const val CLIENT_CHARACTERISTIC_CONFIGURATION_UUID: String =
            "00002902-0000-1000-8000-00805f9b34fb"
    }
}
