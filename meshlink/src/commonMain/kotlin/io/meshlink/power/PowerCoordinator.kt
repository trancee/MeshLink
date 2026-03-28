package io.meshlink.power

import io.meshlink.util.currentTimeMillis

sealed interface ModeChangeResult {
    data object Unchanged : ModeChangeResult
    data class Changed(val oldMode: String, val newMode: String) : ModeChangeResult
}

/**
 * Facade consolidating power-mode transitions (hysteresis-based),
 * memory-pressure evaluation, and buffer-pressure monitoring behind
 * sealed result types.
 *
 * MeshLink delegates all power decisions to this coordinator and
 * pattern-matches on [ModeChangeResult] to drive health updates
 * and adaptive behavior.
 */
class PowerCoordinator(
    clock: () -> Long = { currentTimeMillis() },
    hysteresisMs: Long = 30_000L,
) {
    private val powerModeEngine = PowerModeEngine(hysteresisMs = hysteresisMs, clock = clock)
    private var _currentMode: String = "PERFORMANCE"

    val currentMode: String get() = _currentMode

    // ── Battery / mode transitions ────────────────────────────────

    fun updateBattery(batteryPercent: Int, isCharging: Boolean): ModeChangeResult {
        val oldMode = _currentMode
        _currentMode = powerModeEngine.update(batteryPercent, isCharging).name
        return if (_currentMode != oldMode) {
            ModeChangeResult.Changed(oldMode, _currentMode)
        } else {
            ModeChangeResult.Unchanged
        }
    }

    // ── Memory pressure evaluation ────────────────────────────────

    fun evaluatePressure(bufferUtilPercent: Int): MemoryPressure? = when {
        bufferUtilPercent >= 90 -> MemoryPressure.CRITICAL
        bufferUtilPercent >= 70 -> MemoryPressure.HIGH
        bufferUtilPercent >= 50 -> MemoryPressure.MODERATE
        else -> null
    }

    fun computeShedActions(
        level: MemoryPressure,
        inboundCount: Int,
        dedupSize: Int,
        peerCount: Int,
    ): List<ShedResult> {
        val shedder = TieredShedder(
            relayBufferCount = inboundCount,
            dedupEntries = dedupSize,
            connectionCount = peerCount,
        )
        return shedder.shed(level)
    }

    // ── Buffer pressure monitoring ────────────────────────────────

    fun shouldWarnBufferPressure(usedBytes: Long, capacity: Long): Boolean {
        if (capacity <= 0) return false
        return (usedBytes * 100 / capacity) >= 80
    }
}
