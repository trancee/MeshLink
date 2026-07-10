package ch.trancee.meshlink.platform.android

import android.content.Context
import android.os.Build
import ch.trancee.meshlink.power.PowerPolicy

internal fun advertisedDiscoveryL2capPsm(
    serverSocketPsm: Int?,
    localL2capClientSocketsSupported: Boolean,
): UByte {
    return if (!localL2capClientSocketsSupported) 0u else (serverSocketPsm ?: 0).toUByte()
}

// Registers a BroadcastReceiver for BluetoothAdapter.ACTION_STATE_CHANGED so discovery proactively
// resumes (after a short debounce -- see BluetoothStateChangeDebouncer) when the user manually
// toggles Bluetooth back on, rather than waiting for some unrelated caller to invoke start()/
// refresh() again. Registered in startTransport() and unregistered in stopTransports() so there is
// never more than one receiver instance registered per adapter lifecycle.
internal fun BleTransportAdapter.registerBluetoothStateChangeReceiver(): Unit {
    if (bluetoothStateChangeReceiver != null) return
    val receiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(
                receiverContext: android.content.Context?,
                intent: android.content.Intent?,
            ) {
                val state =
                    intent?.getIntExtra(
                        android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                        android.bluetooth.BluetoothAdapter.ERROR,
                    ) ?: android.bluetooth.BluetoothAdapter.ERROR
                bluetoothStateChangeDebouncer.onStateChanged(state) {
                    if (started && !transportStopping) {
                        refreshDiscoveryState()
                    }
                }
            }
        }
    val filter =
        android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    }
    bluetoothStateChangeReceiver = receiver
}

internal fun BleTransportAdapter.unregisterBluetoothStateChangeReceiver(): Unit {
    val receiver = bluetoothStateChangeReceiver ?: return
    bluetoothStateChangeReceiver = null
    runCatching { context.unregisterReceiver(receiver) }
}

internal suspend fun BleTransportAdapter.startTransport(): Unit {
    transportStopping = false
    ensurePermissionsGranted()
    val bluetoothManager =
        try {
            context.getSystemService(android.bluetooth.BluetoothManager::class.java)
                ?: error("BluetoothManager is unavailable")
        } catch (exception: SecurityException) {
            throw androidPermissionDenied(exception)
        }
    val adapter =
        try {
            bluetoothManager.adapter ?: error("BluetoothAdapter is unavailable")
        } catch (exception: SecurityException) {
            throw androidPermissionDenied(exception)
        }
    bluetoothAdapter = adapter
    advertiser =
        try {
            adapter.bluetoothLeAdvertiser
        } catch (exception: SecurityException) {
            throw androidPermissionDenied(exception)
        }
    scanner =
        try {
            adapter.bluetoothLeScanner
        } catch (exception: SecurityException) {
            throw androidPermissionDenied(exception)
        }

    val l2capServerSupported = supportsL2capServerSockets()
    log(
        "startTransport hardware scanner=${adapter.bluetoothLeScanner != null} advertiser=${adapter.bluetoothLeAdvertiser != null} carrier=${AndroidDiscoveryAdvertisementConfig.carrier.name} l2capServerSupported=$l2capServerSupported"
    )
    val serverSocket =
        if (l2capServerSupported) {
            runCatching { L2capSocketFactory.listenInsecure(adapter) }
                .onFailure { error ->
                    log("L2CAP server socket unavailable: ${error.message.orEmpty()}")
                }
                .getOrNull()
        } else {
            log("L2CAP server socket unavailable: runtime capability probe returned false")
            null
        }
    l2capServerSocket = serverSocket
    val gattNotifyServer =
        BluetoothGattNotifyServer(
            context = context,
            peerBindings = peerBindings,
            onUnknownPeerFrame = ::registerProvisionalGattPeer,
            onClaimedPeerIdentity = ::registerClaimedGattPeer,
            onFrameReceived = ::enqueueInboundFrame,
            log = ::log,
        )
    gattNotifyServer.start().also { ready ->
        if (!ready) {
            log("GATT notify server unavailable: service did not become ready")
        }
    }
    this.gattNotifyServer = gattNotifyServer
    discoveryLifecycle.updateL2capPsm(
        advertisedDiscoveryL2capPsm(
            serverSocketPsm = serverSocket?.psm,
            localL2capClientSocketsSupported = supportsL2capClientSockets(),
        )
    )
    log("start() with l2capPsm=${currentDiscoveryPayload.l2capPsm}")
    serverSocket?.let(::launchAcceptLoop)

    inboundFrameQueue?.close()
    inboundFrameQueue = createInboundFrameQueue()

    started = true
    registerBluetoothStateChangeReceiver()
    refreshDiscoveryState()
}

internal suspend fun BleTransportAdapter.pauseTransport(): Unit {
    stopTransports(clearPeers = false)
    started = false
}

internal suspend fun BleTransportAdapter.resumeTransport(): Unit {
    if (!started) {
        startTransport()
    }
}

internal suspend fun BleTransportAdapter.stopTransport(): Unit {
    stopTransports(clearPeers = true)
    started = false
}

internal suspend fun BleTransportAdapter.updatePowerPolicyState(policy: PowerPolicy): Unit {
    discoveryLifecycle.updatePowerPolicy(
        policy = policy,
        started = started,
        hardware = discoveryHardware(),
    )
}

internal suspend fun BleTransportAdapter.setDiscoverySuspendedState(suspended: Boolean): Unit {
    discoveryLifecycle.setSuspended(
        suspended = suspended,
        started = started,
        hardware = discoveryHardware(),
    )
}

internal fun BleTransportAdapter.discoveryHardware(): BleTransportDiscoveryHardware {
    return BleTransportDiscoveryHardware(
        hasScanner = scanner != null,
        hasAdvertiser = advertiser != null,
        stopScan = { callback -> scanner?.stopScan(callback) },
        startScan = { powerProfile, callback ->
            log(
                "scan requested mode=${powerProfile.scanMode} carrier=${AndroidDiscoveryAdvertisementConfig.carrier.name}"
            )
            scanner?.startScan(buildScanFilters(), buildScanSettings(powerProfile), callback)
            log("scan start invoked carrier=${AndroidDiscoveryAdvertisementConfig.carrier.name}")
        },
        stopAdvertising = { callback -> advertiser?.stopAdvertising(callback) },
        startAdvertising = { powerProfile, payload, callback ->
            log(
                "advertise requested mode=${powerProfile.advertiseMode} carrier=${AndroidDiscoveryAdvertisementConfig.carrier.name} payloadPsm=${payload.l2capPsm}"
            )
            advertiser?.startAdvertising(
                buildAdvertiseSettings(powerProfile),
                buildAdvertiseData(payload),
                callback,
            )
            log(
                "advertise start invoked carrier=${AndroidDiscoveryAdvertisementConfig.carrier.name} payloadPsm=${payload.l2capPsm}"
            )
        },
    )
}
