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

    log(
        "startTransport hardware scanner=${adapter.bluetoothLeScanner != null} advertiser=${adapter.bluetoothLeAdvertiser != null} carrier=${AndroidDiscoveryAdvertisementConfig.carrier.name}"
    )
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
    discoveryLifecycle.updateL2capPsm((serverSocket?.psm ?: 0).toUByte())
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
