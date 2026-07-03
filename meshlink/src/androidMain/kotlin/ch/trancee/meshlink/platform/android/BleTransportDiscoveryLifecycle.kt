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

// Environmental/transient advertise failures that are worth retrying: the BLE
// stack is often just temporarily out of advertiser slots (many concurrent
// apps/devices) or hit an internal error, both of which commonly clear up
// shortly afterwards without any local state change. ALREADY_STARTED is
// included because some stacks still consider a failed advertise set
// "active" internally until stopAdvertising() is called again, which we do
// before every retry attempt below.
private val RETRYABLE_ADVERTISE_ERROR_CODES =
    setOf(
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS,
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR,
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED,
    )
private const val MAX_ADVERTISE_RETRY_ATTEMPTS = 3
private const val ADVERTISE_RETRY_BASE_DELAY_MILLIS = 500L

// AdvertiseCallback only hands back a bare Int error code; map it to its
// symbolic constant name so logs are actionable without needing to cross
// reference the Android SDK source (e.g. errorCode=2 -> ADVERTISE_FAILED_TOO_MANY_ADVERTISERS).
internal fun advertiseErrorCodeName(errorCode: Int): String {
    return when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ADVERTISE_FAILED_ALREADY_STARTED"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "ADVERTISE_FAILED_DATA_TOO_LARGE"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
            "ADVERTISE_FAILED_FEATURE_UNSUPPORTED"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "ADVERTISE_FAILED_INTERNAL_ERROR"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
            "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS"
        else -> "ADVERTISE_FAILED_UNKNOWN"
    }
}

// Environmental/transient scan-start failures worth retrying: the BLE stack
// can be temporarily out of scan client slots or hit an internal/hardware
// error, both of which commonly clear up shortly afterwards without any
// local state change. ALREADY_STARTED is included because some stacks still
// consider a failed scan "active" internally until stopScan() is called
// again, which we do before every retry attempt below.
private val RETRYABLE_SCAN_ERROR_CODES =
    setOf(
        ScanCallback.SCAN_FAILED_ALREADY_STARTED,
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR,
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES,
        ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY,
    )
private const val MAX_SCAN_RETRY_ATTEMPTS = 3
private const val SCAN_RETRY_BASE_DELAY_MILLIS = 500L

// ScanCallback only hands back a bare Int error code; map it to its symbolic
// constant name so logs/diagnostics are actionable without needing to cross
// reference the Android SDK source (e.g. errorCode=1 -> SCAN_FAILED_ALREADY_STARTED).
internal fun scanErrorCodeName(errorCode: Int): String {
    return when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES ->
            "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
        ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY"
        else -> "SCAN_FAILED_UNKNOWN"
    }
}

