package ch.trancee.meshlink.platform.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val ATT_WRITE_REQUEST_OVERHEAD_BYTES: Int = 3

// 495 bytes is the DLE two-full-Link-Layer-packet sweet spot (each LE Data Length Extension
// packet carries up to ~251 usable bytes once L2CAP/ATT overhead is subtracted). Capping the
// write chunk here instead of at the naive MTU-derived 512-byte ceiling keeps every full-size
// chunk aligned to exactly two LL packets; a 512-byte chunk would instead spill a small remainder
// into a mostly-empty third LL packet on every write, wasting airtime. See the optimize-ble-
// throughput skill's 244/495-byte chunk-sizing guidance.
private const val MAX_SAFE_WRITE_CHUNK_BYTES: Int = 495

internal fun maximumGattWriteChunkBytes(currentMtu: Int): Int {
    return minOf(currentMtu - ATT_WRITE_REQUEST_OVERHEAD_BYTES, MAX_SAFE_WRITE_CHUNK_BYTES)
        .coerceAtLeast(1)
}

/**
 * One chunk in flight through the windowed write pipeline. Retains [chunk] (not just the [deferred]
 * completion) so a GATT_CONNECTION_CONGESTED completion can reissue the exact same bytes in place
 * via [GattNotifyClient.retryCongestedWriteIfPossible] instead of only being able to fail the whole
 * payload.
 */
private class PendingGattWrite(val chunk: ByteArray) {
    val deferred: CompletableDeferred<Boolean> = CompletableDeferred()
    var congestionRetries: Int = 0
}

