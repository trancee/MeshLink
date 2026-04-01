package io.meshlink.power

class ConnectionLimiter(
    private val limits: Map<PowerMode, Int> = mapOf(
        PowerMode.PERFORMANCE to PowerProfile.PERFORMANCE.maxConnections,
        PowerMode.BALANCED to PowerProfile.BALANCED.maxConnections,
        PowerMode.POWER_SAVER to PowerProfile.POWER_SAVER.maxConnections,
    )
) {
    private var currentLimit = limits[PowerMode.PERFORMANCE] ?: 8
    private val connected = mutableListOf<String>()

    /** Change power mode. Returns list of connection-evicted peer IDs if limit decreased. */
    fun setMode(mode: PowerMode): List<String> {
        currentLimit = limits[mode] ?: currentLimit
        val connectionEvicted = mutableListOf<String>()
        while (connected.size > currentLimit) {
            connectionEvicted.add(connected.removeFirst())
        }
        return connectionEvicted
    }

    fun tryAdd(peerId: String): Boolean {
        if (connected.size >= currentLimit) return false
        connected.add(peerId)
        return true
    }

    fun remove(peerId: String) {
        connected.remove(peerId)
    }

    fun connectionCount(): Int = connected.size

    fun connections(): List<String> = connected.toList()
}
