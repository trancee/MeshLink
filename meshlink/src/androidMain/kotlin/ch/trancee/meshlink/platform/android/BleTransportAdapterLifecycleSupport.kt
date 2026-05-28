package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.power.PowerPolicy

internal suspend fun BleTransportAdapter.startTransport(): Unit {
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

    val serverSocket =
        runCatching {
                L2capSocketFactory.listenInsecure(adapter) { error ->
                    log(
                        "explicit insecure L2CAP server socket fallback: ${error.message.orEmpty()}"
                    )
                }
            }
            .onFailure { error ->
                log("L2CAP server socket unavailable: ${error.message.orEmpty()}")
            }
            .getOrNull()
    l2capServerSocket = serverSocket
    currentDiscoveryPayload =
        buildDiscoveryPayload(
            appId = appId,
            localKeyHash = localKeyHash,
            currentPowerProfile = currentPowerProfile,
            l2capPsm = (serverSocket?.psm ?: 0).toUByte(),
        )
    log("start() with l2capPsm=${currentDiscoveryPayload.l2capPsm}")
    serverSocket?.let(::launchAcceptLoop)

    inboundFrameQueue?.close()
    inboundFrameQueue = createInboundFrameQueue()

    started = true
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
    currentPowerProfile = PowerMonitor.profileFor(policy)
    currentDiscoveryPayload =
        buildDiscoveryPayload(
            appId = appId,
            localKeyHash = localKeyHash,
            currentPowerProfile = currentPowerProfile,
            l2capPsm = currentDiscoveryPayload.l2capPsm,
        )
    if (!started) {
        return
    }
    refreshDiscoveryState()
}

internal suspend fun BleTransportAdapter.setDiscoverySuspendedState(suspended: Boolean): Unit {
    if (discoverySuspended == suspended) {
        return
    }
    discoverySuspended = suspended
    if (!started) {
        return
    }
    log("discovery suspended=$suspended")
    refreshDiscoveryState()
}
