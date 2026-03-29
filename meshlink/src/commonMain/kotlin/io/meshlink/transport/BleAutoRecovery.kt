package io.meshlink.transport

/**
 * Monitors BLE transport health and triggers recovery when the stack
 * appears unresponsive (extended silence with no received data or advertisements).
 */
class BleAutoRecovery(
    private val silenceThresholdMillis: Long = 60_000L,
    private val maxRecoveriesPerHour: Int = 3,
    private val clock: () -> Long = { io.meshlink.util.currentTimeMillis() },
) {
    private var lastActivityMillis: Long = clock()
    private val recoveryTimestamps = mutableListOf<Long>()

    /**
     * Record BLE activity (advertisement received, data received, etc.).
     * Resets the silence timer.
     */
    fun recordActivity() {
        lastActivityMillis = clock()
    }

    /**
     * Check if silence threshold has been exceeded.
     * Returns true if no activity has been recorded for [silenceThresholdMillis].
     */
    fun isSilent(): Boolean = silenceDurationMillis() >= silenceThresholdMillis

    /**
     * Check if a recovery attempt is allowed (under hourly limit).
     */
    fun canRecover(): Boolean = recoveriesInLastHour() < maxRecoveriesPerHour

    /**
     * Record a recovery attempt. Call this when recovery is actually triggered.
     * Returns false if recovery is not allowed (rate limited).
     */
    fun recordRecovery(): Boolean {
        if (!canRecover()) return false
        recoveryTimestamps.add(clock())
        return true
    }

    /**
     * Milliseconds since last activity.
     */
    fun silenceDurationMillis(): Long = clock() - lastActivityMillis

    /**
     * Number of recoveries in the last hour.
     */
    fun recoveriesInLastHour(): Int {
        val oneHourAgo = clock() - 3_600_000L
        recoveryTimestamps.removeAll { it <= oneHourAgo }
        return recoveryTimestamps.size
    }

    /**
     * Reset all state (e.g., on manual start/stop).
     */
    fun reset() {
        lastActivityMillis = clock()
        recoveryTimestamps.clear()
    }
}
