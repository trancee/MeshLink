package ch.trancee.meshlink.power

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class PowerModeEngine(
    private val scope: CoroutineScope,
    private val batteryMonitor: BatteryMonitor,
    private val clock: () -> Long,
    private val config: PowerConfig,
) {
    var currentTier: PowerTier = PowerTier.PERFORMANCE
        private set

    val tierChanges = MutableSharedFlow<PowerTier>(extraBufferCapacity = 64)

    private var customPowerMode: PowerTier? = null
    private var bootstrapping: Boolean = true

    // Replaces the two separate fields (downgradeTimerTargetTier + downgradeTimerStartedAt)
    // with a single nullable holder to avoid !! operators on co-required fields.
    private class DowngradeTimer(val targetTier: PowerTier, val startedAt: Long)

    private var downgradeTimer: DowngradeTimer? = null

    fun start() {
        scope.launch {
            while (true) {
                evaluateBattery()
                delay(config.batteryPollIntervalMillis)
            }
        }
    }

    fun evaluateBattery() {
        val now = clock()
        val battery = batteryMonitor.readBatteryLevel()

        // a. Bootstrapping: stay at PERFORMANCE, do nothing
        if (bootstrapping) return

        // b. Custom power mode overrides everything
        val custom = customPowerMode
        if (custom != null) {
            if (custom != currentTier) {
                currentTier = custom
                tierChanges.tryEmit(currentTier)
            }
            return
        }

        // c. Charging: cancel downgrade timer, go to PERFORMANCE
        if (batteryMonitor.isCharging) {
            downgradeTimer = null
            if (currentTier != PowerTier.PERFORMANCE) {
                currentTier = PowerTier.PERFORMANCE
                tierChanges.tryEmit(currentTier)
            }
            return
        }

        // d. Compute raw target tier from battery level
        val targetTier =
            when {
                battery > config.performanceThreshold -> PowerTier.PERFORMANCE
                battery < config.powerSaverThreshold -> PowerTier.POWER_SAVER
                else -> PowerTier.BALANCED
            }

        // e. Upward (improving): targetTier is better than currentTier
        if (targetTier.ordinal < currentTier.ordinal) {
            val threshold = thresholdForTransition(from = currentTier, to = targetTier)
            downgradeTimer = null
            if (battery >= threshold + config.hysteresisPercent) {
                currentTier = targetTier
                tierChanges.tryEmit(currentTier)
            }
            return
        }

        // f. Downward (degrading): targetTier is worse than currentTier
        if (targetTier.ordinal > currentTier.ordinal) {
            val threshold = thresholdForTransition(from = currentTier, to = targetTier)
            if (battery <= threshold - config.hysteresisPercent) {
                val existing = downgradeTimer
                val newTimer: DowngradeTimer
                if (existing == null) {
                    // Start new timer
                    newTimer = DowngradeTimer(targetTier = targetTier, startedAt = now)
                } else if (targetTier.ordinal > existing.targetTier.ordinal) {
                    // Target changed to even lower tier: restart timer (cascading hysteresis)
                    newTimer = DowngradeTimer(targetTier = targetTier, startedAt = now)
                } else {
                    // Target same or better: update target but keep timer running
                    newTimer =
                        DowngradeTimer(targetTier = targetTier, startedAt = existing.startedAt)
                }
                downgradeTimer = newTimer
                if (now - newTimer.startedAt >= config.hysteresisDelayMillis) {
                    currentTier = newTimer.targetTier
                    downgradeTimer = null
                    tierChanges.tryEmit(currentTier)
                }
            } else {
                // In dead band (downward signal not strong enough): cancel timer
                downgradeTimer = null
            }
            return
        }

        // g. Same tier: cancel downgrade timer
        downgradeTimer = null
    }

    fun onBootstrapEnd() {
        bootstrapping = false
        evaluateBattery()
    }

    fun setCustomMode(tier: PowerTier?) {
        customPowerMode = tier
        downgradeTimer = null
        evaluateBattery()
    }

    /** Returns the threshold that separates the two tiers we are crossing. */
    private fun thresholdForTransition(from: PowerTier, to: PowerTier): Float =
        when (to) {
            PowerTier.PERFORMANCE -> config.performanceThreshold
            PowerTier.POWER_SAVER -> config.powerSaverThreshold
            PowerTier.BALANCED ->
                if (to.ordinal < from.ordinal) config.powerSaverThreshold // upward from POWER_SAVER
                else config.performanceThreshold // downward from PERFORMANCE
        }
}
