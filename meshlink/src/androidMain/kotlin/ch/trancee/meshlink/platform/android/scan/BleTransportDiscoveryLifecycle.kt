package ch.trancee.meshlink.platform.android.scan

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import ch.trancee.meshlink.platform.android.power.PowerMonitor
import ch.trancee.meshlink.platform.android.power.PowerProfile
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerTier
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

// SCAN_FAILED_APPLICATION_REGISTRATION_FAILED is treated as its own, separately-tracked retry
// family rather than folded into RETRYABLE_SCAN_ERROR_CODES above. Android throttles scan
// start/stop cycles to roughly 5 per rolling 30s window and returns this code -- usually with no
// exception and no log line -- once that budget is exhausted (enforced more strictly from Android
// 17 onward). The fast, short-backoff family above (500ms/1s/2s, 3 attempts, ~3.5s total) exists
// for transient hardware/internal-error conditions and would exhaust itself long before a 30s
// throttle window clears, so a rate-limited scan start needs a slower, longer-lived backoff that
// is tracked independently of (and does not consume) the fast family's attempt budget.
private val RATE_LIMITED_SCAN_ERROR_CODES =
    setOf(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
private const val MAX_SCAN_RATE_LIMIT_RETRY_ATTEMPTS = 6
private const val SCAN_RATE_LIMIT_RETRY_BASE_DELAY_MILLIS = 2_000L
private const val SCAN_RATE_LIMIT_RETRY_MAX_DELAY_MILLIS = 30_000L

// Defense-in-depth against BLE stacks that silently stop delivering scan results without ever
// invoking onScanFailed -- observed in physical-fleet testing as an intermittent, device-specific
// chipset/firmware scan wedge (see
// docs/explanation/reference-app-physical-integration-findings.md).
// If no scan result arrives for SCAN_WATCHDOG_IDLE_THRESHOLD_MILLIS while actively scanning,
// restart
// scanning alone (not advertising) to attempt a self-heal. The check interval is kept well above
// Android's undocumented "5 scan restarts per 30s" throttle window so the watchdog itself can't
// become a source of the very throttling it's meant to work around.
internal const val SCAN_WATCHDOG_CHECK_INTERVAL_MILLIS = 15_000L
internal const val SCAN_WATCHDOG_IDLE_THRESHOLD_MILLIS = 20_000L

// If the watchdog's own scan-only restart fails to clear the wedge this many times in a row
// (i.e. the scanner is still idle past the threshold immediately after a prior watchdog restart),
// escalate beyond a plain scan restart: attempt a full adapter power-cycle where the platform
// still permits it, and otherwise signal that manual user intervention (toggling Bluetooth) is
// needed. See docs/explanation/reference-app-physical-integration-findings.md for the
// device-specific wedge this defends against.
internal const val MAX_WEDGED_SCAN_RESTARTS_BEFORE_ESCALATION = 2

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
    private val scheduleScanWatchdogCheck: (delayMillis: Long, check: () -> Unit) -> Unit =
        { _, _ ->
        },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    // Best-effort adapter power-cycle attempt for the wedge-escalation path below. Returns true
    // if a power-cycle was actually attempted (platform/permission allowed it), false if it could
    // not be attempted at all (e.g. Android 13+ without BLUETOOTH_PRIVILEGED, where
    // BluetoothAdapter.disable()/enable() are no-ops for normal apps) so the caller can fall back
    // to the manual-recovery signal instead.
    private val attemptBluetoothAdapterPowerCycle: () -> Boolean = { false },
    // Invoked when the watchdog has exhausted its own recovery options (scan restart, then
    // adapter power-cycle where possible) and the wedge still hasn't cleared; the caller is
    // expected to surface this as an actionable prompt asking the user to manually toggle
    // Bluetooth.
    private val onManualBluetoothRecoveryNeeded: () -> Unit = {},
) {
    private val appId: String = appId
    private val localKeyHash: ByteArray = localKeyHash.copyOf()
    private val localMeshHash: UShort = BleDiscoveryContract.computeMeshHash(appId)
    private var lastHardware: BleTransportDiscoveryHardware? = null
    private var advertiseRetryAttempt: Int = 0
    private var scanRetryAttempt: Int = 0

    // Tracks SCAN_FAILED_APPLICATION_REGISTRATION_FAILED (rate-limit) retries separately from
    // scanRetryAttempt above -- see RATE_LIMITED_SCAN_ERROR_CODES for why these need an
    // independent, longer backoff budget.
    private var scanRateLimitRetryAttempt: Int = 0

    // Written from stop()/refresh() (invoked from the adapter's IO-dispatcher coroutineScope) and
    // read from scanCallback (invoked on the main thread by the framework). @Volatile guarantees
    // cross-thread visibility so a scan result delivered after stop() has flipped this cannot slip
    // past the guard below and dispatch a new scan-processing coroutine after teardown started.
    @Volatile private var isStopped: Boolean = true

    // Timestamp of the most recent onScanResult/onBatchScanResults delivery (or of the last scan
    // start/restart, used as the baseline immediately after (re)starting). Compared against
    // nowMillis() by the scan watchdog below to detect a stack that has silently stopped
    // delivering results.
    private var lastScanResultAtMillis: Long = 0L

    // Incremented on every refresh()/stop() so stale, already-superseded watchdog check loops
    // (scheduled by an earlier refresh cycle) recognize they're outdated and stop rescheduling
    // themselves instead of running alongside a newer loop.
    private var scanWatchdogGeneration: Int = 0

    // Counts consecutive watchdog-triggered scan restarts that did not clear the idle wedge
    // (i.e. the scanner was still idle past the threshold the very next time it was checked).
    // Reset to 0 whenever a real scan result arrives or discovery is stopped/refreshed, so a
    // healthy scanner that occasionally goes quiet for one cycle doesn't accumulate escalation
    // credit across unrelated idle periods.
    private var consecutiveWedgedScanRestarts: Int = 0

    // The tier behind the most recently *computed* PowerPolicy, regardless of whether it was ever
    // actually applied to the hardware (see lastAppliedPowerTier below).
    private var currentPowerTier: PowerTier = PowerTier.BALANCED

    // The tier that produced the scan/advertise settings currently applied to the hardware, kept
    // in sync every time refresh() actually restarts scan/advertise (whether triggered by
    // updatePowerPolicy or by another caller such as the initial startTransport()/setSuspended()
    // refresh). Used by updatePowerPolicy to skip a redundant restart when the newly-computed tier
    // already matches what's live -- see updatePowerPolicy for why this matters.
    private var lastAppliedPowerTier: PowerTier = PowerTier.BALANCED

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
                if (isStopped) return
                scanRetryAttempt = 0
                scanRateLimitRetryAttempt = 0
                lastScanResultAtMillis = nowMillis()
                consecutiveWedgedScanRestarts = 0
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (isStopped) return
                scanRetryAttempt = 0
                scanRateLimitRetryAttempt = 0
                lastScanResultAtMillis = nowMillis()
                consecutiveWedgedScanRestarts = 0
                results.forEach(handleScanResult)
            }

            override fun onScanFailed(errorCode: Int) {
                log("scan failed errorCode=$errorCode errorName=${scanErrorCodeName(errorCode)}")
                val willRetry =
                    if (errorCode in RATE_LIMITED_SCAN_ERROR_CODES) {
                        maybeRetryScanRateLimited(errorCode)
                    } else {
                        maybeRetryScan(errorCode)
                    }
                val attempt =
                    if (errorCode in RATE_LIMITED_SCAN_ERROR_CODES) {
                        scanRateLimitRetryAttempt
                    } else {
                        scanRetryAttempt
                    }
                onScanFailed(errorCode, scanErrorCodeName(errorCode), willRetry, attempt)
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
        val tierUnchanged =
            started && !isStopped && !isDiscoverySuspended && policy.tier == lastAppliedPowerTier
        currentPowerTier = policy.tier
        currentPowerProfile = PowerMonitor.profileFor(policy)
        currentDiscoveryPayload = buildPayload(l2capPsm = currentDiscoveryPayload.l2capPsm)
        if (tierUnchanged) {
            // Battery/power-policy observations (e.g. periodic battery-changed broadcasts while
            // charging) commonly resolve to the same tier repeatedly. Restarting scan+advertise
            // (stopScan+startScan) on every such no-op update was observed to trip Android's
            // undocumented BLE "scanning too frequently" throttle (roughly 5 start/stop cycles per
            // 30s), after which onScanResult silently stops firing for a cooldown window with no
            // error callback -- see
            // docs/explanation/reference-app-physical-integration-findings.md.
            // Skip the restart entirely when nothing about the applied settings would change.
            log(
                "updatePowerPolicy skipped restart: tier unchanged tier=${policy.tier} started=$started"
            )
            return
        }
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
        scanRateLimitRetryAttempt = 0
        isStopped = true
        advertiseRetryAttempt = 0
        scanRetryAttempt = 0
        scanWatchdogGeneration += 1
        consecutiveWedgedScanRestarts = 0
        hardware.stopScan(scanCallback)
        hardware.stopAdvertising(advertiseCallback)
    }

    fun refresh(started: Boolean, hardware: BleTransportDiscoveryHardware): Unit {
        lastHardware = hardware
        isStopped = !started
        advertiseRetryAttempt = 0
        scanRetryAttempt = 0
        scanRateLimitRetryAttempt = 0
        scanWatchdogGeneration += 1
        consecutiveWedgedScanRestarts = 0
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
        lastAppliedPowerTier = currentPowerTier
        hardware.startScan(currentPowerProfile, scanCallback)
        log("scan started")
        lastScanResultAtMillis = nowMillis()
        val watchdogGeneration = scanWatchdogGeneration
        if (hardware.hasScanner) {
            scheduleScanWatchdogCheck(SCAN_WATCHDOG_CHECK_INTERVAL_MILLIS) {
                runScanWatchdogCheck(watchdogGeneration, hardware)
            }
        }
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

    // Self-perpetuating watchdog loop: re-checks every SCAN_WATCHDOG_CHECK_INTERVAL_MILLIS for as
    // long as this generation is still the current one (i.e. no refresh()/stop() has superseded
    // it). If no scan result has arrived for SCAN_WATCHDOG_IDLE_THRESHOLD_MILLIS, restarts scanning
    // alone as a best-effort self-heal against a wedged BLE scanner -- see the constant doc comment
    // above for the observed motivating failure mode.
    private fun runScanWatchdogCheck(
        generation: Int,
        hardware: BleTransportDiscoveryHardware,
    ): Unit {
        if (
            generation != scanWatchdogGeneration ||
                isStopped ||
                isDiscoverySuspended ||
                !hardware.hasScanner
        ) {
            return
        }
        val idleMillis = nowMillis() - lastScanResultAtMillis
        if (idleMillis >= SCAN_WATCHDOG_IDLE_THRESHOLD_MILLIS) {
            consecutiveWedgedScanRestarts += 1
            log(
                "scan watchdog restarting scan after idleMillis=$idleMillis consecutiveWedgedScanRestarts=$consecutiveWedgedScanRestarts"
            )
            if (consecutiveWedgedScanRestarts >= MAX_WEDGED_SCAN_RESTARTS_BEFORE_ESCALATION) {
                escalateWedgedScanRecovery()
            }
            hardware.stopScan(scanCallback)
            hardware.startScan(currentPowerProfile, scanCallback)
            lastScanResultAtMillis = nowMillis()
        }
        scheduleScanWatchdogCheck(SCAN_WATCHDOG_CHECK_INTERVAL_MILLIS) {
            runScanWatchdogCheck(generation, hardware)
        }
    }

    // A plain scan restart has now repeatedly failed to clear the wedge. Attempt a full adapter
    // power-cycle first (only actually effective pre-Android 13 for a non-privileged app -- see
    // attemptBluetoothAdapterPowerCycle's doc comment); if that isn't possible on this platform,
    // fall back to signalling that manual user intervention is needed. Either way, reset the
    // streak afterwards so a still-wedged scanner escalates again after another full streak
    // rather than firing on every subsequent watchdog check.
    private fun escalateWedgedScanRecovery(): Unit {
        val powerCycleAttempted = attemptBluetoothAdapterPowerCycle()
        log("scan watchdog escalating wedge recovery powerCycleAttempted=$powerCycleAttempted")
        if (!powerCycleAttempted) {
            onManualBluetoothRecoveryNeeded()
        }
        consecutiveWedgedScanRestarts = 0
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

    // Shared eligibility guard for both scan-retry families below: the scanner must still be an
    // active target (hardware present, discovery running and not suspended, a real scanner on
    // this device) before either family schedules a retry at all. Extracted so the fast-error and
    // rate-limited retry paths share one guard shape instead of duplicating it, and so neither
    // grows a six-clause ComplexCondition of its own.
    private fun canScheduleScanRetry(hardware: BleTransportDiscoveryHardware?): Boolean {
        return hardware != null && !isStopped && !isDiscoverySuspended && hardware.hasScanner
    }

    private fun maybeRetryScan(errorCode: Int): Boolean {
        val hardware = lastHardware
        if (
            !canScheduleScanRetry(hardware) ||
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

    private fun maybeRetryScanRateLimited(errorCode: Int): Boolean {
        val hardware = lastHardware
        if (
            !canScheduleScanRetry(hardware) ||
                errorCode !in RATE_LIMITED_SCAN_ERROR_CODES ||
                scanRateLimitRetryAttempt >= MAX_SCAN_RATE_LIMIT_RETRY_ATTEMPTS
        ) {
            return false
        }
        scanRateLimitRetryAttempt += 1
        val delayMillis =
            minOf(
                SCAN_RATE_LIMIT_RETRY_BASE_DELAY_MILLIS shl (scanRateLimitRetryAttempt - 1),
                SCAN_RATE_LIMIT_RETRY_MAX_DELAY_MILLIS,
            )
        log(
            "scan rate-limit retry scheduled attempt=$scanRateLimitRetryAttempt " +
                "delayMillis=$delayMillis errorCode=$errorCode errorName=${scanErrorCodeName(errorCode)}"
        )
        scheduleScanRetry(delayMillis) { retryScan() }
        return true
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
