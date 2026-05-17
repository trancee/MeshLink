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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class AndroidGattNotifyClient(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val appId: String,
    private val peerHintId: PeerId,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
    private val onFrameReceived: (PeerId, ByteArray) -> Unit,
    private val onDisconnected: (PeerId) -> Unit,
) {
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var ready: Boolean = false
    @Volatile private var servicesDiscoveryStarted: Boolean = false
    @Volatile private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var closedByOwner: Boolean = false
    @Volatile private var currentMtu: Int = DEFAULT_ATT_MTU_BYTES

    private val frameBuffer = AndroidL2capFrameBuffer()
    private val writeMutex = Mutex()
    private val notificationLock = Any()
    private val inboundFrameScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inboundFrames = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private val inboundFrameDispatchJob =
        inboundFrameScope.launch {
            for (payload in inboundFrames) {
                onFrameReceived(peerHintId, payload)
            }
        }

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
                    val shouldNotifyDisconnect = !closedByOwner
                    ready = false
                    completePendingWrite(success = false)
                    closeInternal(markClosedByOwner = true)
                    if (shouldNotifyDisconnect) {
                        onDisconnected(peerHintId)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                log(
                    "GATT notify side link ${peerHintId.value.takeLast(6)} mtu=$mtu status=$status"
                )
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    currentMtu = mtu
                }
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
                    closeInternal(markClosedByOwner = false)
                    return
                }
                val service =
                    gatt.getService(java.util.UUID.fromString(BleDiscoveryContract.GATT_FALLBACK_SERVICE_UUID))
                if (service == null) {
                    log(
                        "GATT notify side link ${peerHintId.value.takeLast(6)} missing service ${BleDiscoveryContract.GATT_FALLBACK_SERVICE_UUID}"
                    )
                    closeInternal(markClosedByOwner = false)
                    return
                }
                val notifyCharacteristic =
                    service.getCharacteristic(
                        java.util.UUID.fromString(BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID)
                    )
                val writeCharacteristic =
                    service.getCharacteristic(
                        java.util.UUID.fromString(BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID)
                    )
                if (notifyCharacteristic == null || writeCharacteristic == null) {
                    log(
                        "GATT notify side link ${peerHintId.value.takeLast(6)} missing notify/write characteristic"
                    )
                    closeInternal(markClosedByOwner = false)
                    return
                }
                this@AndroidGattNotifyClient.notifyCharacteristic = notifyCharacteristic
                this@AndroidGattNotifyClient.writeCharacteristic = writeCharacteristic
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
                    closeInternal(markClosedByOwner = false)
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
                handleNotificationValue(value.copyOf())
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (
                    characteristic.uuid !=
                        java.util.UUID.fromString(BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID)
                ) {
                    return
                }
                handleNotificationValue(value.copyOf())
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (
                    characteristic.uuid !=
                        java.util.UUID.fromString(BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID)
                ) {
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log(
                        "GATT notify side link ${peerHintId.value.takeLast(6)} write failed status=$status"
                    )
                }
                completePendingWrite(success = status == BluetoothGatt.GATT_SUCCESS)
            }
        }

    private fun handleNotificationValue(value: ByteArray): Unit {
        synchronized(notificationLock) {
            log(
                "GATT notify side link ${peerHintId.value.takeLast(6)} received notification bytes=${value.size}"
            )
            val frames = frameBuffer.append(value)
            frames.forEach { payload ->
                log(
                    "GATT notify side link ${peerHintId.value.takeLast(6)} decoded frame bytes=${payload.size}"
                )
                check(inboundFrames.trySend(payload.copyOf()).isSuccess) {
                    "GATT inbound frame queue overflowed for ${peerHintId.value}"
                }
            }
        }
    }

    fun start(): Unit {
        if (gatt != null) {
            return
        }
        ready = false
        servicesDiscoveryStarted = false
        notifyCharacteristic = null
        writeCharacteristic = null
        closedByOwner = false
        currentMtu = DEFAULT_ATT_MTU_BYTES
        gatt = connectGatt(device)
    }

    fun isReady(): Boolean {
        return ready
    }

    suspend fun write(payload: ByteArray): Boolean {
        return writeMutex.withLock {
            if (!ready) {
                log(
                    "GATT notify side link ${peerHintId.value.takeLast(6)} write skipped: client not ready"
                )
                false
            } else {
                val gatt = gatt
                val writeCharacteristic = writeCharacteristic
                if (gatt == null || writeCharacteristic == null) {
                    log(
                        "GATT notify side link ${peerHintId.value.takeLast(6)} write skipped: missing GATT state gatt=${gatt != null} characteristic=${writeCharacteristic != null}"
                    )
                    false
                } else {
                    val encoded = frameBuffer.encode(payload)
                    val maxChunkBytes = maximumWriteChunkBytes()
                    encoded.asList().chunked(maxChunkBytes).all { chunk ->
                        writeEncodedChunk(
                            gatt = gatt,
                            writeCharacteristic = writeCharacteristic,
                            payloadBytes = payload.size,
                            encodedBytes = encoded.size,
                            chunk = chunk.toByteArray(),
                        )
                    }
                }
            }
        }
    }

    fun close(): Unit {
        closeInternal(markClosedByOwner = true)
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
            closeInternal(markClosedByOwner = false)
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(cccd)
    }

    private fun completePendingWrite(success: Boolean): Unit {
        pendingWrite?.complete(success)
        pendingWrite = null
    }

    private fun closeInternal(markClosedByOwner: Boolean): Unit {
        closedByOwner = markClosedByOwner
        ready = false
        notifyCharacteristic = null
        writeCharacteristic = null
        currentMtu = DEFAULT_ATT_MTU_BYTES
        completePendingWrite(success = false)
        runCatching { gatt?.close() }
        gatt = null
    }

    private suspend fun writeEncodedChunk(
        gatt: BluetoothGatt,
        writeCharacteristic: BluetoothGattCharacteristic,
        payloadBytes: Int,
        encodedBytes: Int,
        chunk: ByteArray,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingWrite = deferred
        @Suppress("DEPRECATION")
        writeCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        writeCharacteristic.value = chunk
        @Suppress("DEPRECATION")
        val enqueued = gatt.writeCharacteristic(writeCharacteristic)
        if (!enqueued) {
            log(
                "GATT notify side link ${peerHintId.value.takeLast(6)} write enqueue failed bytes=${payloadBytes} encodedBytes=${encodedBytes} chunkBytes=${chunk.size}"
            )
            completePendingWrite(success = false)
            return false
        }
        return withTimeoutOrNull(WRITE_TIMEOUT_MILLIS) { deferred.await() }
            ?.also { success ->
                if (!success) {
                    log(
                        "GATT notify side link ${peerHintId.value.takeLast(6)} write callback reported failure bytes=${payloadBytes} encodedBytes=${encodedBytes} chunkBytes=${chunk.size}"
                    )
                }
            }
            ?: false.also {
                log(
                    "GATT notify side link ${peerHintId.value.takeLast(6)} write timed out bytes=${payloadBytes} encodedBytes=${encodedBytes} chunkBytes=${chunk.size}"
                )
                completePendingWrite(success = false)
            }
    }

    private fun maximumWriteChunkBytes(): Int {
        return minOf(currentMtu - ATT_WRITE_REQUEST_OVERHEAD_BYTES, MAX_SAFE_WRITE_CHUNK_BYTES)
            .coerceAtLeast(1)
    }

    private fun connectGatt(device: BluetoothDevice): BluetoothGatt {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
    }

    internal companion object {
        internal fun maximumPayloadBytesPerDelivery(): Int {
            return MAX_FRAME_PAYLOAD_BYTES
        }

        private const val CLIENT_CHARACTERISTIC_CONFIGURATION_UUID: String =
            "00002902-0000-1000-8000-00805f9b34fb"
        private const val WRITE_TIMEOUT_MILLIS: Long = 5_000L
        private const val DEFAULT_ATT_MTU_BYTES: Int = 23
        private const val ATT_WRITE_REQUEST_OVERHEAD_BYTES: Int = 3
        private const val MAX_SAFE_WRITE_CHUNK_BYTES: Int = 512
        private const val MAX_FRAME_PAYLOAD_BYTES: Int = 128 * 1024
    }
}
