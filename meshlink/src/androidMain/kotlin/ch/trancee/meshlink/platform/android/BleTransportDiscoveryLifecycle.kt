package ch.trancee.meshlink.platform.android

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPayload

internal data class BleTransportDiscoveryHardware(
    val hasScanner: Boolean,
    val hasAdvertiser: Boolean,
    val stopScan: (ScanCallback) -> Unit = {},
    val startScan: (PowerProfile, ScanCallback) -> Unit = { _, _ -> },
    val stopAdvertising: (AdvertiseCallback) -> Unit = {},
    val startAdvertising: (PowerProfile, BleDiscoveryPayload, AdvertiseCallback) -> Unit =
        { _, _, _ ->
        },
)

internal class BleTransportDiscoveryLifecycle(
    appId: String,
    localKeyHash: ByteArray,
    private val handleScanResult: (ScanResult) -> Unit,
    private val ensurePermissionsGranted: () -> Unit,
    private val log: (String) -> Unit,
) {
    private val appId: String = appId
    private val localKeyHash: ByteArray = localKeyHash.copyOf()

    var currentPowerProfile: PowerProfile = PowerMonitor.defaultProfile()
        private set

    var currentDiscoveryPayload: BleDiscoveryPayload = buildPayload(l2capPsm = 0u)
        private set

    var isDiscoverySuspended: Boolean = false
        private set

    val advertiseCallback =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                log(
                    "advertising started mode=${settingsInEffect.mode} tx=${settingsInEffect.txPowerLevel} connectable=${settingsInEffect.isConnectable}"
                )
            }

            override fun onStartFailure(errorCode: Int) {
                log(
                    "advertising failed errorCode=$errorCode carrier=${AndroidDiscoveryAdvertisementConfig.carrier.name} " +
                        "mode=${currentPowerProfile.advertiseMode} tx=${currentPowerProfile.txPowerLevel} " +
                        "connectable=true psm=${currentDiscoveryPayload.l2capPsm}"
                )
            }
        }

    val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(handleScanResult)
            }
        }

    fun updateL2capPsm(l2capPsm: UByte): Unit {
        currentDiscoveryPayload = buildPayload(l2capPsm = l2capPsm)
    }

    fun updatePowerPolicy(
        policy: PowerPolicy,
        started: Boolean,
        hardware: BleTransportDiscoveryHardware,
    ): Unit {
        currentPowerProfile = PowerMonitor.profileFor(policy)
        currentDiscoveryPayload = buildPayload(l2capPsm = currentDiscoveryPayload.l2capPsm)
        if (started) {
            refresh(started = true, hardware = hardware)
        }
    }

    fun setSuspended(
        suspended: Boolean,
        started: Boolean,
        hardware: BleTransportDiscoveryHardware,
    ): Unit {
        if (isDiscoverySuspended == suspended) {
            return
        }
        isDiscoverySuspended = suspended
        if (!started) {
            return
        }
        log("discovery suspended=$suspended")
        refresh(started = true, hardware = hardware)
    }

    fun stop(hardware: BleTransportDiscoveryHardware): Unit {
        hardware.stopScan(scanCallback)
        hardware.stopAdvertising(advertiseCallback)
    }

    fun refresh(started: Boolean, hardware: BleTransportDiscoveryHardware): Unit {
        log(
            "refreshDiscoveryState started=$started suspended=$isDiscoverySuspended scanner=${hardware.hasScanner} advertiser=${hardware.hasAdvertiser} activeMeshHash=${BleDiscoveryContract.computeMeshHash(appId)} advertisedMeshHash=${currentDiscoveryPayload.meshHash} psm=${currentDiscoveryPayload.l2capPsm} carrier=${AndroidDiscoveryAdvertisementConfig.carrier.name}"
        )
        log(
            "discovery.summary activeMeshHash=${BleDiscoveryContract.computeMeshHash(appId)} advertisedMeshHash=${currentDiscoveryPayload.meshHash} psm=${currentDiscoveryPayload.l2capPsm} carrier=${AndroidDiscoveryAdvertisementConfig.carrier.name}"
        )
        stop(hardware)
        if (!started || isDiscoverySuspended) {
            log(
                "refreshDiscoveryState skipped after stop started=$started suspended=$isDiscoverySuspended"
            )
            return
        }
        ensurePermissionsGranted()
        hardware.startScan(currentPowerProfile, scanCallback)
        log("scan started")
        if (hardware.hasAdvertiser) {
            hardware.startAdvertising(
                currentPowerProfile,
                currentDiscoveryPayload,
                advertiseCallback,
            )
        } else {
            log("advertise skipped: advertiser unavailable")
        }
        log(
            "advertise requested carrier=${AndroidDiscoveryAdvertisementConfig.carrier.name} payloadPsm=${currentDiscoveryPayload.l2capPsm}"
        )
    }

    private fun buildPayload(l2capPsm: UByte): BleDiscoveryPayload {
        return buildDiscoveryPayload(
            appId = appId,
            localKeyHash = localKeyHash,
            currentPowerProfile = currentPowerProfile,
            l2capPsm = l2capPsm,
        )
    }
}