@SuppressLint("MissingPermission", "ObsoleteSdkInt")
@Suppress("LongParameterList")
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
    // Overridable so tests can use a zero delay + an unconfined/immediate scope to observe the
    // status=133 connect-retry deterministically, the same pattern already used for
    // GattSideLinkCoordinator's peerLostDebounceMillis/scope.
    private val connectRetryDelayMillis: Long = CONNECT_RETRY_DELAY_MILLIS,
    private val connectRetryScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    @Volatile private var session: GattNotifySession? = null
    @Volatile
    private var lifecycleState: GattNotifyLifecycleState =
        startedGattNotifyLifecycle(DEFAULT_ATT_MTU_BYTES)
    // Counts consecutive status=133 (GATT_ERROR) connect failures for the *current* peer so the
    // bounded retry in onConnectionStateChange() doesn't retry forever; reset on every explicit
    // start() and on any successful STATE_CONNECTED callback.
    @Volatile private var connectRetryAttempts: Int = 0
    // Guards against GattSideLinkCoordinator.ensureStarted() (re-triggered on every incoming
    // discovery broadcast for a not-yet-ready peer) racing a fresh connectGatt() in behind the
    // status=133 retry's own delayed start() call below -- without this, both could see
    // session == null during the retry delay window and each issue their own connectGatt() to the
    // same remote device, reintroducing the duplicate-connection-attempt failure mode this retry
    // is meant to recover from.
    @Volatile private var reconnectPending: Boolean = false
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
    private val pendingWrites = ArrayDeque<PendingGattWrite>()
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
                    connectRetryAttempts = 0
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
                    if (
                        status == ANDROID_GATT_ERROR &&
                            !lifecycleState.closedByOwner &&
                            connectRetryAttempts < MAX_CONNECT_RETRY_ATTEMPTS
                    ) {
                        connectRetryAttempts += 1
                        log(
                            "GATT notify side link ${peerHintId.value.takeLast(6)} status=133 " +
                                "(GATT_ERROR), retrying connect ($connectRetryAttempts/" +
                                "$MAX_CONNECT_RETRY_ATTEMPTS) in ${connectRetryDelayMillis}ms"
                        )
                        failAllPendingWrites()
                        runCatching { this@GattNotifyClient.session?.close() }
                        this@GattNotifyClient.session = null
                        reconnectPending = true
                        connectRetryScope.launch {
                            delay(connectRetryDelayMillis)
                            // Bypasses the reconnectPending guard in start() entirely rather than
                            // clearing the flag first and then calling start() as two separate
                            // steps -- this coroutine is the sole intended owner of this specific
                            // pending reconnect attempt (status=133 events for one connection are
                            // delivered serially by Android, so only one retry can ever be
                            // in-flight at a time), and closing the gap this way means there is no
                            // moment where reconnectPending is false but the actual reconnect
                            // hasn't happened yet for a concurrent ensureStarted() call to race
                            // into.
                            startInternal()
                        }
                        return
                    }
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
                // GATT_CONNECTION_CONGESTED means the local BLE stack's transmit queue is
                // temporarily backed up -- the controller couldn't send this chunk yet, not that
                // the link or the chunk itself is broken. Retrying the same chunk in place absorbs
                // that transient backpressure the same way enqueueEncodedChunk()'s busy-retry loop
                // already does for the synchronous writeCharacteristic() == false case, instead of
                // tearing down and reconnecting the whole session for what is normally a
                // self-clearing condition.
                if (
                    status == ANDROID_GATT_CONNECTION_CONGESTED && retryCongestedWriteIfPossible()
                ) {
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
        if (session != null || reconnectPending) {
            return
        }
        startInternal()
    }

    // Unconditionally (re)opens a session, bypassing the reconnectPending guard in start(). Only
    // called from two places: start() itself (after its guard already passed) and the status=133
    // retry's own resumed coroutine above, which is the sole intended owner of a pending retry and
    // must not be blocked by the very flag it set to keep *other* concurrent start() callers out.
    private fun startInternal(): Unit {
        reconnectPending = false
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
                // A fresh tracker per writeViaGattNotify() call: drainPendingWrites() awaits
                // exactly these chunks' own deferreds rather than inferring "done" from shared
                // queue emptiness (see drainPendingWrites() for why that inference is unsound).
                val announceChunks = mutableListOf<PendingGattWrite>()
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
                                            pendingChunks = announceChunks,
                                        )
                                    }
                                },
                                drain = { drainPendingWrites(announceChunks) },
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
            val payloadChunks = mutableListOf<PendingGattWrite>()
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
                                    pendingChunks = payloadChunks,
                                )
                            }
                        },
                        drain = { drainPendingWrites(payloadChunks) },
                        log = log,
                    ),
            )
        }
    }

    fun close(): Unit {
        connectRetryScope.cancel()
        closeInternal(markClosedByOwner = true)
    }

    private fun completePendingWrite(success: Boolean): Unit {
        val pending = synchronized(pendingWritesLock) { pendingWrites.removeFirstOrNull() }
        if (pending != null) {
            writeWindow.release()
        }
        pending?.deferred?.complete(success)
    }

    /**
     * Reissues the oldest still-pending write in place after a GATT_CONNECTION_CONGESTED
     * completion, up to [MAX_CONGESTION_RETRY_ATTEMPTS] times, without releasing its write-window
     * permit or removing it from [pendingWrites] -- from the pipeline's point of view this chunk is
     * still in flight, just re-submitted. Returns false (leaving the caller to fall back to
     * treating this as a genuine failure) if there is no pending write to retry, its retry budget
     * is exhausted, or the session is gone.
     */
    private fun retryCongestedWriteIfPossible(): Boolean {
        val activeSession = session ?: return false
        val pending =
            synchronized(pendingWritesLock) { pendingWrites.firstOrNull() } ?: return false
        if (pending.congestionRetries >= MAX_CONGESTION_RETRY_ATTEMPTS) {
            return false
        }
        pending.congestionRetries += 1
        val reissued = activeSession.writeChunk(pending.chunk)
        if (!reissued) {
            return false
        }
        log(
            "GATT notify side link ${peerHintId.value.takeLast(6)} write congested, retrying " +
                "(${pending.congestionRetries}/$MAX_CONGESTION_RETRY_ATTEMPTS)"
        )
        return true
    }

    private fun failAllPendingWrites(): Unit {
        val pending =
            synchronized(pendingWritesLock) {
                val copy = pendingWrites.toList()
                pendingWrites.clear()
                copy
            }
        repeat(pending.size) { writeWindow.release() }
        pending.forEach { it.deferred.complete(false) }
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
        pendingChunks: MutableList<PendingGattWrite>,
    ): Boolean {
        writeWindow.acquire()
        val pending = PendingGattWrite(chunk)
        synchronized(pendingWritesLock) { pendingWrites.addLast(pending) }
        // Recorded here (in issue order) so drainPendingWrites() can await this exact chunk's own
        // completion later, independent of whether completePendingWrite() has already removed it
        // from the shared pendingWrites queue by the time drain runs -- see drainPendingWrites().
        pendingChunks.add(pending)
        var enqueued = session.writeChunk(chunk)
        var attempt = 1
        while (!enqueued && attempt < ENQUEUE_RETRY_ATTEMPTS) {
            delay(ENQUEUE_RETRY_DELAY_MILLIS)
            enqueued = session.writeChunk(chunk)
            attempt += 1
        }
        if (!enqueued) {
            synchronized(pendingWritesLock) { pendingWrites.remove(pending) }
            writeWindow.release()
            log(
                "GATT notify side link ${peerHintId.value.takeLast(6)} write enqueue failed after ${attempt} attempt(s) bytes=${payloadBytes} encodedBytes=${encodedBytes} chunkBytes=${chunk.size}"
            )
            pending.deferred.complete(false)
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
     * Awaits completion of every chunk enqueued so far for this payload (in issue order) and
     * reports whether all of them completed successfully. Must be called after the last
     * [enqueueEncodedChunk] for a payload so callers still get an accurate success/failure result
     * despite chunks being pipelined rather than awaited individually.
     *
     * Takes the exact list of [PendingGattWrite] entries [enqueueEncodedChunk] created for this
     * payload and awaits each one's own [PendingGattWrite.deferred] directly, rather than inferring
     * completion from whether [pendingWrites] (the shared FIFO queue) still contains an entry to
     * wait on. The latter is unsound: completePendingWrite() removes an entry from [pendingWrites]
     * as soon as *any* completion for it arrives -- success or failure -- so if every chunk's
     * completion happens to arrive (and be removed) before this function gets a chance to run,
     * checking queue membership alone would see an empty queue and report success unconditionally,
     * silently treating a failed transfer as successful. Awaiting each recorded chunk's own
     * deferred sidesteps that: a deferred already completed by the time we get to it resolves
     * immediately with its real result, whether or not it's still in [pendingWrites].
     */
    private suspend fun drainPendingWrites(pendingChunks: List<PendingGattWrite>): Boolean {
        for (next in pendingChunks) {
            val result = withTimeoutOrNull(WRITE_TIMEOUT_MILLIS) { next.deferred.await() }
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
                log("GATT notify side link ${peerHintId.value.takeLast(6)} write chunk failed")
                // A genuine per-chunk write failure (as opposed to enqueue failure or drain
                // timeout, both already handled above/elsewhere) can still arrive after later
                // chunks of the same payload have already been dispatched to the controller,
                // since writes are pipelined rather than awaited one at a time. Continuing to
                // drain the remaining chunks -- or reusing this session for a later write() --
                // would leave the peer's length-prefixed frame reassembly mid-frame with no more
                // bytes coming for it, the same corruption risk already documented for the
                // timeout and enqueue-failure cases. Close the session and stop draining rather
                // than treating this as just one failed chunk among an otherwise-healthy payload.
                closeInternal(markClosedByOwner = false)
                return false
            }
        }
        return true
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
        // Bounds how many times a single chunk may be reissued in place after a
        // GATT_CONNECTION_CONGESTED write completion before it's treated as a genuine failure (see
        // retryCongestedWriteIfPossible). Congestion is expected to clear within a handful of the
        // controller's own TX-complete cycles; retrying indefinitely would risk masking a link that
        // is actually gone.
        private const val MAX_CONGESTION_RETRY_ATTEMPTS: Int = 5
        private const val ANDROID_GATT_CONNECTION_CONGESTED: Int = 143
        // Android's generic BluetoothGatt.GATT_ERROR (133) status, most often seen as an
        // immediate connection failure right after connectGatt() on certain older/OEM Bluetooth
        // stacks (observed on SDK-28-era Samsung/Xiaomi hardware in this project's device fleet).
        // It is a well-documented transient condition in the Android developer community rather
        // than a permanent per-pairing failure, so a short bounded retry is attempted before
        // giving up and surfacing a real disconnect/PeerLost.
        private const val ANDROID_GATT_ERROR: Int = 133
        private const val MAX_CONNECT_RETRY_ATTEMPTS: Int = 2
        private const val CONNECT_RETRY_DELAY_MILLIS: Long = 400L
        private const val DEFAULT_ATT_MTU_BYTES: Int = 23
        private const val MAX_FRAME_PAYLOAD_BYTES: Int = 128 * 1024
    }
}
