package ch.trancee.meshlink.platform.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val ATT_WRITE_REQUEST_OVERHEAD_BYTES: Int = 3
private const val MAX_SAFE_WRITE_CHUNK_BYTES: Int = 512

internal fun maximumGattWriteChunkBytes(currentMtu: Int): Int {
    return minOf(currentMtu - ATT_WRITE_REQUEST_OVERHEAD_BYTES, MAX_SAFE_WRITE_CHUNK_BYTES)
        .coerceAtLeast(1)
}

@SuppressLint("MissingPermission", "ObsoleteSdkInt")
internal class GattNotifyClient(
    private val context: Any,
    @Suppress("UNUSED_PARAMETER") private val appId: String,
    private val peerHintId: PeerId,
    private val localHintPeerId: PeerId,
    private val device: Any,
    private val log: (String) -> Unit,
    private val onFrameReceived: (PeerId, ByteArray) -> Boolean,
    private val onDisconnected: (PeerId) -> Unit,
    // Read at connection time so the priority reflects the *current* power tier rather than
    // whatever tier was active when this client was constructed.
    private val connectionPriorityProvider: () -> Int = { BluetoothGatt.CONNECTION_PRIORITY_HIGH },
    private val sessionFactory: GattNotifySessionFactory =
        BluetoothGattNotifySessionFactory(context = context, device = device),
) {
    @Volatile private var session: GattNotifySession? = null
    @Volatile
    private var lifecycleState: GattNotifyLifecycleState =
        startedGattNotifyLifecycle(DEFAULT_ATT_MTU_BYTES)
    private val frameBuffer = L2capFrameBuffer()
    private val writeMutex = Mutex()
    private val notificationLock = Any()
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    @Volatile private var identityAnnounced: Boolean = false

    private val sessionListener =
        object : GattNotifySessionListener {
            override fun onConnectionStateChange(address: String, status: Int, newState: Int) {
                val stateLabel =
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                        else -> newState.toString()
                    }
                log(
                    "GATT notify side link ${peerHintId.value.takeLast(6)} addr=$address status=$status state=$stateLabel"
                )
                val session = session ?: return
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    session.requestConnectionPriority(connectionPriorityProvider())
                    requestFastPhyIfSupported(session)
                    val connectionPlan =
                        connectedGattNotifyLifecycle(
                            state = lifecycleState,
                            requestedMtu = session.requestMtu(517),
                        )
                    lifecycleState = connectionPlan.state
                    if (connectionPlan.shouldDiscoverServices) {
                        session.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val shouldNotifyDisconnect = !lifecycleState.closedByOwner
                    completePendingWrite(success = false)
                    closeInternal(markClosedByOwner = true)
                    if (shouldNotifyDisconnect) {
                        onDisconnected(peerHintId)
                    }
                }
            }

            override fun onMtuChanged(mtu: Int, status: Int) {
                log("GATT notify side link ${peerHintId.value.takeLast(6)} mtu=$mtu status=$status")
                val session = session ?: return
                requestFastPhyIfSupported(session)
                val mtuPlan =
                    mtuChangedGattNotifyLifecycle(
                        state = lifecycleState,
                        mtu = mtu,
                        mtuAccepted = status == ANDROID_GATT_SUCCESS,
                    )
                lifecycleState = mtuPlan.state
                if (mtuPlan.shouldDiscoverServices) {
                    session.discoverServices()
                }
            }

            override fun onPhyUpdate(txPhy: Int, rxPhy: Int, status: Int) {
                log(
                    "GATT notify side link ${peerHintId.value.takeLast(6)} phy tx=$txPhy rx=$rxPhy status=$status"
                )
            }

            override fun onServicesDiscovered(status: Int) {
                val session = session ?: return
                if (status != ANDROID_GATT_SUCCESS) {
                    log(
                        "GATT notify side link ${peerHintId.value.takeLast(6)} service discovery failed status=$status"
                    )
                    closeInternal(markClosedByOwner = false)
                    return
                }
                when (session.resolveFallbackCharacteristics()) {
                    GattNotifyCharacteristicResolution.MISSING_SERVICE -> {
                        log(
                            "GATT notify side link ${peerHintId.value.takeLast(6)} missing service ${FALLBACK_SERVICE_UUID}"
                        )
                        closeInternal(markClosedByOwner = false)
                    }

                    GattNotifyCharacteristicResolution.MISSING_CHARACTERISTICS -> {
                        log(
                            "GATT notify side link ${peerHintId.value.takeLast(6)} missing notify/write characteristic"
                        )
                        closeInternal(markClosedByOwner = false)
                    }

                    GattNotifyCharacteristicResolution.READY -> {
                        when (session.enableNotifications()) {
                            GattNotifyEnableNotificationsResult.REQUESTED -> Unit
                            GattNotifyEnableNotificationsResult.MISSING_CCCD -> {
                                log(
                                    "GATT notify side link ${peerHintId.value.takeLast(6)} missing CCCD for notify characteristic"
                                )
                                closeInternal(markClosedByOwner = false)
                            }

                            GattNotifyEnableNotificationsResult.REQUEST_FAILED -> {
                                log(
                                    "GATT notify side link ${peerHintId.value.takeLast(6)} notify enable request failed"
                                )
                                closeInternal(markClosedByOwner = false)
                            }
                        }
                    }
                }
            }

            override fun onDescriptorWrite(descriptorUuid: String, status: Int) {
                if (descriptorUuid != CLIENT_CHARACTERISTIC_CONFIGURATION_UUID) {
                    return
                }
                val descriptorPlan =
                    descriptorWrittenGattNotifyLifecycle(
                        state = lifecycleState,
                        success = status == ANDROID_GATT_SUCCESS,
                    )
                lifecycleState = descriptorPlan.state
                if (!descriptorPlan.ready) {
                    log(
                        "GATT notify side link ${peerHintId.value.takeLast(6)} notify enable failed status=$status"
                    )
                    closeInternal(markClosedByOwner = false)
                    return
                }
                val address = session?.address.orEmpty()
                log("GATT notify side link ready for ${peerHintId.value.takeLast(6)} addr=$address")
            }

            override fun onCharacteristicChanged(characteristicUuid: String, value: ByteArray) {
                if (characteristicUuid != GATT_NOTIFY_CHARACTERISTIC_UUID) {
                    return
                }
                handleNotificationValue(value)
            }

            override fun onCharacteristicWrite(characteristicUuid: String, status: Int) {
                if (characteristicUuid != GATT_WRITE_CHARACTERISTIC_UUID) {
                    return
                }
                if (status != ANDROID_GATT_SUCCESS) {
                    log(
                        "GATT notify side link ${peerHintId.value.takeLast(6)} write failed status=$status"
                    )
                }
                completePendingWrite(success = status == ANDROID_GATT_SUCCESS)
            }
        }

    private fun handleNotificationValue(value: ByteArray): Unit {
        synchronized(notificationLock) {
            val frames = frameBuffer.append(value)
            frames.forEach { payload ->
                log(
                    "GATT notify side link ${peerHintId.value.takeLast(6)} decoded frame bytes=${payload.size}"
                )
                val accepted = onFrameReceived(peerHintId, payload)
                if (!accepted) {
                    closeInternal(markClosedByOwner = false)
                    return
                }
            }
        }
    }

    fun start(): Unit {
        if (session != null) {
            return
        }
        identityAnnounced = false
        lifecycleState = startedGattNotifyLifecycle(DEFAULT_ATT_MTU_BYTES)
        session = sessionFactory.open(sessionListener)
    }

    fun isReady(): Boolean {
        return lifecycleState.ready
    }

    suspend fun write(payload: ByteArray): Boolean {
        return writeMutex.withLock {
            val session = session
            if (!identityAnnounced) {
                val announced =
                    writeViaGattNotify(
                        payload = WireCodec.encode(WireFrame.LinkIdentity(localHintPeerId)),
                        context =
                            GattNotifyWriteContext(
                                peerLogSuffix = peerHintId.value.takeLast(6),
                                clientReady = lifecycleState.ready,
                                hasGatt = session != null,
                                hasWriteCharacteristic = session?.hasWriteCharacteristic() == true,
                                maxChunkBytes = maximumWriteChunkBytes(),
                            ),
                        dependencies =
                            GattNotifyWriteDependencies(
                                encode = frameBuffer::encode,
                                writeChunk = { payloadBytes, encodedBytes, chunk ->
                                    if (session == null) {
                                        false
                                    } else {
                                        writeEncodedChunk(
                                            session = session,
                                            payloadBytes = payloadBytes,
                                            encodedBytes = encodedBytes,
                                            chunk = chunk,
                                        )
                                    }
                                },
                                log = log,
                            ),
                    )
                if (!announced) {
                    return@withLock false
                }
                identityAnnounced = true
                log(
                    "GATT notify side link ${peerHintId.value.takeLast(6)} announced local LinkIdentity=${localHintPeerId.value.takeLast(6)}"
                )
            }
            writeViaGattNotify(
                payload = payload,
                context =
                    GattNotifyWriteContext(
                        peerLogSuffix = peerHintId.value.takeLast(6),
                        clientReady = lifecycleState.ready,
                        hasGatt = session != null,
                        hasWriteCharacteristic = session?.hasWriteCharacteristic() == true,
                        maxChunkBytes = maximumWriteChunkBytes(),
                    ),
                dependencies =
                    GattNotifyWriteDependencies(
                        encode = frameBuffer::encode,
                        writeChunk = { payloadBytes, encodedBytes, chunk ->
                            if (session == null) {
                                false
                            } else {
                                writeEncodedChunk(
                                    session = session,
                                    payloadBytes = payloadBytes,
                                    encodedBytes = encodedBytes,
                                    chunk = chunk,
                                )
                            }
                        },
                        log = log,
                    ),
            )
        }
    }

    fun close(): Unit {
        closeInternal(markClosedByOwner = true)
    }

    private fun completePendingWrite(success: Boolean): Unit {
        pendingWrite?.complete(success)
        pendingWrite = null
    }

    private fun closeInternal(markClosedByOwner: Boolean): Unit {
        identityAnnounced = false
        lifecycleState =
            closedGattNotifyLifecycle(
                defaultAttMtuBytes = DEFAULT_ATT_MTU_BYTES,
                markClosedByOwner = markClosedByOwner,
            )
        completePendingWrite(success = false)
        runCatching { session?.close() }
        session = null
    }

    private suspend fun writeEncodedChunk(
        session: GattNotifySession,
        payloadBytes: Int,
        encodedBytes: Int,
        chunk: ByteArray,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingWrite = deferred
        val enqueued = session.writeChunk(chunk)
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
        return maximumGattWriteChunkBytes(lifecycleState.currentMtu)
    }

    private fun requestFastPhyIfSupported(session: GattNotifySession): Unit {
        runCatching { session.requestFastPhyIfSupported() }
            .onFailure { error ->
                log(
                    "GATT notify side link ${peerHintId.value.takeLast(6)} preferred PHY request failed: ${error.message.orEmpty()}"
                )
            }
    }

    internal companion object {
        internal fun maximumPayloadBytesPerDelivery(): Int {
            return MAX_FRAME_PAYLOAD_BYTES
        }

        private const val ANDROID_GATT_SUCCESS: Int = 0
        private const val CLIENT_CHARACTERISTIC_CONFIGURATION_UUID: String =
            "00002902-0000-1000-8000-00805f9b34fb"
        private const val FALLBACK_SERVICE_UUID: String =
            BleDiscoveryContract.GATT_FALLBACK_SERVICE_UUID
        private const val GATT_NOTIFY_CHARACTERISTIC_UUID: String =
            BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID
        private const val GATT_WRITE_CHARACTERISTIC_UUID: String =
            BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID
        private const val WRITE_TIMEOUT_MILLIS: Long = 5_000L
        private const val DEFAULT_ATT_MTU_BYTES: Int = 23
        private const val MAX_FRAME_PAYLOAD_BYTES: Int = 128 * 1024
    }
}