internal class BleTransportDiscoveryLifecycle(
    appId: String,
    localKeyHash: ByteArray,
    private val handleScanResult: (ScanResult) -> Unit,
    private val ensurePermissionsGranted: () -> Unit,
    private val foreignScanIgnoredCount: () -> Int,
    private val log: (String) -> Unit,
    private val scheduleAdvertiseRetry: (delayMillis: Long, retry: () -> Unit) -> Unit = { _, _ ->
    },
    private val onAdvertiseFailed:
        (errorCode: Int, errorName: String, willRetry: Boolean, attempt: Int) -> Unit =
        { _, _, _, _ ->
        },
    private val scheduleScanRetry: (delayMillis: Long, retry: () -> Unit) -> Unit = { _, _ -> },
    private val onScanFailed:
        (errorCode: Int, errorName: String, willRetry: Boolean, attempt: Int) -> Unit =
        { _, _, _, _ ->
        },
) {
    private val appId: String = appId
    private val localKeyHash: ByteArray = localKeyHash.copyOf()
    private val localMeshHash: UShort = BleDiscoveryContract.computeMeshHash(appId)
    private var lastHardware: BleTransportDiscoveryHardware? = null
    private var advertiseRetryAttempt: Int = 0
    private var scanRetryAttempt: Int = 0
    private var isStopped: Boolean = true

    var currentPowerProfile: PowerProfile = PowerMonitor.defaultProfile()
        private set

    var currentDiscoveryPayload: BleDiscoveryPayload = buildPayload(l2capPsm = 0u)
        private set

    var isDiscoverySuspended: Boolean = false
        private set

    val advertiseCallback =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                advertiseRetryAttempt = 0
                log(
                    "advertising started mode=${settingsInEffect.mode} tx=${settingsInEffect.txPowerLevel} connectable=${settingsInEffect.isConnectable}"
                )
            }

            override fun onStartFailure(errorCode: Int) {
                log(
                    "advertising failed errorCode=$errorCode errorName=${advertiseErrorCodeName(errorCode)} carrier=${AndroidDiscoveryAdvertisementConfig.carrier.name} " +
                        "mode=${currentPowerProfile.advertiseMode} tx=${currentPowerProfile.txPowerLevel} " +
                        "connectable=true psm=${currentDiscoveryPayload.l2capPsm}"
                )
                val willRetry = maybeRetryAdvertising(errorCode)
                onAdvertiseFailed(
                    errorCode,
                    advertiseErrorCodeName(errorCode),
                    willRetry,
                    advertiseRetryAttempt,
                )
            }
        }

    val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scanRetryAttempt = 0
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                scanRetryAttempt = 0
                results.forEach(handleScanResult)
            }

            override fun onScanFailed(errorCode: Int) {
                log("scan failed errorCode=$errorCode errorName=${scanErrorCodeName(errorCode)}")
                val willRetry = maybeRetryScan(errorCode)
                onScanFailed(errorCode, scanErrorCodeName(errorCode), willRetry, scanRetryAttempt)
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
        isStopped = true
        advertiseRetryAttempt = 0
        scanRetryAttempt = 0
        hardware.stopScan(scanCallback)
        hardware.stopAdvertising(advertiseCallback)
    }

    fun refresh(started: Boolean, hardware: BleTransportDiscoveryHardware): Unit {
        lastHardware = hardware
        isStopped = !started
        advertiseRetryAttempt = 0
        scanRetryAttempt = 0
        log(
            "refreshDiscoveryState started=$started suspended=$isDiscoverySuspended scanner=${hardware.hasScanner} advertiser=${hardware.hasAdvertiser} activeMeshHash=$localMeshHash advertisedMeshHash=${currentDiscoveryPayload.meshHash} psm=${currentDiscoveryPayload.l2capPsm} carrier=${AndroidDiscoveryAdvertisementConfig.carrier.name} foreignScanIgnoredCount=${foreignScanIgnoredCount()}"
        )
        log(
            "discovery.summary activeMeshHash=$localMeshHash advertisedMeshHash=${currentDiscoveryPayload.meshHash} psm=${currentDiscoveryPayload.l2capPsm} carrier=${AndroidDiscoveryAdvertisementConfig.carrier.name} foreignScanIgnoredCount=${foreignScanIgnoredCount()}"
        )
        hardware.stopScan(scanCallback)
        hardware.stopAdvertising(advertiseCallback)
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

    private fun maybeRetryAdvertising(errorCode: Int): Boolean {
        val hardware = lastHardware
        if (
            hardware == null ||
                isStopped ||
                isDiscoverySuspended ||
                !hardware.hasAdvertiser ||
                errorCode !in RETRYABLE_ADVERTISE_ERROR_CODES ||
                advertiseRetryAttempt >= MAX_ADVERTISE_RETRY_ATTEMPTS
        ) {
            return false
        }
        advertiseRetryAttempt += 1
        val delayMillis = ADVERTISE_RETRY_BASE_DELAY_MILLIS shl (advertiseRetryAttempt - 1)
        log(
            "advertise retry scheduled attempt=$advertiseRetryAttempt delayMillis=$delayMillis errorCode=$errorCode errorName=${advertiseErrorCodeName(errorCode)}"
        )
        scheduleAdvertiseRetry(delayMillis) { retryAdvertising() }
        return true
    }

    private fun retryAdvertising(): Unit {
        val hardware = lastHardware
        if (hardware == null || isStopped || isDiscoverySuspended || !hardware.hasAdvertiser) {
            log("advertise retry skipped stopped=$isStopped suspended=$isDiscoverySuspended")
            return
        }
        log("advertise retry invoked attempt=$advertiseRetryAttempt")
        // Some BLE stacks still treat a failed advertise set as "active"
        // internally until stopAdvertising() is called again; without this
        // the retry can itself fail with ADVERTISE_FAILED_ALREADY_STARTED.
        hardware.stopAdvertising(advertiseCallback)
        hardware.startAdvertising(currentPowerProfile, currentDiscoveryPayload, advertiseCallback)
    }

    private fun maybeRetryScan(errorCode: Int): Boolean {
        val hardware = lastHardware
        if (
            hardware == null ||
                isStopped ||
                isDiscoverySuspended ||
                !hardware.hasScanner ||
                errorCode !in RETRYABLE_SCAN_ERROR_CODES ||
                scanRetryAttempt >= MAX_SCAN_RETRY_ATTEMPTS
        ) {
            return false
        }
        scanRetryAttempt += 1
        val delayMillis = SCAN_RETRY_BASE_DELAY_MILLIS shl (scanRetryAttempt - 1)
        log(
            "scan retry scheduled attempt=$scanRetryAttempt delayMillis=$delayMillis errorCode=$errorCode errorName=${scanErrorCodeName(errorCode)}"
        )
        scheduleScanRetry(delayMillis) { retryScan() }
        return true
    }

    private fun retryScan(): Unit {
        val hardware = lastHardware
        if (hardware == null || isStopped || isDiscoverySuspended || !hardware.hasScanner) {
            log("scan retry skipped stopped=$isStopped suspended=$isDiscoverySuspended")
            return
        }
        log("scan retry invoked attempt=$scanRetryAttempt")
        // Some BLE stacks still treat a failed scan as "active" internally
        // until stopScan() is called again; without this the retry can
        // itself fail with SCAN_FAILED_ALREADY_STARTED.
        hardware.stopScan(scanCallback)
        hardware.startScan(currentPowerProfile, scanCallback)
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
