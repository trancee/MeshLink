package ch.trancee.meshlink.platform.android

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
import android.content.Context
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleDiscoveryContract
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal interface GattNotifyServer {
    suspend fun start(): Boolean

    fun close(): Unit
}

@SuppressLint("MissingPermission")
internal class BluetoothGattNotifyServer(
    private val context: Context,
    private val peerBindings: PeerBindings,
    private val onUnknownPeerFrame: (PeerId, String) -> Unit,
    private val onClaimedPeerIdentity: (PeerId, String) -> Unit,
    private val onFrameReceived: (PeerId, ByteArray) -> Boolean,
    private val log: (String) -> Unit,
    private val serviceReadyTimeoutMillis: Long = 2_000,
) : GattNotifyServer {
    private val frameBuffersByAddress: MutableMap<String, L2capFrameBuffer> = linkedMapOf()
    private val connectedDevicesByAddress: MutableMap<String, BluetoothDevice> = linkedMapOf()
    private val lock = Any()
    @Volatile private var server: BluetoothGattServer? = null
    @Volatile private var serviceReady = CompletableDeferred<Boolean>()

    private val callback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                val stateLabel =
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                        else -> newState.toString()
                    }
                log("GATT notify server ${device.address} status=$status state=$stateLabel")
                synchronized(lock) {
                    connectedDevicesByAddress[device.address] = device
                    peerBindings.retainDevice(device.address, device)
                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        frameBuffersByAddress.remove(device.address)
                        connectedDevicesByAddress.remove(device.address)
                    }
                }
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                val serviceUuid = service?.uuid?.toString().orEmpty().lowercase()
                if (serviceUuid != BleDiscoveryContract.GATT_FALLBACK_SERVICE_UUID) {
                    return
                }
                val ready = status == BluetoothGatt.GATT_SUCCESS
                log(
                    "GATT notify server service added status=$status service=$serviceUuid ready=$ready"
                )
                if (!serviceReady.isCompleted) {
                    serviceReady.complete(ready)
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val gattServer = server ?: return
                log(
                    "GATT notify server read request addr=${device.address} characteristic=${characteristic.uuid} offset=$offset"
                )
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }

            @Suppress("DEPRECATION")
            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor,
            ) {
                val gattServer = server ?: return
                log(
                    "GATT notify server descriptor read addr=${device.address} descriptor=${descriptor.uuid} offset=$offset"
                )
                gattServer.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    descriptor.value,
                )
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                val gattServer = server ?: return
                val descriptorUuid = descriptor.uuid.toString().lowercase()
                val isCccd = descriptorUuid == CLIENT_CHARACTERISTIC_CONFIGURATION_UUID.lowercase()
                if (!isCccd) {
                    if (responseNeeded) {
                        gattServer.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                            offset,
                            value,
                        )
                    }
                    return
                }
                log(
                    "GATT notify server descriptor write addr=${device.address} descriptor=$descriptorUuid prepared=$preparedWrite responseNeeded=$responseNeeded offset=$offset valueSize=${value?.size ?: 0}"
                )
                if (responseNeeded) {
                    gattServer.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value,
                    )
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                val gattServer = server ?: return
                val targetUuid = characteristic.uuid.toString().lowercase()
                if (targetUuid != BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID) {
                    if (responseNeeded) {
                        gattServer.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                            offset,
                            value,
                        )
                    }
                    return
                }
                val payload = value ?: ByteArray(0)
                val decodedFrames = mutableListOf<ByteArray>()
                val accepted =
                    synchronized(lock) {
                        val buffer =
                            frameBuffersByAddress.getOrPut(device.address) { L2capFrameBuffer() }
                        val frames = buffer.append(payload)
                        decodedFrames.addAll(frames)
                        frames.all { frame ->
                            when (
                                val disposition =
                                    resolveIncomingGattFrameDisposition(
                                        address = device.address,
                                        frame = frame,
                                        peerBindings = peerBindings,
                                        onUnknownPeerFrame = onUnknownPeerFrame,
                                        onClaimedPeerIdentity = onClaimedPeerIdentity,
                                        log = log,
                                    )
                            ) {
                                is IncomingGattFrameDisposition.ConsumedLinkIdentity -> true
                                is IncomingGattFrameDisposition.Deliver ->
                                    onFrameReceived(disposition.peerId, frame)
                            }
                        }
                    }
                val peerHintIdValue =
                    peerBindings.hintForAddress(device.address)
                        ?: peerBindings.temporaryHintForAddress(device.address)
                        ?: "unbound"
                log(
                    "GATT notify server write addr=${device.address} peer=${peerHintIdValue.takeLast(6)} prepared=$preparedWrite frames=${decodedFrames.size} accepted=$accepted"
                )
                if (responseNeeded) {
                    gattServer.sendResponse(
                        device,
                        requestId,
                        if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                        offset,
                        value,
                    )
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                log("GATT notify server notification sent addr=${device.address} status=$status")
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                log("GATT notify server mtu addr=${device.address} mtu=$mtu")
            }
        }

    override suspend fun start(): Boolean {
        if (server != null) {
            return true
        }
        val bluetoothManager =
            context.getSystemService(BluetoothManager::class.java)
                ?: run {
                    log("GATT notify server unavailable: BluetoothManager is missing")
                    return false
                }
        // openGattServer() has been observed to transiently return null right after a fresh app
        // install/permission grant or a recent Bluetooth adapter state change (the adapter/stack
        // hasn't finished settling yet), rather than being a permanent per-device limitation. A
        // short bounded retry recovers from that transient window instead of leaving this device
        // permanently unable to receive any incoming GATT client connection for the rest of the
        // mesh session.
        val openedServer =
            retryWhileNull(
                maxAttempts = OPEN_SERVER_RETRY_ATTEMPTS,
                delayMillis = OPEN_SERVER_RETRY_DELAY_MILLIS,
                onRetry = { attempt ->
                    log(
                        "GATT notify server openGattServer returned null, retrying " +
                            "($attempt/${OPEN_SERVER_RETRY_ATTEMPTS - 1})"
                    )
                },
            ) {
                bluetoothManager.openGattServer(context, callback)
            }
                ?: run {
                    log("GATT notify server unavailable: openGattServer returned null")
                    return false
                }
        server = openedServer
        serviceReady = CompletableDeferred()
        val service = buildService()
        val addServiceResult = openedServer.addService(service)
        log("GATT notify server addService requested added=$addServiceResult")
        val ready =
            try {
                withTimeout(serviceReadyTimeoutMillis) { serviceReady.await() }
            } catch (_: TimeoutCancellationException) {
                log(
                    "GATT notify server service installation timed out after ${serviceReadyTimeoutMillis}ms"
                )
                false
            }
        if (!ready) {
            close()
        }
        return ready
    }

    override fun close(): Unit {
        val gattServer = server ?: return
        synchronized(lock) {
            frameBuffersByAddress.clear()
            connectedDevicesByAddress.clear()
        }
        runCatching { gattServer.close() }
        server = null
        if (!serviceReady.isCompleted) {
            serviceReady.complete(false)
        }
    }

    private fun buildService(): BluetoothGattService {
        val notifyCharacteristic =
            BluetoothGattCharacteristic(
                    UUID.fromString(BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID),
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                )
                .also { characteristic ->
                    characteristic.addDescriptor(
                        BluetoothGattDescriptor(
                            UUID.fromString(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID),
                            BluetoothGattDescriptor.PERMISSION_READ or
                                BluetoothGattDescriptor.PERMISSION_WRITE,
                        )
                    )
                }
        val writeCharacteristic =
            BluetoothGattCharacteristic(
                UUID.fromString(BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID),
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        return BluetoothGattService(
                UUID.fromString(BleDiscoveryContract.GATT_FALLBACK_SERVICE_UUID),
                BluetoothGattService.SERVICE_TYPE_PRIMARY,
            )
            .apply {
                addCharacteristic(writeCharacteristic)
                addCharacteristic(notifyCharacteristic)
            }
    }

    private companion object {
        private const val CLIENT_CHARACTERISTIC_CONFIGURATION_UUID: String =
            "00002902-0000-1000-8000-00805f9b34fb"
        private const val OPEN_SERVER_RETRY_ATTEMPTS: Int = 3
        private const val OPEN_SERVER_RETRY_DELAY_MILLIS: Long = 300L
    }
}
