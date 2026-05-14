package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleDiscoveryContract

internal class AndroidGattNotifyClient(
    private val context: Context,
    private val appId: String,
    private val peerHintId: PeerId,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
    private val onFrameReceived: (PeerId, ByteArray) -> Unit,
) {
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var ready: Boolean = false
    @Volatile private var servicesDiscoveryStarted: Boolean = false

    private val frameBuffer = AndroidL2capFrameBuffer()

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val stateLabel =
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                        else -> newState.toString()
                    }
                log(
                    "GATT notify side link ${peerHintId.value.takeLast(6)} addr=${device.address} status=$status state=$stateLabel"
                )
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    servicesDiscoveryStarted = false
                    val requestedMtu = gatt.requestMtu(517)
                    if (!requestedMtu) {
                        servicesDiscoveryStarted = true
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    ready = false
                    close()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                log(
                    "GATT notify side link ${peerHintId.value.takeLast(6)} mtu=$mtu status=$status"
                )
                if (!servicesDiscoveryStarted) {
                    servicesDiscoveryStarted = true
                    gatt.discoverServices()
                }
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log(
                        "GATT notify side link ${peerHintId.value.takeLast(6)} service discovery failed status=$status"
                    )
                    close()
                    return
                }
                val service =
                    gatt.getService(java.util.UUID.fromString(BleDiscoveryContract.GATT_FALLBACK_SERVICE_UUID))
                if (service == null) {
                    log(
                        "GATT notify side link ${peerHintId.value.takeLast(6)} missing service ${BleDiscoveryContract.GATT_FALLBACK_SERVICE_UUID}"
                    )
                    close()
                    return
                }
                val notifyCharacteristic =
                    service.getCharacteristic(
                        java.util.UUID.fromString(BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID)
                    )
                if (notifyCharacteristic == null) {
                    log(
                        "GATT notify side link ${peerHintId.value.takeLast(6)} missing notify characteristic"
                    )
                    close()
                    return
                }
                enableNotifications(gatt, notifyCharacteristic)
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (
                    descriptor.uuid !=
                        java.util.UUID.fromString(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID)
                ) {
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log(
                        "GATT notify side link ${peerHintId.value.takeLast(6)} notify enable failed status=$status"
                    )
                    close()
                    return
                }
                ready = true
                log(
                    "GATT notify side link ready for ${peerHintId.value.takeLast(6)} addr=${device.address}"
                )
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (
                    characteristic.uuid !=
                        java.util.UUID.fromString(BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID)
                ) {
                    return
                }
                val value = characteristic.value ?: return
                log(
                    "GATT notify side link ${peerHintId.value.takeLast(6)} received notification bytes=${value.size}"
                )
                val frames = frameBuffer.append(value)
                frames.forEach { payload ->
                    log(
                        "GATT notify side link ${peerHintId.value.takeLast(6)} decoded frame bytes=${payload.size}"
                    )
                    onFrameReceived(peerHintId, payload)
                }
            }
        }

    fun start(): Unit {
        if (gatt != null) {
            return
        }
        ready = false
        servicesDiscoveryStarted = false
        gatt = connectGatt(device)
    }

    fun isReady(): Boolean {
        return ready
    }

    fun close(): Unit {
        ready = false
        runCatching { gatt?.close() }
        gatt = null
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        notifyCharacteristic: BluetoothGattCharacteristic,
    ) {
        gatt.setCharacteristicNotification(notifyCharacteristic, true)
        val cccd =
            notifyCharacteristic.getDescriptor(
                java.util.UUID.fromString(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID)
            )
        if (cccd == null) {
            log(
                "GATT notify side link ${peerHintId.value.takeLast(6)} missing CCCD for notify characteristic"
            )
            close()
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(cccd)
    }

    private fun connectGatt(device: BluetoothDevice): BluetoothGatt {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
    }

    private fun serviceIdBytes(): ByteArray {
        val meshHash = BleDiscoveryContract.computeMeshHash(appId)
        return byteArrayOf(
            (meshHash.toInt() and 0xFF).toByte(),
            ((meshHash.toInt() shr 8) and 0xFF).toByte(),
        )
    }

    private companion object {
        private const val CLIENT_CHARACTERISTIC_CONFIGURATION_UUID: String =
            "00002902-0000-1000-8000-00805f9b34fb"
    }
}
