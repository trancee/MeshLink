package io.meshlink.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Controls BLE scan on/off cycling based on the current power mode.
 *
 * Accepts raw scan timing values — callers resolve power mode to timing
 * externally (via `PowerProfile.forMode()`), keeping the transport layer
 * free of power-management imports.
 *
 * Thread-safety: all mutable state is confined to the coroutine that runs
 * the duty-cycle loop, so no external synchronisation is needed.
 */
class ScanDutyCycleController {
    /** Duty-cycle timing. */
    data class CycleTiming(
        val scanOnMillis: Long,
        val scanOffMillis: Long,
    ) {
        val totalMillis: Long get() = scanOnMillis + scanOffMillis
        val dutyPercent: Int get() = ((scanOnMillis * 100) / totalMillis).toInt()
    }

    private var cycleJob: Job? = null

    private var currentTiming: CycleTiming = CycleTiming(
        scanOnMillis = 4_000L,
        scanOffMillis = 1_000L,
    )

    private var running = false

    /** Whether the duty-cycle loop is currently active. */
    val isRunning: Boolean get() = running

    /** The current cycle timing. */
    val timing: CycleTiming get() = currentTiming

    /**
     * Starts the scan duty-cycle loop.
     *
     * Each iteration calls [transport]`.startAdvertisingAndScanning()` for the
     * scan-on window, then [transport]`.stopAll()` for the scan-off window.
     */
    fun start(scope: CoroutineScope, transport: BleTransport) {
        stop()
        running = true
        cycleJob = scope.launch {
            while (isActive && running) {
                val t = currentTiming
                transport.startAdvertisingAndScanning()
                delay(t.scanOnMillis)
                if (!isActive || !running) break
                transport.stopAll()
                delay(t.scanOffMillis)
            }
        }
    }

    /** Stops the duty-cycle loop and cancels the running coroutine. */
    fun stop() {
        running = false
        cycleJob?.cancel()
        cycleJob = null
    }

    /**
     * Adjusts the cycle timing. Takes effect at the start of the next cycle
     * iteration.
     */
    fun onTimingChanged(scanOnMillis: Long, scanOffMillis: Long) {
        currentTiming = CycleTiming(
            scanOnMillis = scanOnMillis,
            scanOffMillis = scanOffMillis,
        )
    }
}
