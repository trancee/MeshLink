package ch.trancee.meshlink.platform.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
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
    // Windowed write pipeline: up to WRITE_WINDOW_SIZE chunks may be enqueued with the local BLE
    // stack (via WRITE_TYPE_NO_RESPONSE) before we block waiting for completions, instead of the
    // previous stop-and-wait design that awaited a full round trip per chunk. pendingWrites is a
    // FIFO because Android GATT client operations for a single connection execute and complete
    // strictly in the order they were issued, so completions can be matched to the oldest queued
    // entry.
    private val pendingWritesLock = Any()
    private val pendingWrites = ArrayDeque<CompletableDeferred<Boolean>>()
    private val writeWindow = Semaphore(WRITE_WINDOW_SIZE)
    @Volatile private var identityAnnounced: Boolean = false
    // Guards against retrying the cache-refresh indefinitely: only one refresh-and-rediscover
    // cycle is attempted per connection attempt (i.e. per start()/session lifecycle - this client
    // instance can be reused across reconnects by GattSideLinkCoordinator, so start() re-arms this
    // guard rather than relying on a fresh instance). If the service is still missing afterwards,
    // the peripheral genuinely isn't advertising it (or refresh() is unsupported on this OEM
    // build), and looping further would just waste radio time.
    @Volatile private var serviceCacheRefreshAttempted: Boolean = false

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
                    failAllPendingWrites()
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
                        if (!serviceCacheRefreshAttempted && session.refreshServiceCache()) {
                            serviceCacheRefreshAttempted = true
                            log(
                                "GATT notify side link ${peerHintId.value.takeLast(6)} missing service ${FALLBACK_SERVICE_UUID}, refreshing stale service cache and retrying discovery"
                            )
                            session.discoverServices()
                            return
                        }
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
        // GattSideLinkCoordinator.ensureStarted() reuses this same client instance across
        // reconnects (it only replaces it with a fresh instance once a real GATT disconnect event
        // removes it from clientsByHint), so the refresh-once guard must be re-armed here rather
        // than relying on a new instance being created per connection attempt.
        serviceCacheRefreshAttempted = false
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
                                        enqueueEncodedChunk(
                                            session = session,
                                            payloadBytes = payloadBytes,
                                            encodedBytes = encodedBytes,
                                            chunk = chunk,
                                        )
                                    }
                                },
                                drain = ::drainPendingWrites,
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
                                enqueueEncodedChunk(
                                    session = session,
                                    payloadBytes = payloadBytes,
                                    encodedBytes = encodedBytes,
                                    chunk = chunk,
                                )
                            }
                        },
                        drain = ::drainPendingWrites,
                        log = log,
                    ),
            )
        }
    }

    fun close(): Unit {
        closeInternal(markClosedByOwner = true)
    }

    private fun completePendingWrite(success: Boolean): Unit {
        val deferred = synchronized(pendingWritesLock) { pendingWrites.removeFirstOrNull() }
        if (deferred != null) {
            writeWindow.release()
        }
        deferred?.complete(success)
    }

    private fun failAllPendingWrites(): Unit {
        val pending =
            synchronized(pendingWritesLock) {
                val copy = pendingWrites.toList()
                pendingWrites.clear()
                copy
            }
        repeat(pending.size) { writeWindow.release() }
        pending.forEach { it.complete(false) }
    }

    private fun closeInternal(markClosedByOwner: Boolean): Unit {
        identityAnnounced = false
        lifecycleState =
            closedGattNotifyLifecycle(
                defaultAttMtuBytes = DEFAULT_ATT_MTU_BYTES,
                markClosedByOwner = markClosedByOwner,
            )
        failAllPendingWrites()
        runCatching { session?.close() }
        session = null
    }

    /**
     * Enqueues one chunk into the windowed write pipeline and returns as soon as it has been
     * accepted by the local BLE stack, without waiting for that chunk's own completion. Call
     * [drainPendingWrites] once all chunks for a payload have been enqueued to learn whether they
     * all actually completed successfully.
     *
     * `writeCharacteristic()` can transiently return `false` immediately after a previous write
     * completes -- the framework's internal busy flag can lag a moment behind the completion
     * callback we already awaited, and this is more pronounced with WRITE_TYPE_NO_RESPONSE. That is
     * local backpressure, not a real failure, so it is retried a bounded number of times with a
     * short delay instead of aborting the whole chunked transfer and reconnecting.
     */
    private suspend fun enqueueEncodedChunk(
        session: GattNotifySession,
        payloadBytes: Int,
        encodedBytes: Int,
        chunk: ByteArray,
    ): Boolean {
        writeWindow.acquire()
        val deferred = CompletableDeferred<Boolean>()
        synchronized(pendingWritesLock) { pendingWrites.addLast(deferred) }
        var enqueued = session.writeChunk(chunk)
        var attempt = 1
        while (!enqueued && attempt < ENQUEUE_RETRY_ATTEMPTS) {
            delay(ENQUEUE_RETRY_DELAY_MILLIS)
            enqueued = session.writeChunk(chunk)
            attempt += 1
        }
        if (!enqueued) {
            synchronized(pendingWritesLock) { pendingWrites.remove(deferred) }
            writeWindow.release()
            log(
                "GATT notify side link ${peerHintId.value.takeLast(6)} write enqueue failed after ${attempt} attempt(s) bytes=${payloadBytes} encodedBytes=${encodedBytes} chunkBytes=${chunk.size}"
            )
            deferred.complete(false)
            // A mid-payload chunk enqueue failure means writeViaGattNotify() aborts without ever
            // draining the chunks already enqueued for this same payload (see
            // GattNotifyWriteSupport.writeViaGattNotify). Those earlier chunks were already
            // accepted by the local BLE stack and are in flight to the peer, so the peer's
            // length-prefixed frame reassembly (L2capFrameBuffer) is left mid-frame with no more
            // bytes coming for it. Any later write() on this same connection would then be
            // silently misinterpreted as a continuation of that truncated frame. Closing the
            // session -- as every other unrecoverable GATT failure in this class already does --
            // marks the client not-ready so GattSideLinkCoordinator reconnects with a fresh
            // session instead of reusing one with a corrupted frame stream.
            closeInternal(markClosedByOwner = false)
            return false
        }
        return true
    }

    /**
     * Awaits completion of every chunk enqueued so far (in issue order) and reports whether all of
     * them completed successfully. Must be called after the last [enqueueEncodedChunk] for a
     * payload so callers still get an accurate success/failure result despite chunks being
     * pipelined rather than awaited individually.
     */
    private suspend fun drainPendingWrites(): Boolean {
        var allSucceeded = true
        while (true) {
            val next = synchronized(pendingWritesLock) { pendingWrites.firstOrNull() } ?: break
            val result = withTimeoutOrNull(WRITE_TIMEOUT_MILLIS) { next.await() }
            if (result == null) {
                log("GATT notify side link ${peerHintId.value.takeLast(6)} write drain timed out")
                // Android's GATT client API has no way to cancel an in-flight write, so the
                // native operation this deferred represents can still complete later even
                // though we're giving up on it here. If the session stayed open, that stale
                // onCharacteristicWrite callback would eventually arrive and completePendingWrite
                // would wrongly resolve it against whatever unrelated chunk is at the head of
                // pendingWrites by then (since matching is purely FIFO-positional), silently
                // desynchronizing every subsequent write on this connection. Closing the session
                // now -- the same way every other unrecoverable GATT failure in this class does
                // -- marks the client not-ready so GattSideLinkCoordinator replaces it with a
                // fresh instance instead of reusing this one. closeInternal() already fails any
                // still-pending writes, so there's no need to call failAllPendingWrites() here.
                closeInternal(markClosedByOwner = false)
                return false
            }
            if (!result) {
                allSucceeded = false
            }
        }
        return allSucceeded
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
        // Bounds how many WRITE_TYPE_NO_RESPONSE chunks may be outstanding with the local BLE
        // stack at once. This keeps the transmit queue full (avoiding the previous stop-and-wait
        // per-chunk round trip) while still capping memory/backpressure if the controller falls
        // behind.
        private const val WRITE_WINDOW_SIZE: Int = 4
        // writeCharacteristic() can transiently return false right after a previous write's
        // completion callback fires (the framework's busy flag can lag a moment behind it,
        // especially for WRITE_TYPE_NO_RESPONSE); a few short retries absorb that without
        // aborting the whole chunked transfer.
        private const val ENQUEUE_RETRY_ATTEMPTS: Int = 5
        private const val ENQUEUE_RETRY_DELAY_MILLIS: Long = 20L
        private const val DEFAULT_ATT_MTU_BYTES: Int = 23
        private const val MAX_FRAME_PAYLOAD_BYTES: Int = 128 * 1024
    }
}
