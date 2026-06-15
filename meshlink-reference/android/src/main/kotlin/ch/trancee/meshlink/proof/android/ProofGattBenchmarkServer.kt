@file:Suppress("ReturnCount", "MagicNumber", "MaxLineLength")

package ch.trancee.meshlink.proof.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream

@SuppressLint("MissingPermission")
internal class ProofGattBenchmarkServer(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val advertiser: BluetoothLeAdvertiser?,
    private val logger: (String) -> Unit,
    private val appId: String,
) {
    private val subscribedDeviceAddresses: MutableSet<String> = linkedSetOf()

    private var gattServer: BluetoothGattServer? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null
    private var activeTransfer: ActiveTransfer? = null

    private val advertiseCallback =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                logger(
                    "GATT benchmark advertising started service=${ProofGattBenchmarkProtocol.SERVICE_UUID}"
                )
            }

            override fun onStartFailure(errorCode: Int) {
                logger("GATT benchmark advertising failed error=$errorCode")
            }
        }

    private val callback =
        object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    startAdvertising()
                } else {
                    logger("GATT benchmark addService failed status=$status")
                }
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val stateLabel =
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                        else -> newState.toString()
                    }
                logger(
                    "GATT benchmark connection addr=${device.address} status=$status state=$stateLabel"
                )
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    subscribedDeviceAddresses.remove(device.address)
                    if (activeTransfer?.deviceAddress == device.address) {
                        activeTransfer = null
                    }
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                logger("GATT benchmark mtu addr=${device.address} mtu=$mtu")
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                logger(
                    "GATT benchmark write request addr=${device.address} char=${characteristic.uuid} preparedWrite=$preparedWrite responseNeeded=$responseNeeded offset=$offset bytes=${value.size}"
                )
                val status =
                    when {
                        characteristic.uuid != ProofGattBenchmarkProtocol.WRITE_CHARACTERISTIC_UUID -> {
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                        }
                        preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                        offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                        else -> handleWriteFrame(device, value)
                    }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, status, 0, null)
                }
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor,
            ) {
                if (descriptor.uuid != ProofGattBenchmarkProtocol.CCCD_UUID || offset != 0) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        0,
                        null,
                    )
                    return
                }
                val value =
                    if (subscribedDeviceAddresses.contains(device.address)) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                val status =
                    when {
                        descriptor.uuid != ProofGattBenchmarkProtocol.CCCD_UUID -> {
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                        }
                        preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                        offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                            subscribedDeviceAddresses += device.address
                            BluetoothGatt.GATT_SUCCESS
                        }
                        value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                            subscribedDeviceAddresses -= device.address
                            BluetoothGatt.GATT_SUCCESS
                        }
                        else -> BluetoothGatt.GATT_FAILURE
                    }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, status, 0, null)
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                logger(
                    "GATT benchmark notification sent addr=${device.address} status=$status"
                )
            }
        }

    fun start(): Unit {
        if (gattServer != null) {
            return
        }
        val server = bluetoothManager.openGattServer(context, callback)
        checkNotNull(server) { "BluetoothGattServer is unavailable" }
        gattServer = server
        val service = buildService()
        ackCharacteristic = service.getCharacteristic(ProofGattBenchmarkProtocol.ACK_CHARACTERISTIC_UUID)
        logger("GATT benchmark server opening")
        server.addService(service)
    }

    fun stop(): Unit {
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.clearServices()
        gattServer?.close()
        gattServer = null
        ackCharacteristic = null
        activeTransfer = null
        subscribedDeviceAddresses.clear()
        logger("GATT benchmark server stopped")
    }

    private fun buildService(): BluetoothGattService {
        val writeCharacteristic =
            BluetoothGattCharacteristic(
                ProofGattBenchmarkProtocol.WRITE_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        val ackCharacteristic =
            BluetoothGattCharacteristic(
                ProofGattBenchmarkProtocol.ACK_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        val cccd =
            BluetoothGattDescriptor(
                ProofGattBenchmarkProtocol.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        ackCharacteristic.addDescriptor(cccd)
        return BluetoothGattService(
                ProofGattBenchmarkProtocol.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY,
            )
            .apply {
                addCharacteristic(writeCharacteristic)
                addCharacteristic(ackCharacteristic)
            }
    }

    private fun startAdvertising(): Unit {
        val settings =
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()
        val advertiseData =
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(ProofGattBenchmarkProtocol.SERVICE_UUID))
                .addManufacturerData(
                    ProofGattBenchmarkProtocol.MANUFACTURER_ID,
                    ProofGattBenchmarkProtocol.advertisementTag(appId),
                )
                .build()
        advertiser?.startAdvertising(settings, advertiseData, advertiseCallback)
            ?: logger("GATT benchmark advertiser unavailable")
    }

    private fun handleWriteFrame(device: BluetoothDevice, value: ByteArray): Int {
        return when (val frame = ProofGattBenchmarkProtocol.decodeWriteFrame(value)) {
            is ProofGattBenchmarkProtocol.StartFrame -> {
                activeTransfer =
                    ActiveTransfer(
                        deviceAddress = device.address,
                        tokenHex = frame.tokenHex,
                        totalBytes = frame.totalBytes,
                    )
                logger(
                    "GATT benchmark start addr=${device.address} token=${frame.tokenHex} bytes=${frame.totalBytes}"
                )
                BluetoothGatt.GATT_SUCCESS
            }
            is ProofGattBenchmarkProtocol.DataFrame -> {
                val transfer = activeTransfer
                    ?: return BluetoothGatt.GATT_FAILURE.also {
                        logger(
                            "GATT benchmark data before start addr=${device.address} token=${frame.tokenHex}"
                        )
                    }
                if (transfer.deviceAddress != device.address || transfer.tokenHex != frame.tokenHex) {
                    return BluetoothGatt.GATT_FAILURE.also {
                        logger(
                            "GATT benchmark token mismatch addr=${device.address} token=${frame.tokenHex} expected=${transfer.tokenHex}"
                        )
                    }
                }
                return runCatching {
                        transfer.append(frame.chunk)
                        if (transfer.isComplete()) {
                            logger(
                                "MSG from gatt bytes=${transfer.totalBytes} benchmarkToken=${transfer.tokenHex}"
                            )
                            sendAck(device, transfer)
                            activeTransfer = null
                        } else {
                            logger(
                                "GATT benchmark data accepted addr=${device.address} token=${frame.tokenHex} receivedBytes=${transfer.receivedBytes()} totalBytes=${transfer.totalBytes}"
                            )
                        }
                        BluetoothGatt.GATT_SUCCESS
                    }
                    .getOrElse { error ->
                        logger(
                            "GATT benchmark write failed addr=${device.address} token=${frame.tokenHex}: ${error.message.orEmpty()}"
                        )
                        activeTransfer = null
                        BluetoothGatt.GATT_FAILURE
                    }
            }
            null -> {
                logger("GATT benchmark unknown frame addr=${device.address} bytes=${value.size}")
                BluetoothGatt.GATT_FAILURE
            }
        }
    }

    private fun sendAck(device: BluetoothDevice, transfer: ActiveTransfer): Unit {
        val server = gattServer ?: return
        val characteristic = ackCharacteristic ?: return
        logger(
            "GATT benchmark ack addr=${device.address} token=${transfer.tokenHex} totalBytes=${transfer.totalBytes}"
        )
        if (!subscribedDeviceAddresses.contains(device.address)) {
            logger(
                "GATT benchmark receipt skipped because notifications are disabled addr=${device.address} token=${transfer.tokenHex}"
            )
            return
        }
        val value = ProofGattBenchmarkProtocol.encodeAck(transfer.tokenHex, transfer.totalBytes)
        if (Build.VERSION.SDK_INT >= 33) {
            val status = server.notifyCharacteristicChanged(device, characteristic, false, value)
            logger(
                "GATT benchmark receipt notify addr=${device.address} token=${transfer.tokenHex} status=$status"
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            val notified = server.notifyCharacteristicChanged(device, characteristic, false)
            logger(
                "GATT benchmark receipt notify addr=${device.address} token=${transfer.tokenHex} status=$notified"
            )
        }
    }

    private class ActiveTransfer(
        val deviceAddress: String,
        val tokenHex: String,
        val totalBytes: Int,
    ) {
        private val bytes = ByteArrayOutputStream(totalBytes)

        fun append(chunk: ByteArray): Unit {
            require(bytes.size() + chunk.size <= totalBytes) {
                "Received ${bytes.size() + chunk.size} bytes for $totalBytes-byte transfer"
            }
            bytes.write(chunk)
        }

        fun receivedBytes(): Int {
            return bytes.size()
        }

        fun isComplete(): Boolean {
            return bytes.size() == totalBytes
        }
    }
}
