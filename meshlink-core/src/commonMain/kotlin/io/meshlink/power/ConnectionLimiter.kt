package io.meshlink.power

class ConnectionLimiter(
    private val limits: Map<PowerMode, Int> = mapOf(
        PowerMode.PERFORMANCE to 8,
        PowerMode.BALANCED to 4,
        PowerMode.POWER_SAVER to 1,
    )
) {
    private var currentLimit = limits[PowerMode.PERFORMANCE] ?: 8
    private val connected = mutableListOf<String>()

    /** Change power mode. Returns list of evicted peer IDs if limit decreased. */
    fun setMode(mode: PowerMode): List<String> {
        currentLimit = limits[mode] ?: currentLimit
        val evicted = mutableListOf<String>()
        while (connected.size > currentLimit) {
            evicted.add(connected.removeFirst())
        }
        return evicted
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
