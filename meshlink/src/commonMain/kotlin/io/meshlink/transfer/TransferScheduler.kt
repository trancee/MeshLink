package io.meshlink.transfer

/**
 * Round-robin scheduler for interleaving concurrent outbound transfers.
 * Ensures fair bandwidth distribution across multiple transfers.
 */
internal class TransferScheduler(
    private val maxConcurrentPerformance: Int = 8,
    private val maxConcurrentBalanced: Int = 4,
    private val maxConcurrentPowerSaver: Int = 1,
) {
    enum class PowerMode { PERFORMANCE, BALANCED, POWER_SAVER }

    /**
     * Represents a scheduled transfer with its key and chunk allocation.
     */
    data class ScheduledTransfer(
        val transferKey: String,
        val chunksToSend: Int,
    )

    private val activeTransfers = mutableListOf<String>()
    private val waitingTransfers = mutableListOf<String>()
    private var currentIndex = 0
    private var currentPowerMode = PowerMode.PERFORMANCE

    /**
     * Set current power mode (affects max concurrency).
     * Downgrading may queue excess active transfers; upgrading may promote waiting ones.
     */
    fun setPowerMode(mode: PowerMode) {
        currentPowerMode = mode
        val max = maxConcurrent()

        // Downgrade: move excess active transfers to the waiting queue (FIFO — demote the newest)
        while (activeTransfers.size > max) {
            val demoted = activeTransfers.removeAt(activeTransfers.lastIndex)
            waitingTransfers.add(0, demoted)
        }

        // Upgrade: promote waiting transfers into active slots
        while (activeTransfers.size < max && waitingTransfers.isNotEmpty()) {
            activeTransfers.add(waitingTransfers.removeAt(0))
        }

        // Keep currentIndex within bounds
        if (activeTransfers.isNotEmpty()) {
            currentIndex = currentIndex.coerceIn(0, activeTransfers.lastIndex)
        } else {
            currentIndex = 0
        }
    }

    /**
     * Register a new transfer. Returns true if it became active,
     * false if it was queued due to concurrency limit.
     * Adding a key that is already active or waiting is a no-op and returns its current status.
     */
    fun addTransfer(transferKey: String): Boolean {
        if (transferKey in activeTransfers) return true
        if (transferKey in waitingTransfers) return false

        return if (activeTransfers.size < maxConcurrent()) {
            activeTransfers.add(transferKey)
            true
        } else {
            waitingTransfers.add(transferKey)
            false
        }
    }

    /**
     * Remove a completed/failed transfer. Promotes the next waiting transfer if any.
     * Returns the key of any newly promoted transfer, or null.
     */
    fun removeTransfer(transferKey: String): String? {
        val activeIdx = activeTransfers.indexOf(transferKey)
        if (activeIdx >= 0) {
            activeTransfers.removeAt(activeIdx)
            // Adjust round-robin pointer so we don't skip a transfer
            if (activeTransfers.isNotEmpty()) {
                if (currentIndex > activeIdx) {
                    currentIndex--
                }
                currentIndex = currentIndex.coerceIn(0, activeTransfers.lastIndex)
            } else {
                currentIndex = 0
            }
            // Promote from waiting queue
            if (waitingTransfers.isNotEmpty() && activeTransfers.size < maxConcurrent()) {
                val promoted = waitingTransfers.removeAt(0)
                activeTransfers.add(promoted)
                return promoted
            }
            return null
        }

        // Also allow removing from the waiting queue (no promotion needed)
        waitingTransfers.remove(transferKey)
        return null
    }

    /**
     * Get the next transfer to send chunks for (round-robin).
     * Returns null if no active transfers.
     * [totalWindow] is the total congestion window across all transfers.
     */
    fun nextTransfer(totalWindow: Int): ScheduledTransfer? {
        if (activeTransfers.isEmpty()) return null

        val count = activeTransfers.size
        if (currentIndex >= count) currentIndex = 0

        val key = activeTransfers[currentIndex]
        currentIndex = (currentIndex + 1) % count

        val perTransfer = maxOf(1, totalWindow / count)
        return ScheduledTransfer(key, perTransfer)
    }

    /** Get all currently active transfer keys (FIFO order). */
    fun activeTransferKeys(): List<String> = activeTransfers.toList()

    /** Get all waiting (queued) transfer keys (FIFO order). */
    fun waitingTransferKeys(): List<String> = waitingTransfers.toList()

    /** Check if a transfer is active (not queued). */
    fun isActive(transferKey: String): Boolean = transferKey in activeTransfers

    /** Max concurrent transfers for the current power mode. */
    fun maxConcurrent(): Int = when (currentPowerMode) {
        PowerMode.PERFORMANCE -> maxConcurrentPerformance
        PowerMode.BALANCED -> maxConcurrentBalanced
        PowerMode.POWER_SAVER -> maxConcurrentPowerSaver
    }

    /** Clear all state. */
    fun clear() {
        activeTransfers.clear()
        waitingTransfers.clear()
        currentIndex = 0
        currentPowerMode = PowerMode.PERFORMANCE
    }
}
