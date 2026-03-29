package io.meshlink.transport

import io.meshlink.power.PowerMode
import io.meshlink.power.PowerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Controls BLE scan on/off cycling based on the current [PowerMode].
 *
 * Each power mode defines a scan-on and scan-off duration that together
 * form the duty cycle:
 * - **Performance**: scan 4 s, pause 1 s (80 % duty)
 * - **Balanced**: scan 3 s, pause 3 s (50 % duty)
 * - **PowerSaver**: scan 1 s, pause 5 s (≈17 % duty)
 *
 * Thread-safety: all mutable state is confined to the coroutine that runs
 * the duty-cycle loop, so no external synchronisation is needed.
 */
class ScanDutyCycleController {
    /** Duty-cycle timing for a given power mode. */
    data class CycleTiming(
        val scanOnMs: Long,
        val scanOffMs: Long,
    ) {
        val totalMs: Long get() = scanOnMs + scanOffMs
        val dutyPercent: Int get() = ((scanOnMs * 100) / totalMs).toInt()
    }

    private var cycleJob: Job? = null

    private var currentTiming: CycleTiming = timingFor(PowerMode.PERFORMANCE)

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
                delay(t.scanOnMs)
                if (!isActive || !running) break
                transport.stopAll()
                delay(t.scanOffMs)
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
     * Adjusts the cycle timing for the new [mode].
     * Takes effect at the start of the next cycle iteration.
     */
    fun onPowerModeChanged(mode: PowerMode) {
        currentTiming = timingFor(mode)
    }

    companion object {
        /** Returns the [CycleTiming] for the given [mode], derived from [PowerProfile]. */
        fun timingFor(mode: PowerMode): CycleTiming {
            val profile = PowerProfile.forMode(mode)
            return CycleTiming(scanOnMs = profile.scanOnMs, scanOffMs = profile.scanOffMs)
        }
    }
}
